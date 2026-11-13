-keep class org.torproject.jni.TorService {
    *;
}
-keep class com.uacspoofer.mobile.engine.tor.HevSocks5Tunnel {
    *;
}

# -repackageclasses 'a.b.c'
-allowaccessmodification
-overloadaggressively

# Preserve native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Android Services and VpnService
-keep public class * extends android.app.Service
-keep public class * extends android.net.VpnService

# Keep Go / native core / tun2socks / v2ray packages if used
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class tun2socks.** { *; }
-keep class androidx.core.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
-dontwarn tun2socks.**

# Keep entire internal application classes, methods, and fields
-keep class com.uacspoofer.mobile.** { *; }
-keepclassmembers class com.uacspoofer.mobile.** { *; }

# Preserve annotations and type signatures for serialization/reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep JSON serialization models (Gson, Moshi, Kotlinx)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Parcelable and Serializable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
