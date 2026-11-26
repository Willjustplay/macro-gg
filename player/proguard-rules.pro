# Wjust Player (com.willjustplay.wjustplayer) R8 / ProGuard rules

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- Kotlinx Serialization (polymorphic Action models, Macro, MacroSet) ----
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.data.**$$serializer { *; }
-keepclassmembers class com.example.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.example.data.** { *; }

# ---- ML Kit Text Recognition ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ---- LuaJ transitive warning suppression from :core ----
-dontwarn org.luaj.**
-dontwarn javax.script.**
-dontwarn java.awt.**
