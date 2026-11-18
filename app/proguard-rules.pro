# Preserve line numbers and source file names for crash reporting
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*, EnclosingMethod

# Room & Persistence
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Start.io (StartApp) In-App SDK
-keep class com.startapp.** { *; }
-dontwarn com.startapp.**
-dontwarn android.webkit.JavascriptInterface
-dontwarn org.jetbrains.annotations.**
-dontwarn androidx.webkit.**
-dontwarn com.amazon.privacypass.**

