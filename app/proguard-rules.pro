# The native library resolves these methods by name through JNI, so R8 must not
# rename or remove the class or its native methods.
-keep class com.lightdecoder.heif.NativeDecoder {
    native <methods>;
    *;
}

# Jakarta Mail (the Gmail tab) finds its protocol providers and data handlers
# by class name, from META-INF/javamail.* and mailcap.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**

# ONNX Runtime (the sticker maker's smart cut-out): its native library finds these
# classes, their fields and their constructors by name.
-keep class ai.onnxruntime.** { *; }

# iMessage (uniffi bindings over JNA): JNA binds the native functions to these classes'
# methods and reads Structure fields and Callback interfaces by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.rust_bridge.** { *; }
-dontwarn java.awt.**
