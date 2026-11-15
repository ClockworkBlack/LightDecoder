package com.lightdecoder.heif;

// Host-only mirror of the app's native-method declarations, used to exercise
// the real JNI entry points in liblightdecoder.so from a plain JVM. Only the
// methods that do not touch android.graphics.Bitmap are declared here.
public final class NativeDecoder {
    static { System.loadLibrary("lightdecoder"); }

    public static native String nativeVersions();
    public static native long nativeOpen(byte[] data);
    public static native void nativeClose(long handle);
    public static native String nativeDescribe(long handle);
    public static native String nativeLastError(long handle);
    public static native int nativeImageCount(long handle);
    public static native boolean nativeHasSequence(long handle);
    public static native boolean nativePrepareStill(long handle, int index);
    public static native int nativePrepareNextFrame(long handle);
    public static native boolean nativeRewind(long handle);
    public static native int nativePreparedWidth(long handle);
    public static native int nativePreparedHeight(long handle);
    public static native boolean nativePreparedHasAlpha(long handle);
    public static native int nativePreparedDurationMs(long handle);
    public static native byte[] nativeCopyRGBA(long handle);
}
