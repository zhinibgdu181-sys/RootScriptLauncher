# 保留 MainActivity 和所有 UI 控件，防止混淆后报错找不到
-keep class com.example.rootlauncher.MainActivity { *; }
-keep class com.example.rootlauncher.R$* { *; }

# 保留 ProcessBuilder、Runtime、su 相关底层调用
-keepclassmembers class * {
    native <methods>;
}
-keep class java.lang.ProcessBuilder { *; }
-keep class java.lang.Runtime { *; }

# 保留基本的反射、泛型和注解
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
