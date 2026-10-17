#include <jni.h>

const char* awgJstrChars(JNIEnv* env, jstring s) {
    return (*env)->GetStringUTFChars(env, s, NULL);
}

void awgJstrRelease(JNIEnv* env, jstring s, const char* c) {
    (*env)->ReleaseStringUTFChars(env, s, c);
}

jstring awgNewString(JNIEnv* env, const char* s) {
    return (*env)->NewStringUTF(env, s);
}
