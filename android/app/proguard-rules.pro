# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ── SmartSpend release rules ──────────────────────────────────────────────────
# Gson fills API models by matching JSON keys to Kotlin field names via reflection, and
# creates them without any constructor call the code can see. Every type Retrofit/Gson reads
# or writes lives in the root package (BackendService.kt, ApiModels.kt). Without these rules
# R8 decides those classes are never created and strips their fields — the release app then
# gets empty data. Class names may still be obfuscated; field names may not.
-keep,allowobfuscation class com.smartspend.app.* {
    <init>(...);
}
-keepclassmembers class com.smartspend.app.* {
    <fields>;
}
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.JsonDeserializer

# Readable crash stack traces once the mapping file is uploaded to Play Console.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
