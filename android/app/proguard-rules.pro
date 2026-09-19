# What has to survive R8.
#
# Everything here is reached by name rather than by a call: a serializer the compiler
# plugin generated, or a class whose name Glance writes into a RemoteViews action and
# looks up again later, in another process. R8 cannot see either kind of edge, and the
# failure mode is not a build error — it is a widget button that quietly does nothing on
# a release build, which is the worst way to find out.

# --- kotlinx.serialization ---------------------------------------------------
#
# The wire types in `transport/` are reached only through the serializers the plugin
# generates onto the classes themselves.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.siliconoptimizer.buddy.** {
    *** Companion;
}
-keepclasseswithmembers class dev.siliconoptimizer.buddy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class dev.siliconoptimizer.buddy.** { *; }
-keep,includedescriptorclasses class dev.siliconoptimizer.buddy.**$$serializer { *; }

# --- Glance app widget -------------------------------------------------------
#
# `actionRunCallback<AskQuickPromptAction>()` never calls the class; it writes the class
# *name* into the action, and Glance instantiates it by name when the button is tapped.
# Renamed or stripped, the widget's one button becomes a no-op and nothing says so.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-dontwarn androidx.glance.**

# --- Everything else ---------------------------------------------------------

# EncryptedSharedPreferences reaches Tink's key types by name; losing them means the
# token cannot be read back and the app silently asks to pair again.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ML Kit loads its barcode detector through a registrar it names at runtime.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# CameraX picks its implementation from a class name in the manifest metadata.
-keep class androidx.camera.camera2.Camera2Config { *; }


# --- so the app can be instrumented ------------------------------------------
#
# `androidx.tracing` is on the app's classpath but unused, so R8 removes it. The
# instrumented tests run *in the app's process*, and `AndroidJUnitRunner.onCreate` calls
# `androidx.tracing.Trace` by name — and because the test APK is shrunk against the app's
# already-shrunk output, a class the app dropped is not duplicated back into it. The
# result is neither APK having it and the process dying before the first test runs.
#
# A few kilobytes to keep the release build testable on a device, which is where the keep
# rules above are actually worth proving.
-keep class androidx.tracing.** { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.jvm.internal.** { *; }
