# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Disable obfuscation to keep symbol names readable
-dontobfuscate

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Keep all classes in our package unobfuscated
-keep class com.mobilenext.devicekit.** { *; }

# Keep tracing and test runner classes
-keep class androidx.tracing.Trace { *; }
-keep class androidx.test.** { *; }

# Keep Kotlin stdlib needed by the test runner
-keep class kotlin.** { *; }

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}