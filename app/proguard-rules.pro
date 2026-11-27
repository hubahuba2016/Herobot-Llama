# Keep data/model classes and Firebase / Google Play Services reflection rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep App Check providers, Firebase AI Logic, and Firestore model classes
-keep class com.herobot.model.** { *; }
-keep class com.herobot.data.remote.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn org.slf4j.**
