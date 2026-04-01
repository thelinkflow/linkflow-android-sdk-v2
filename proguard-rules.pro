# Keep LinkFlow SDK classes
-keep class com.linkflow.sdk.** { *; }
-keepclassmembers class com.linkflow.sdk.** { *; }

# Keep Install Referrer
-keep class com.android.installreferrer.** { *; }

# Keep Google Play Services
-keep class com.google.android.gms.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# JSON
-keepclassmembers class * {
    @org.json.** *;
}

# Prevent stripping of native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
