#include <jni.h>
JNIEXPORT jstring JNICALL Java_com_ahu_ahutong_bootstrap_NativeAbi_current(JNIEnv *env, jclass type) {
    (void)type;
#if defined(__aarch64__)
    return (*env)->NewStringUTF(env, "arm64-v8a");
#elif defined(__x86_64__)
    return (*env)->NewStringUTF(env, "x86_64");
#elif defined(__i386__)
    return (*env)->NewStringUTF(env, "x86");
#else
    return (*env)->NewStringUTF(env, "armeabi-v7a");
#endif
}
