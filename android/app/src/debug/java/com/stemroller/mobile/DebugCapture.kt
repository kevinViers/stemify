package com.stemroller.mobile

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import java.io.File
import java.lang.ref.WeakReference

/**
 * `adb shell am broadcast -a com.stemroller.mobile.CAPTURE -p com.stemroller.mobile`
 * writes files/capture.png: the current activity drawn in software.
 *
 * `... -a com.stemroller.mobile.BACK --es phase start|progress|commit|cancel --ef p 0.4`
 * feeds the predictive back callbacks the way a system swipe would.
 */
class DebugCapture : ContentProvider() {
    private var current = WeakReference<Activity>(null)

    override fun onCreate(): Boolean {
        val app = context!!.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { current = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val a = current.get() ?: return
                val v = a.window.decorView
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                File(a.filesDir, "capture.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }, IntentFilter("com.stemroller.mobile.CAPTURE"), Context.RECEIVER_EXPORTED)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val a = current.get() as? ComponentActivity ?: return
                val d = a.onBackPressedDispatcher
                val p = i.getFloatExtra("p", 0f)
                val e = BackEventCompat(p * 600f, 1300f, p, BackEventCompat.EDGE_LEFT)
                when (i.getStringExtra("phase")) {
                    "start" -> d.dispatchOnBackStarted(e)
                    "progress" -> d.dispatchOnBackProgressed(e)
                    "commit" -> d.onBackPressed()
                    "cancel" -> d.dispatchOnBackCancelled()
                }
            }
        }, IntentFilter("com.stemroller.mobile.BACK"), Context.RECEIVER_EXPORTED)
        return true
    }

    override fun query(u: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: android.net.Uri) = null
    override fun insert(u: android.net.Uri, v: ContentValues?) = null
    override fun delete(u: android.net.Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: android.net.Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
