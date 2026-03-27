# ============================================================
# Podcasto — ProGuard / R8 Rules
# ============================================================

# === Kotlin Serialization ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ghostwan.podcasto.**$$serializer { *; }
-keepclassmembers class com.ghostwan.podcasto.** {
    *** Companion;
}
-keepclasseswithmembers class com.ghostwan.podcasto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# === Retrofit ===
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# === OkHttp ===
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# === Gson ===
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# === Room ===
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# === Hilt ===
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# === Ktor ===
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep class io.ktor.server.** { *; }
-keep class io.ktor.serialization.** { *; }

# === Google Generative AI (Gemini) ===
-dontwarn com.google.ai.client.generativeai.**
-keep class com.google.ai.client.generativeai.** { *; }

# === Coil ===
-dontwarn coil.**
-keep class coil.** { *; }

# === Media3 / ExoPlayer ===
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# === WorkManager ===
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# === Data classes used in API responses ===
-keep class com.ghostwan.podcasto.data.remote.** { *; }
-keep class com.ghostwan.podcasto.web.** { *; }
-keep class com.ghostwan.podcasto.ui.screens.AiSuggestion { *; }
-keep class com.ghostwan.podcasto.ui.screens.AiDiscoverResponse { *; }

# === Keep enum classes ===
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# === General Android ===
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends android.app.Activity

# === Suppress warnings ===
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**

# === JSch (SSH tunnel) ===
-dontwarn com.jcraft.jsch.**
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.security.jgss.**

# === Google Sign-In / Drive API ===
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.http.client.** { *; }
-dontwarn com.google.api.client.googleapis.extensions.android.**
-dontwarn com.google.api.client.http.**
-dontwarn org.apache.http.**

# === Backup data classes ===
-keep class com.ghostwan.podcasto.data.repository.Backup* { *; }
-keep class com.ghostwan.podcasto.data.backup.** { *; }

# === Hilt WorkManager ===
-keep class * extends androidx.work.ListenableWorker
-keep class androidx.hilt.work.** { *; }
