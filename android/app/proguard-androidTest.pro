# Rules for the instrumented-test APK only. None of this reaches the app.
#
# `testBuildType = "release"` is deliberate — the point of these tests is to run against
# the APK R8 actually produced, since a debug build keeps everything and would prove
# nothing. The cost is that the *test* APK goes through R8 as well, and the test
# infrastructure is all reached reflectively: the runner is named in a manifest, the test
# classes and methods are found by annotation, and `androidx.tracing` is loaded by name
# from inside the runner's `onCreate`. Stripped, the whole process dies before a single
# test runs — which is what happened.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class * extends android.app.Instrumentation { *; }
-keep class dev.siliconoptimizer.buddy.MinifiedBuildTest { *; }
-keep class dev.siliconoptimizer.buddy.AgentsScreenTest { *; }
-keep class dev.siliconoptimizer.buddy.AgentsScreenTest$* { *; }
-keep class dev.siliconoptimizer.buddy.FakeMac { *; }
-keep class dev.siliconoptimizer.buddy.OnDeviceModelTest { *; }
-keep class dev.siliconoptimizer.buddy.OnDeviceModelTest$* { *; }
-keep class dev.siliconoptimizer.buddy.StandInMac { *; }
-keepclasseswithmembers class * { @org.junit.Test <methods>; }

# Compile-only annotations the test libraries reference and that never exist at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.common.**
