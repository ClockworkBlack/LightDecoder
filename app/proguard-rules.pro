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
