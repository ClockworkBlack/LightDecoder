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

# Guava (through the emoji picker) is annotated with jsr305, checker-qual and j2objc annotations,
# which are left out of the app (annotations only; nothing reads them at run time).
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**

# iMessage (uniffi bindings over JNA): JNA binds the native functions to these classes'
# methods and reads Structure fields and Callback interfaces by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.rust_bridge.** { *; }
-dontwarn java.awt.**

# FaceTime: librust_bridge.so binds its JNI functions to this class by name
# (Java_com_localob_messaging_app_facetime_FaceTimeInCallService_*), calls its callback methods by
# name and writes its isVideo field. Renamed, or with a callback removed (none has a Kotlin caller)
# or the field folded away (nothing in Kotlin writes it), calls would fail in release builds only.
-keep class com.localob.messaging.app.facetime.FaceTimeInCallService {
    native <methods>;
    *;
}
