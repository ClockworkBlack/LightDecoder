# The native library resolves these methods by name through JNI, so R8 must not
# rename or remove the class or its native methods.
-keep class com.lightdecoder.heif.NativeDecoder {
    native <methods>;
    *;
}
