# kotlinx.serialization keeps its generated serializers via its own consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.stemroller.mobile.**$$serializer { *; }
-keepclassmembers class com.stemroller.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.stemroller.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
