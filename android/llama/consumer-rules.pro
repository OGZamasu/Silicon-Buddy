# Kept by name, because native code finds them by name.
#
# JNI binds `external` methods to C functions whose names spell out the class and the
# method — Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeLoad — and the C side
# looks the sink's callback up with GetMethodID("onText", "([B)Z"). R8 sees neither edge.
# Renamed, the build still succeeds and the first answer on the phone is an
# UnsatisfiedLinkError or a NoSuchMethodError. The app's CI checks seeds.txt for these,
# and an instrumented test calls through them on the minified build.
-keep class dev.siliconoptimizer.buddy.llama.LlamaNative {
    native <methods>;
    *;
}
-keep interface dev.siliconoptimizer.buddy.llama.LlamaSink { *; }
-keepclassmembers class * implements dev.siliconoptimizer.buddy.llama.LlamaSink {
    public boolean onText(byte[]);
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
