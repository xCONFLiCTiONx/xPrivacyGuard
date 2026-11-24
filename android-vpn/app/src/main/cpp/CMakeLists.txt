cmake_minimum_required(VERSION 3.22.1)
project(xprivacy_aether_jni LANGUAGES C CXX)

add_library(xprivacy_aether_jni SHARED
    aether_jni.cpp
)

target_compile_features(xprivacy_aether_jni PRIVATE cxx_std_17)
target_include_directories(xprivacy_aether_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_directories(xprivacy_aether_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
target_link_libraries(xprivacy_aether_jni PRIVATE aether)
