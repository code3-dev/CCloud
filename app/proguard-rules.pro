# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Obfuscate class names
-repackageclasses ''
-allowaccessmodification

# ExoPlayer specific rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin serialization
-keep class com.pira.ccloud.data.model.** { *; }

# OkHttp and networking
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn javax.annotation.**
-dontwarn okhttp3.**

# Kotlin specific
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Compose specific rules
-keep class androidx.compose.ui.** {*;}
-dontwarn androidx.compose.ui.**
-keep class androidx.compose.foundation.** {*;}
-dontwarn androidx.compose.foundation.**
-keep class androidx.compose.material.** {*;}
-dontwarn androidx.compose.material.**

# ViewModel and Lifecycle
-keep class androidx.lifecycle.** {*;}
-dontwarn androidx.lifecycle.**