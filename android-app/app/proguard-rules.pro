# OpenBot Mobile - keep JNI bridge names
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.openbot.mobile.OpenBotCore { *; }
