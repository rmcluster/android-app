#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <thread>
#include <android/log.h>
#include <atomic>
#include "ggml.h"
#include "ggml-rpc.h"

#define TAG "LLAMA_RPC_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_run_server{false};
static std::atomic<int> g_current_token{0};

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
        jint n_threads,
        jstring cacheDir) {
    
    int my_token = ++g_current_token;
    g_run_server.store(false);
    ggml_backend_rpc_stop_server();
    
    // Wait for previous threads to notice shutdown
    usleep(300000);
    g_run_server.store(true);

    const char *h_str = host ? env->GetStringUTFChars(host, 0) : "0.0.0.0";
    std::string endpoint = std::string(h_str) + ":" + std::to_string(port);
    if (host) env->ReleaseStringUTFChars(host, h_str);

    const char *c_str = cacheDir ? env->GetStringUTFChars(cacheDir, 0) : nullptr;
    std::string cache_dir_str = c_str ? c_str : "";
    if (cacheDir && c_str) env->ReleaseStringUTFChars(cacheDir, c_str);
    const char *native_cache_dir = cache_dir_str.empty() ? nullptr : cache_dir_str.c_str();

    LOGI("JNI startServer (Token %d): endpoint=%s, cache=%s, threads=%d", 
          my_token, endpoint.c_str(), native_cache_dir ? native_cache_dir : "null", n_threads);

    ggml_log_set(ggml_log_callback_android, nullptr);
    ggml_backend_load_all();

    std::vector<ggml_backend_dev_t> devices;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
            devices.push_back(dev);
        }
    }
    
    if (devices.empty()) {
        LOGE("JNI: No CPU device found!");
        return;
    }

    // Redirect stdout and stderr to logcat
    static int pfd[2];
    static bool pipe_initialized = false;
    if (!pipe_initialized) {
        setvbuf(stdout, 0, _IOLBF, 0);
        setvbuf(stderr, 0, _IONBF, 0);
        pipe(pfd);
        dup2(pfd[1], 1);
        dup2(pfd[1], 2);
        
        std::thread logger([]() {
            ssize_t readSize;
            char buf[1024];
            while ((readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
                if(buf[readSize-1] == '\n') --readSize;
                buf[readSize] = 0;
                LOGI("GGML_OUT: %s", buf);
            }
        });
        logger.detach();
        pipe_initialized = true;
    }
    
    while (g_run_server.load() && g_current_token.load() == my_token) {
        LOGI("JNI: calling ggml_backend_rpc_start_server (Token %d) on %s...", my_token, endpoint.c_str());
        ggml_backend_rpc_start_server(endpoint.c_str(), native_cache_dir, (size_t)n_threads, devices.size(), devices.data());
        
        LOGE("JNI: start_server returned (Token %d, active %d, run %d)", 
             my_token, g_current_token.load(), g_run_server.load());
        
        if (!g_run_server.load() || g_current_token.load() != my_token) break;
        
        LOGE("JNI: Waiting 2s before retry...");
        for (int i = 0; i < 20; ++i) {
            if (!g_run_server.load() || g_current_token.load() != my_token) break;
            usleep(100000);
        }
    }
    LOGI("JNI: Thread (Token %d) exiting cleanly", my_token);
}

extern "C" JNIEXPORT void JNICALL
Java_com_llama_rpcapp_NativeRpcServer_stopServer(JNIEnv* env, jobject /* this */) {
    g_run_server.store(false);
    ggml_backend_rpc_stop_server();
    LOGI("JNI: stopServer requested");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llama_rpcapp_NativeRpcServer_getMaxSize(JNIEnv* env, jobject /* this */) {
    ggml_backend_load_all();
    size_t dev_count = ggml_backend_dev_count();
    size_t max_size = 0;
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        ggml_backend_t backend = ggml_backend_dev_init(dev, nullptr);
        if (backend) {
            size_t size = ggml_backend_get_max_size(backend);
            if (size > max_size) max_size = size;
            ggml_backend_free(backend);
        }
    }
    return (jlong)max_size;
}
