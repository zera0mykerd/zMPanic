-dontobfuscate

-keepattributes Signature, InnerClasses, EnclosingMethod

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- CORE APP ---
-keep class com.mykerd.panic.** { *; }

# --- COMPOSE ---
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.material3.** { *; }
-dontwarn androidx.compose.runtime.**

# --- ANDROID HARDWARE & SYSTEM ---
-keep class android.hardware.Camera { *; }
-keep class android.location.LocationManager { *; }
-keep class android.media.MediaRecorder { *; }

# --- CLEAN LOG ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# --- DEBUG & ERROR REPORTING ---
-keepattributes SourceFile, LineNumberTable
-dontnote **

# --- MISSING CLASSES DETECTED BY R8 ---
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
