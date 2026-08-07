# 反射调用系统隐藏 API，保留相关类
-keep class android.bluetooth.** { *; }
-keep class android.net.wifi.** { *; }
-keep class com.carboot.assistant.** { *; }
-dontwarn android.**
