cmake_minimum_required(VERSION 3.22.1)
project(trackmr LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CARDBOARDSDK_RENDERING_GLESv3 ON CACHE BOOL "" FORCE)
set(CARDBOARDSDK_RENDERING_VULKAN OFF CACHE BOOL "" FORCE)
set(CARDBOARDSDK_UNITY_PLUGIN OFF CACHE BOOL "" FORCE)
get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_subdirectory("${ROOT}/.deps/cardboard/sdk" "${CMAKE_BINARY_DIR}/cardboard")
# Upstream's export map also lists Unity/Vulkan functions even when those backends
# are disabled. Keep the C API, but do not weaken NDK undefined-symbol checks.
set_property(TARGET GfxPluginCardboard PROPERTY LINK_FLAGS
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/cardboard-gles.lds")
set_property(TARGET GfxPluginCardboard PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/cardboard-gles.lds")
add_library(trackmr SHARED renderer.cpp)
target_include_directories(trackmr PRIVATE "${ROOT}/.deps/cardboard/sdk/include")
target_compile_options(trackmr PRIVATE -Wall -Wextra -Werror=return-type -O2)
target_link_libraries(trackmr GfxPluginCardboard android log GLESv3)
# Android 15+ 16 KiB page compatibility.
target_link_options(trackmr PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(GfxPluginCardboard PRIVATE "-Wl,-z,max-page-size=16384")
