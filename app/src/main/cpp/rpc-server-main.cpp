#include <string>
#include <vector>
#include <unistd.h>
#include <android/log.h>
#include <thread>
#include <stdio.h>
#include <sys/types.h>
#include <exception>
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-rpc.h"

#define TAG "LLAMA_RPC_SERVER"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string g_ggml_log_buffer;
static int pfd[2] = {-1, -1};

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
        __android_log_print(android_level, "GGML", "%s", line.c_str());
    }
}

int main(int argc, char * argv[]) {
    // Expected arguments: <host> <port> <n_threads> <cache_dir>
    if (argc != 4) {
        LOG_ERROR("Invalid arguments. Expected: host port threads [cache_dir]");
        return 1;
    }

    std::string host = argv[1];
    int port = 0;
    int n_threads = 0;
    try {
        port = std::stoi(argv[2]);
        n_threads = std::stoi(argv[3]);
    } catch (const std::exception & e) {
        LOG_ERROR("Failed to parse port/threads: %s", e.what());
        return 1;
    }
    const char * cache_dir = argc >= 5 ? argv[4] : nullptr;

    std::string endpoint = host + ":" + std::to_string(port);

    LOG_INFO("Starting RPC server on %s with %d threads, cache: %s", endpoint.c_str(), n_threads, cache_dir ? cache_dir : "null");

    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IONBF, 0);
    if (pipe(pfd) != 0 || dup2(pfd[1], 1) < 0 || dup2(pfd[1], 2) < 0) {
        LOG_ERROR("Failed to initialize stdout/stderr redirection");
        return 1;
    }

    std::thread logger([]() {
        ssize_t readSize;
        char buf[1024];
        while ((readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
            if(buf[readSize-1] == '\n') --readSize;
            buf[readSize] = 0;
            LOG_INFO("GGML_OUT: %s", buf);
        }
    });
    logger.detach();

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
        LOG_ERROR("No CPU device found!");
        return 1;
    }

    ggml_backend_reg_t reg = ggml_backend_reg_by_name("RPC");
    if (!reg) {
        LOG_ERROR("Failed to find RPC backend");
        return 1;
    }

    auto start_server_fn = (void(*)(const char*, const char*, size_t, size_t, ggml_backend_dev_t*)) 
                           ggml_backend_reg_get_proc_address(reg, "ggml_backend_rpc_start_server");
    
    if (!start_server_fn) {
        LOG_ERROR("Failed to obtain RPC backend start server function");
        return 1;
    }

    LOG_INFO("Invoking start_server_fn...");
    start_server_fn(endpoint.c_str(), cache_dir, (size_t)n_threads, devices.size(), devices.data());
    
    LOG_INFO("RPC server stopped cleanly");
    return 0;
}
