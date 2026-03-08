cmake_minimum_required(VERSION 3.22.1)

project("llama-rpc" VERSION 1.0.0 LANGUAGES C CXX)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp)

# llama.cpp options
set(GGML_RPC ON CACHE BOOL "ggml: RPC backend" FORCE)
set(LLAMA_BUILD_RPC ON CACHE BOOL "llama: build RPC" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(llama-rpc SHARED
        native-lib.cpp)

target_include_directories(llama-rpc PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(llama-rpc
        llama
        ggml
        ggml-cpu
        android
        log)
