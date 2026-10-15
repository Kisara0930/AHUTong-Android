-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions
# Only the tiny Java/Android ABI is retained for separately signed components.
-keep class com.ahu.ahutong.hostapi.** { public protected *; }
-keep class com.ahu.ahutong.bootstrap.NativeBootstrap { public static *; }
# Isolate private APK names from independently minified shared-runtime class names.
-repackageclasses com.ahu.ahutong.bootstrap.internal
