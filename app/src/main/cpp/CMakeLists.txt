cmake_minimum_required(VERSION 3.22.1)

project("llama-rpc" VERSION 1.0.0 LANGUAGES C CXX)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp-rpc)
set(ANDROID_COMPAT_DIR ${CMAKE_CURRENT_LIST_DIR}/android_compat)

# llama.cpp options
set(GGML_RPC ON CACHE BOOL "ggml: RPC backend" FORCE)
set(LLAMA_BUILD_RPC ON CACHE BOOL "llama: build RPC" FORCE)

if(ANDROID)
    # OpenMP runtime (libomp) needs Bionic symbols missing on API 16.
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    # API 16 Bionic has no FORTIFY *_chk symbols; NDK adds -D_FORTIFY_SOURCE=2 by default.
    add_compile_options(-U_FORTIFY_SOURCE)
    add_compile_definitions(_FORTIFY_SOURCE=0)

    set(_ANDROID_COMPAT_HEADER "${ANDROID_COMPAT_DIR}/getline_compat.h")
    add_compile_options(
            "$<$<COMPILE_LANGUAGE:C>:-include${_ANDROID_COMPAT_HEADER}>"
            "$<$<COMPILE_LANGUAGE:CXX>:-include${_ANDROID_COMPAT_HEADER}>")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(android_compat STATIC
        ${ANDROID_COMPAT_DIR}/getline_compat.c
        ${ANDROID_COMPAT_DIR}/bionic_math_compat.c)

set(_ANDROID_COMPAT_WRAP_FLAGS -Wl,--wrap=_Exit)
target_include_directories(android_compat PUBLIC ${ANDROID_COMPAT_DIR})

# Bionic on API 16 lacks several libm symbols; embed shims into each packaged .so.
if(ANDROID)
    foreach(_llama_target IN ITEMS ggml-base ggml ggml-cpu ggml-rpc llama)
        if(TARGET ${_llama_target})
            target_link_options(${_llama_target} PRIVATE ${_ANDROID_COMPAT_WRAP_FLAGS})
            target_link_libraries(${_llama_target} PRIVATE
                    -Wl,--whole-archive android_compat -Wl,--no-whole-archive
                    m)
        endif()
    endforeach()
endif()

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
target_link_options(llama-rpc PRIVATE ${_ANDROID_COMPAT_WRAP_FLAGS})
target_link_libraries(llama-rpc ${LLAMA_RPC_LINK_LIBS}
        -Wl,--whole-archive android_compat -Wl,--no-whole-archive)

add_executable(rpc-server rpc-server-main.cpp)

set_target_properties(rpc-server PROPERTIES
        OUTPUT_NAME "rpc-server"
        PREFIX "lib"
        SUFFIX ".so")

target_include_directories(rpc-server PRIVATE ${LLAMA_RPC_INCLUDE_DIRS})
target_link_options(rpc-server PRIVATE ${_ANDROID_COMPAT_WRAP_FLAGS})
target_link_libraries(rpc-server ${LLAMA_RPC_LINK_LIBS}
        -Wl,--whole-archive android_compat -Wl,--no-whole-archive)
