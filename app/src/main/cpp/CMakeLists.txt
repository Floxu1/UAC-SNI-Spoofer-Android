cmake_minimum_required(VERSION 3.22.1)
project(uac_pow_native)

set(AETHER_SO "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libaether.so")

if(EXISTS "${AETHER_SO}")
    add_library(aether SHARED IMPORTED)
    set_target_properties(aether PROPERTIES
        IMPORTED_LOCATION "${AETHER_SO}"
        IMPORTED_NO_SONAME TRUE)

    add_library(aether_jni SHARED aether_jni.cpp)
    target_link_options(aether_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
    find_library(log-lib log)
    target_link_libraries(aether_jni aether ${log-lib})
endif()

set(DIRECT_SO "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libDirect.so")
if(EXISTS "${DIRECT_SO}")
    add_library(Direct SHARED IMPORTED)
    set_target_properties(Direct PROPERTIES IMPORTED_LOCATION "${DIRECT_SO}")
    add_library(direct_jni SHARED "${CMAKE_CURRENT_LIST_DIR}/../../../../core/direct/android/jni.cpp")
    target_link_options(direct_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
    target_link_libraries(direct_jni PRIVATE Direct)
endif()
