# kotlinx.serialization keeps its generated serializers via its own consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.stemify.mobile.**$$serializer { *; }
-keepclassmembers class com.stemify.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.stemify.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
