#include <string>
#include <vector>
#include <unistd.h>
#include <android/log.h>
#include <stdio.h>
#include <sys/types.h>
#include <exception>
#include <cstdarg>
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-rpc.h"

#define TAG "LLAMA_RPC_SERVER"

static std::string g_ggml_log_buffer;

static void app_log_vprint(int android_level, FILE * stream, const char * tag, const char * fmt, va_list args) {
    va_list android_args;
    va_copy(android_args, args);
    __android_log_vprint(android_level, tag, fmt, android_args);
    va_end(android_args);

    fprintf(stream, "%s: ", tag);
    vfprintf(stream, fmt, args);
    fputc('\n', stream);
    fflush(stream);
}

static void app_log_print(int android_level, FILE * stream, const char * tag, const char * fmt, ...) {
    va_list args;
    va_start(args, fmt);
    app_log_vprint(android_level, stream, tag, fmt, args);
    va_end(args);
}

static void ggml_log_emit(int android_level, const std::string & line) {
    __android_log_print(android_level, "GGML", "%s", line.c_str());
    FILE * stream = android_level == ANDROID_LOG_ERROR ? stderr : stdout;
    fprintf(stream, "GGML: %s\n", line.c_str());
    fflush(stream);
}

static void ggml_log_callback_android(ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    if (!text) return;

    g_ggml_log_buffer += text;

    size_t pos;
    while ((pos = g_ggml_log_buffer.find('\n')) != std::string::npos) {
        std::string line = g_ggml_log_buffer.substr(0, pos);
        g_ggml_log_buffer.erase(0, pos + 1);

        int android_level = ANDROID_LOG_INFO;
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: android_level = ANDROID_LOG_ERROR; break;
            case GGML_LOG_LEVEL_WARN:  android_level = ANDROID_LOG_WARN;  break;
            case GGML_LOG_LEVEL_INFO:  android_level = ANDROID_LOG_INFO;  break;
            case GGML_LOG_LEVEL_DEBUG: android_level = ANDROID_LOG_DEBUG; break;
            default: break;
        }
        ggml_log_emit(android_level, line);
    }
}

int main(int argc, char * argv[]) {
    // Expected arguments: <host> <port> <n_threads> [cache_dir]
    if (argc < 4 ||  argc > 5) {
        app_log_print(ANDROID_LOG_ERROR, stderr, TAG, "Invalid arguments. Expected: host port threads [optionally cache_dir]");
        return 1;
    }

    std::string host = argv[1];
    int port = 0;
    int n_threads = 0;
    try {
        port = std::stoi(argv[2]);
        n_threads = std::stoi(argv[3]);
    } catch (const std::exception & e) {
        app_log_print(ANDROID_LOG_ERROR, stderr, TAG, "Failed to parse port/threads: %s", e.what());
        return 1;
    }
    const char * cache_dir = argc >= 5 ? argv[4] : nullptr;

    std::string endpoint = host + ":" + std::to_string(port);

    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IONBF, 0);
    app_log_print(ANDROID_LOG_INFO, stdout, TAG, "Starting RPC server on %s with %d threads, cache: %s", endpoint.c_str(), n_threads, cache_dir ? cache_dir : "null");

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
        app_log_print(ANDROID_LOG_ERROR, stderr, TAG, "No CPU device found!");
        return 1;
    }

    ggml_backend_reg_t reg = ggml_backend_reg_by_name("RPC");
    if (!reg) {
        app_log_print(ANDROID_LOG_ERROR, stderr, TAG, "Failed to find RPC backend");
        return 1;
    }

    auto start_server_fn = (void(*)(const char*, const char*, size_t, size_t, ggml_backend_dev_t*)) 
                           ggml_backend_reg_get_proc_address(reg, "ggml_backend_rpc_start_server");
    
    if (!start_server_fn) {
        app_log_print(ANDROID_LOG_ERROR, stderr, TAG, "Failed to obtain RPC backend start server function");
        return 1;
    }

    app_log_print(ANDROID_LOG_INFO, stdout, TAG, "Invoking start_server_fn...");
    start_server_fn(endpoint.c_str(), cache_dir, (size_t)n_threads, devices.size(), devices.data());
    
    app_log_print(ANDROID_LOG_INFO, stdout, TAG, "RPC server stopped cleanly");
    return 0;
}
