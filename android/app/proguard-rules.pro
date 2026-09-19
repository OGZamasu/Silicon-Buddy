# kotlinx.serialization keeps its generated serializers on the class itself.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.siliconoptimizer.buddy.** {
    *** Companion;
}
-keepclasseswithmembers class dev.siliconoptimizer.buddy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
