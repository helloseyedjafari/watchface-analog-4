# The native library throws PackException by class name (JNI FindClass) and binds PackNative's native methods by name.
-keep class com.seyedjafari.analogplus.packjni.PackException { *; }
-keepclasseswithmembernames class * { native <methods>; }
