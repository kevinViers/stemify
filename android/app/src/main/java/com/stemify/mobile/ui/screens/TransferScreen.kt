package com.stemify.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.stemify.mobile.App
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.transfer.Backup
import com.stemify.mobile.transfer.restore
import com.stemify.mobile.ui.components.Cell
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.Overlays
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.section
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moving to a new phone: this one makes a backup file (settings, EQ, queue and mix, and the
 * list of downloads), it travels however files do (Quick Share, Drive, a cable), and the new
 * phone restores it and downloads the same music from the server.
 */
@Composable
fun TransferScreen() {
    val g = App.graph
    val c = Ios.colors
    val ctx = LocalContext.current
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val records by g.downloads.records.collectAsState()
    val playlists by g.downloads.playlists.collectAsState()
    val incoming by g.incomingBackup.collectAsState()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = backupText()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }.isSuccess
            overlays.hud(if (ok) "Saved" else "Couldn't save the backup", if (ok) Sym.check else Sym.warning)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { offerRestore(ctx, it, overlays) }
    }
    // A backup file opened from Files or a Quick Share notification lands here.
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        g.incomingBackup.value = null
        offerRestore(ctx, uri, overlays)
    }

    val songs = records.values.count { it.direct }
    Page("Transfer", grouped = true) {
        section("send", header = "From This Phone",
            footer = "Settings, EQ profiles, the queue and its stem mix, and the list of what's downloaded " +
                "($songs songs, ${playlists.size} playlists). The new phone downloads the music itself from your server. " +
                "The file includes your Plex and stem server sign-ins, so keep it to yourself.",
            rows = listOf(
                { Cell("Send to New Phone…", titleColor = c.accent) { scope.launch { share(ctx, overlays) } } },
                { Cell("Save as a File…", titleColor = c.accent) { save.launch(fileName()) } },
            ))
        section("receive", header = "On the New Phone",
            footer = "Pick the backup file, or just open it from Files or the Quick Share notification. " +
                "Settings and EQ replace this phone's; downloads are added to what's here.",
            rows = listOf {
                Cell("Restore from a Backup…", titleColor = c.accent) { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
            })
    }
}

private fun fileName(): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "Stemify backup ${android.os.Build.MODEL} $day.json"
}

private suspend fun backupText(): String {
    val g = App.graph
    val b = Backup.make(g.settings, g.eq, g.playback, g.downloads)
    return withContext(Dispatchers.Default) { AppJson.encodeToString(Backup.serializer(), b) }
}

/** Writes the backup where the share sheet can hand it out, and opens the share sheet. */
private suspend fun share(ctx: Context, overlays: Overlays) {
    val file = runCatching {
        val text = backupText()
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "transfer").apply { deleteRecursively(); mkdirs() }
            File(dir, fileName()).apply { writeText(text) }
        }
    }.getOrElse {
        overlays.hud("Couldn't make the backup", Sym.warning)
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(Backup.MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Send Stemify Backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

/** Reads a backup, says what's in it, and restores it once confirmed. */
private fun offerRestore(ctx: Context, uri: Uri, overlays: Overlays) {
    val g = App.graph
    g.scope.launch {
        val backup = runCatching {
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }
            withContext(Dispatchers.Default) { Backup.parse(text) }
        }.getOrElse {
            overlays.hud(it.message?.takeIf { m -> m.startsWith("That") } ?: "Couldn't read that file", Sym.warning)
            return@launch
        }
        val made = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(backup.made))
        val parts = listOfNotNull(
            "${backup.settings.size} settings",
            backup.eq?.let { "${it.profiles.size} EQ profiles" },
            backup.queueLength.takeIf { it > 0 }?.let { "a queue of $it songs with its stem mix" },
            plural(backup.songs.size, "downloaded song") + " (" + plural(backup.albums, "album") + ")",
            plural(backup.playlists.size, "downloaded playlist"),
        )
        overlays.confirm(
            "Restore from ${backup.device.ifEmpty { "another phone" }}?",
            "Made $made: ${parts.joinToString(", ")}. This phone's settings and EQ are replaced; " +
                "the downloads start now" + (if (g.settings.downloadsWifiOnly.value) " (on Wi-Fi)." else "."),
            "Restore",
            destructive = false,
        ) {
            backup.restore(g.settings, g.eq, g.playback, g.downloads)
            overlays.hud("Restored")
        }
    }
}
