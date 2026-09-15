# kotlinx.serialization : conserver les sérialiseurs générés
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lixirian.notesdefrais.**$$serializer { *; }
-keepclassmembers class com.lixirian.notesdefrais.** { *** Companion; }
-keepclasseswithmembers class com.lixirian.notesdefrais.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
