# The phone's own model: llama.cpp b11053 and a thin JNI bridge over it.
#
# Everything llama.cpp builds here is a shared library, and the CPU backend is built seven
# times — one per ARM generation — so the app can load the best one the phone has when it
# starts rather than guess at build time. See android/llama/build.gradle.kts for the flags.
cmake_minimum_required(VERSION 3.22.1)

project(buddy_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED BUDDY_LLAMA_SOURCE OR NOT EXISTS "${BUDDY_LLAMA_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp is not checked out at '${BUDDY_LLAMA_SOURCE}'. "
        "Run: git submodule update --init --depth 1 third_party/llama.cpp")
endif()

add_subdirectory("${BUDDY_LLAMA_SOURCE}" llama.cpp)

add_library(buddy_llama SHARED buddy_llama.cpp)

target_link_libraries(buddy_llama PRIVATE llama llama-common android log)

# The JNI entry points are the only symbols anything outside this library calls.
set_target_properties(buddy_llama PROPERTIES CXX_VISIBILITY_PRESET hidden)
