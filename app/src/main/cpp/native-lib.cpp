#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_maya_ai_MainActivity_getNativeMessage(
    JNIEnv* env,
    jobject
) {
    return env->NewStringUTF("MAYA Native Engine Ready");
}
