# Keep data/model classes and Firebase / Google Play Services reflection rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep App Check providers and Firebase AI Logic model classes
-keep class com.herobot.model.** { *; }
-dontwarn org.slf4j.**
