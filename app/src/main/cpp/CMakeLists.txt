cmake_minimum_required(VERSION 3.22.1)
project("gbalink_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(
    -O3
    -DNDEBUG
    -fexceptions
    -D__LIBRETRO__
    -DHAVE_HLE_BIOS
    -DINLINE=inline
    -DFRONTEND_SUPPORTS_RGB565
    -DUSE_CHEATS
    -DLOAD_FROM_MEMORY
    -DTILED_RENDERING
    -DHAVE_STDINT_H
    -DHAVE_INTTYPES_H
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/vbanext
    ${CMAKE_CURRENT_SOURCE_DIR}/vbanext/src
    ${CMAKE_CURRENT_SOURCE_DIR}/vbanext/libretro
    ${CMAKE_CURRENT_SOURCE_DIR}/vbanext/libretro-common/include
)

set(SOURCES
    jni_bridge.cpp
    vbanext/src/sound.c
    vbanext/src/memory.c
    vbanext/src/gba.c
    vbanext/src/system.c
    vbanext/libretro/libretro.c
    vbanext/libretro-common/memalign.c
    vbanext/libretro-common/compat/compat_posix_string.c
    vbanext/libretro-common/compat/compat_strcasestr.c
    vbanext/libretro-common/compat/compat_snprintf.c
    vbanext/libretro-common/compat/compat_strl.c
    vbanext/libretro-common/compat/fopen_utf8.c
    vbanext/libretro-common/encodings/encoding_utf.c
    vbanext/libretro-common/file/file_path.c
    vbanext/libretro-common/file/file_path_io.c
    vbanext/libretro-common/streams/file_stream.c
    vbanext/libretro-common/string/stdstring.c
    vbanext/libretro-common/string/rstrtod.c
    vbanext/libretro-common/time/rtime.c
    vbanext/libretro-common/vfs/vfs_implementation.c
)

add_library(
    gbalink_core
    SHARED
    ${SOURCES}
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    gbalink_core
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
