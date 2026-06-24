# kotlinx.serialization keeps generated serializers; the rules ship with the lib.
# Add app-specific keep rules here as the app grows.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
