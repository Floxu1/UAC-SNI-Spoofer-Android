#include <jni.h>

#include <mutex>

extern "C" {
int direct_start(const char* config);
int direct_stop();
int direct_is_running();
int direct_is_ready();
const char* direct_last_error();
const char* direct_last_log();
void direct_set_socket_protector(int (*protector)(int));
}

namespace {
JavaVM* vm = nullptr;
jobject vpn_service = nullptr;
jmethodID protect_method = nullptr;
std::mutex vpn_mutex;

int protect_socket(int fd) {
    JNIEnv* env = nullptr;
    const bool needs_attach = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (needs_attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
    if (env == nullptr) return 0;

    bool allowed;
    {
        std::lock_guard<std::mutex> lock(vpn_mutex);
        allowed = vpn_service != nullptr && env->CallBooleanMethod(vpn_service, protect_method, fd);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            allowed = false;
        }
    }
    if (needs_attach) vm->DetachCurrentThread();
    return allowed ? 1 : 0;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* java_vm, void*) {
    vm = java_vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeAttach(JNIEnv* env, jclass, jobject service) {
    const jclass service_class = env->GetObjectClass(service);
    if (service_class == nullptr) return;
    const jmethodID method = env->GetMethodID(service_class, "protectSocket", "(I)Z");
    env->DeleteLocalRef(service_class);
    if (method == nullptr) return;

    const jobject service_ref = env->NewGlobalRef(service);
    if (service_ref == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(vpn_mutex);
        if (vpn_service != nullptr) env->DeleteGlobalRef(vpn_service);
        vpn_service = service_ref;
        protect_method = method;
    }
    direct_set_socket_protector(protect_socket);
}

extern "C" JNIEXPORT void JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeDetach(JNIEnv* env, jclass) {
    direct_set_socket_protector(nullptr);
    std::lock_guard<std::mutex> lock(vpn_mutex);
    if (vpn_service != nullptr) env->DeleteGlobalRef(vpn_service);
    vpn_service = nullptr;
    protect_method = nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeStart(JNIEnv* env, jclass, jstring config) {
    if (config == nullptr) return -1;
    const char* text = env->GetStringUTFChars(config, nullptr);
    if (text == nullptr) return -1;
    const jint result = direct_start(text);
    env->ReleaseStringUTFChars(config, text);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeStop(JNIEnv*, jclass) {
    return direct_stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeIsRunning(JNIEnv*, jclass) {
    return direct_is_running() != 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeIsReady(JNIEnv*, jclass) {
    return direct_is_ready() != 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(direct_last_error());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_uacspoofer_mobile_engine_direct_DirectNative_nativeLastLog(JNIEnv* env, jclass) {
    return env->NewStringUTF(direct_last_log());
}
