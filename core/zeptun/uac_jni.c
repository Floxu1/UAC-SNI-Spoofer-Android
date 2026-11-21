/* Adapted from Zeptun src/jni/zeptun_jni.c (MIT, see LICENSE). */
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "zeptun.h"

/* Added by android-readiness.patch. */
extern bool zeptun_is_ready(Zeptun *tun);

typedef struct {
    Zeptun *tun;
    JavaVM *vm;
    jobject service;
    jmethodID protect;
    pthread_t thread;
    atomic_int exit_code;
} Context;

static bool protect_socket(void *opaque, int fd) {
    Context *ctx = opaque;
    JNIEnv *env = NULL;
    bool attached = (*ctx->vm)->GetEnv(ctx->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && (*ctx->vm)->AttachCurrentThread(ctx->vm, &env, NULL) != JNI_OK) return false;
    if (env == NULL) return false;
    jboolean ok = (*env)->CallBooleanMethod(env, ctx->service, ctx->protect, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    if (attached) (*ctx->vm)->DetachCurrentThread(ctx->vm);
    return ok == JNI_TRUE;
}

static void *run_engine(void *opaque) {
    Context *ctx = opaque;
    int rc = zeptun_run(ctx->tun);
    atomic_store(&ctx->exit_code, rc == ZEPTUN_OK ? ZEPTUN_ERR_CLOSED : rc);
    return NULL;
}

static void dispose(JNIEnv *env, Context *ctx) {
    if (ctx->tun != NULL) zeptun_destroy(ctx->tun);
    if (ctx->service != NULL) (*env)->DeleteGlobalRef(env, ctx->service);
    free(ctx);
}

static jlong start(JNIEnv *env, jobject self, jobject service, jint fd, jstring config) {
    (void)self;
    if (fd < 0 || fcntl(fd, F_GETFD) < 0 || service == NULL || config == NULL)
        return ZEPTUN_ERR_INVALID_ARGUMENT;
    Context *ctx = calloc(1, sizeof *ctx);
    if (ctx == NULL) return ZEPTUN_ERR_OUT_OF_MEMORY;
    atomic_init(&ctx->exit_code, 0);
    int rc = ZEPTUN_ERR_INVALID_ARGUMENT;
    if ((*env)->GetJavaVM(env, &ctx->vm) != JNI_OK) goto failed;
    jclass cls = (*env)->GetObjectClass(env, service);
    if (cls == NULL) goto failed;
    ctx->protect = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (ctx->protect == NULL) goto failed;
    ctx->service = (*env)->NewGlobalRef(env, service);
    if (ctx->service == NULL) goto failed;
    const char *json = (*env)->GetStringUTFChars(env, config, NULL);
    if (json == NULL) goto failed;
    rc = zeptun_create_from_json(json, strlen(json), &ctx->tun);
    (*env)->ReleaseStringUTFChars(env, config, json);
    if (rc != ZEPTUN_OK) goto failed;
    rc = zeptun_set_protect_callback(ctx->tun, protect_socket, ctx);
    if (rc != ZEPTUN_OK) goto failed;
    if (pthread_create(&ctx->thread, NULL, run_engine, ctx) == 0)
        return (jlong)(uintptr_t)ctx;
    rc = ZEPTUN_ERR_OUT_OF_MEMORY;
failed:
    dispose(env, ctx);
    return rc;
}

static jint status(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    Context *ctx = (Context *)(uintptr_t)handle;
    int rc = atomic_load(&ctx->exit_code);
    if (rc != 0) return rc;
    return zeptun_is_ready(ctx->tun) ? 1 : 0;
}

static jlongArray stats(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Context *ctx = (Context *)(uintptr_t)handle;
    ZeptunStats snapshot;
    if (zeptun_stats(ctx->tun, &snapshot) != ZEPTUN_OK) return NULL;
    const jlong counters[] = {
        (jlong)snapshot.rx_packets, (jlong)snapshot.rx_bytes,
        (jlong)snapshot.tx_packets, (jlong)snapshot.tx_bytes,
    };
    jlongArray array = (*env)->NewLongArray(env, 4);
    if (array != NULL) (*env)->SetLongArrayRegion(env, array, 0, 4, counters);
    return array;
}

static void stop(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Context *ctx = (Context *)(uintptr_t)handle;
    /* Stop may arrive before the new thread has entered zeptun_run. */
    const struct timespec pause = { .tv_nsec = 1000000 };
    while (atomic_load(&ctx->exit_code) == 0 &&
           zeptun_stop(ctx->tun) == ZEPTUN_ERR_NOT_RUNNING) nanosleep(&pause, NULL);
    pthread_join(ctx->thread, NULL);
    dispose(env, ctx);
}

static const JNINativeMethod methods[] = {
    { "start", "(Landroid/net/VpnService;ILjava/lang/String;)J", (void *)start },
    { "status", "(J)I", (void *)status },
    { "stats", "(J)[J", (void *)stats },
    { "stop", "(J)V", (void *)stop },
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/uacspoofer/mobile/vpn/ZeptunNative");
    if (cls == NULL || (*env)->RegisterNatives(env, cls, methods,
        sizeof methods / sizeof methods[0]) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
