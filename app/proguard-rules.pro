# Keep Kotlin
-keep class kotlin.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.** 

# Keep model classes
-keep class com.tkjone.app.model.** { *; }
-keep class com.tkjone.app.service.** { *; }
-keep class com.tkjone.app.ui.** { *; }
-keep class com.tkjone.app.TKJOneApp { *; }
-keep class com.tkjone.app.MainActivity { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes *Annotation*, InnerClasses
-keepattributes SourceFile, LineNumberTable
