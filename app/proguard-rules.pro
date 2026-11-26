# Wjust macro (com.willjustplay.wjustmacro) R8 / ProGuard rules for Play Store release builds

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- Kotlinx Serialization (polymorphic Action models, Macro, MacroSet, AppSettings) ----
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

# ---- Google Play Billing Library ----
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.api.** { *; }

# ---- LuaJ (uses reflection for standard libraries) ----
-keep class org.luaj.vm2.** { *; }
-dontwarn org.luaj.vm2.**
-dontwarn javax.script.**
-dontwarn java.awt.**

# ---- ML Kit Text Recognition ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
