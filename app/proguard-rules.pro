# 1. 保留 MainActivity 防止找不到入口
-keep class com.example.rootlauncher.MainActivity { *; }

# 2. 保留 Android 系统组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# 3. 保留 R 文件
-keep class com.example.rootlauncher.R$* { *; }

# 4. 保留底层 ProcessBuilder 和 Runtime，防止 su 执行断裂
-keep class java.lang.ProcessBuilder { *; }
-keep class java.lang.Runtime { *; }

# 5. 保留 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 6. 保留泛型和注解
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 7. 防止混淆报错
-dontwarn com.example.rootlauncher.**
