# Keep kotlinx.serialization models used for MVV/DB DTOs and SBahnEvent
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.mvvtracker.app.data.** { *; }
-keep,includedescriptorclasses class com.mvvtracker.app.**$$serializer { *; }
-keepclassmembers class com.mvvtracker.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mvvtracker.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
