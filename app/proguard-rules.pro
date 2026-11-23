# kotlinx.serialization: احتفظ بالمسلسلات المولّدة لكيانات النسخة الاحتياطية
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.rasidi.core.** { *** Companion; }
-keepclasseswithmembers class app.rasidi.core.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.rasidi.core.**$$serializer { *; }
-dontwarn org.slf4j.**
