cmake_minimum_required(VERSION 3.22.1)
project(localvoice LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)

# Static-link llama/ggml into one JNI library so Android never loads mismatched libggml/libomp .so files.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Disable host CPU probing; otherwise CMake ignores GGML_CPU_ARM_ARCH.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
# Exynos 9610 (Cortex-A73/A53) supports baseline ARMv8.0-A NEON only; dotprod/i8mm cause SIGILL.
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# Pthreads avoid bundling NDK libomp.so.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# CPU-only inference; Mali-G72 MP3 has no usable compute backend.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
# JNI wrapper calls include/llama.h directly, skipping llama-common/cpp-httplib overhead.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Fully offline app: no libcurl, OpenSSL, or Rust llguidance toolchain.
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama_build EXCLUDE_FROM_ALL)

# Phase 2 supplies llama_jni.cpp before the first native build.
add_library(llama_jni SHARED llama_jni.cpp)

target_compile_options(llama_jni PRIVATE
    -march=armv8-a
    -O3
    -mtune=cortex-a73
    -fvisibility=hidden
)

foreach(GGML_CPU_TARGET ggml-cpu ggml-cpu-armv8-a)
    if(TARGET ${GGML_CPU_TARGET})
        target_compile_options(${GGML_CPU_TARGET} PRIVATE
            -march=armv8-a
            -O3
            -mtune=cortex-a73
        )
    endif()
endforeach()

target_include_directories(llama_jni PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
)

target_link_libraries(llama_jni PRIVATE
    llama
    log
)