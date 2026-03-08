#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <unistd.h>
#include "ggml.h"
#include "ggml-rpc.h"

#define TAG "LLAMA_RPC_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void ggml_log_callback_android(ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    int android_level = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: android_level = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  android_level = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  android_level = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: android_level = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_print(android_level, "GGML", "%s", text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_llama_rpcapp_NativeRpcServer_startServer(
        JNIEnv* env,
        jobject /* this */,
        jstring host,
        jint port,
        jint n_threads) {
    
    const char *native_host = env->GetStringUTFChars(host, 0);
    std::string endpoint = std::string(native_host) + ":" + std::to_string(port);
    
    ggml_log_set(ggml_log_callback_android, nullptr);
    ggml_backend_load_all();
    
    size_t reg_count = ggml_backend_reg_count();
    size_t dev_count = ggml_backend_dev_count();
    LOGI("Backends registered: %zu, Devices found: %zu", reg_count, dev_count);
    
    // Get available devices
    std::vector<ggml_backend_dev_t> devices;
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("Device %zu: %s (%s)", i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
        devices.push_back(dev);
    }
    
    if (devices.empty()) {
        LOGE("No devices found for RPC server");
        env->ReleaseStringUTFChars(host, native_host);
        return;
    }

    ggml_backend_reg_t reg = ggml_backend_reg_by_name("RPC");
    if (!reg) {
        LOGE("Failed to find RPC backend");
        env->ReleaseStringUTFChars(host, native_host);
        return;
    }

    auto start_server_fn = (void(*)(const char*, const char*, size_t, size_t, ggml_backend_dev_t*)) 
                           ggml_backend_reg_get_proc_address(reg, "ggml_backend_rpc_start_server");
    
    if (!start_server_fn) {
        LOGE("Failed to obtain RPC backend start server function");
        env->ReleaseStringUTFChars(host, native_host);
        return;
    }

    // restart if failed
    while (true) {
        LOGI("Invoking start_server_fn...");
        start_server_fn(endpoint.c_str(), nullptr, (size_t)n_threads, devices.size(), devices.data());
        LOGE("start_server_fn returned! Waiting 5 seconds before retry...");
        sleep(5);
    }
    
    env->ReleaseStringUTFChars(host, native_host);
    LOGI("RPC server stopped");
}
