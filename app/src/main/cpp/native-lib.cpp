#include <jni.h>
#include "ggml.h"
#include "ggml-rpc.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_llama_rpcapp_NativeRpcServer_getMaxSize(JNIEnv* env, jobject /* this */) {
    (void) env;
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
