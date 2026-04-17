cmake_minimum_required(VERSION 3.22.1)

project("llama-rpc" VERSION 1.0.0 LANGUAGES C CXX)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp)

# llama.cpp options
set(GGML_RPC ON CACHE BOOL "ggml: RPC backend" FORCE)
set(LLAMA_BUILD_RPC ON CACHE BOOL "llama: build RPC" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

set(LLAMA_RPC_INCLUDE_DIRS
        ${LLAMA_SRC}
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

set(LLAMA_RPC_LINK_LIBS
        llama
        ggml
        ggml-cpu
        android
        log)

add_library(llama-rpc SHARED native-lib.cpp)

target_include_directories(llama-rpc PRIVATE ${LLAMA_RPC_INCLUDE_DIRS})
target_link_libraries(llama-rpc ${LLAMA_RPC_LINK_LIBS})

add_executable(rpc-server rpc-server-main.cpp)

set_target_properties(rpc-server PROPERTIES
        OUTPUT_NAME "rpc-server"
        PREFIX "lib"
        SUFFIX ".so")

target_include_directories(rpc-server PRIVATE ${LLAMA_RPC_INCLUDE_DIRS})
target_link_libraries(rpc-server ${LLAMA_RPC_LINK_LIBS})
