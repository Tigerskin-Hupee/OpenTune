# ============================================================
# OpenTune ProGuard / R8 rules
# ============================================================

# ── Kotlin ───────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin Serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.opentune.**$$serializer { *; }
-keepclassmembers class app.opentune.** {
    *** Companion;
}
-keepclasseswithmembers class app.opentune.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ── Hilt / Dagger ────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembernames class * {
    @dagger.* <fields>;
    @dagger.* <methods>;
    @javax.inject.* <fields>;
    @javax.inject.* <methods>;
}
-dontwarn dagger.**
-dontwarn dagger.hilt.**

# ── Room ─────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep class app.opentune.db.** { *; }
-dontwarn androidx.room.**

# ── AndroidX / Jetpack ───────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── Media3 / ExoPlayer ───────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

# ── OkHttp ───────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**

# ── Ktor ─────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Coil ─────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── App entities / models ─────────────────────────────────────
-keep class app.opentune.db.entities.** { *; }
-keep class app.opentune.innertube.** { *; }
-keep class app.opentune.playback.YtDlpManager { *; }
-keep class app.opentune.playback.YtDlpDownloadWorker { *; }
-keep class app.opentune.playback.YtDlpUpdateChecker { *; }

# ── Enum classes ──────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Reflection safety ─────────────────────────────────────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Suppress known-safe warnings ──────────────────────────────
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.invoke.StringConcatFactory
