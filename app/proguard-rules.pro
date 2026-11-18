-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Room database & entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class app.nota.data.db.** { *; }

# Legacy API 26-27 fingerprint bridge
-keep class app.nota.legacyfingerprint.** { *; }

# Compose RichEditor
-keep class com.mohamedrejeb.richeditor.** { *; }

# Optional JVM/TLS provider warnings from OkHttp
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
