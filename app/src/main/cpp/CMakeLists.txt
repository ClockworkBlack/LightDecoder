cmake_minimum_required(VERSION 3.22.1)
project(lightdecoder LANGUAGES C CXX)

# One shared library, liblightdecoder.so, with libheif and libde265 linked in
# statically. The name is deliberately unique: Android already ships a system
# library called libheif.so, and bundling a second one under that name invites
# the wrong one being loaded.
#
# The same file builds for Android (through Gradle) and for the build machine
# (LD_HOST_BUILD=ON), where the JVM unit tests load it to decode real files.

option(LD_HOST_BUILD "Build for the build machine, for the JVM unit tests" OFF)

get_filename_component(LD_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LD_THIRD_PARTY "${LD_REPO_ROOT}/third_party" CACHE PATH "Directory holding the libde265 and libheif checkouts")

foreach(lib libde265 libheif)
  if(NOT EXISTS "${LD_THIRD_PARTY}/${lib}/CMakeLists.txt")
    message(FATAL_ERROR
      "${LD_THIRD_PARTY}/${lib} is missing. Run: git submodule update --init --depth 1")
  endif()
endforeach()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# The codecs are always optimised, also in debug variants: an unoptimised HEVC
# decoder takes seconds per sticker. Target options come after the per-config
# flags on the command line, so this wins over -O0.
add_compile_options(-O2 -ffunction-sections -fdata-sections)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# --- libde265 (HEVC decoder) -------------------------------------------------

set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)   # the dec265 command-line program
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
set(ENABLE_SHERLOCK265 OFF CACHE BOOL "" FORCE)

set(LD_DE265_SOURCE_DIR "${LD_THIRD_PARTY}/libde265")
set(LD_DE265_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/libde265")
add_subdirectory("${LD_DE265_SOURCE_DIR}" "${LD_DE265_BINARY_DIR}" EXCLUDE_FROM_ALL)

# --- libheif (HEIF container), decoding HEVC through libde265 only -----------

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
foreach(codec
    X265 KVAZAAR UVG266 VVDEC VVENC X264 OpenH264_DECODER DAV1D AOM_DECODER
    AOM_ENCODER SvtEnc RAV1E JPEG_DECODER JPEG_ENCODER OpenJPEG_ENCODER
    OpenJPEG_DECODER FFMPEG_DECODER OPENJPH_ENCODER)
  set(WITH_${codec} OFF CACHE BOOL "" FORCE)
  set(WITH_${codec}_PLUGIN OFF CACHE BOOL "" FORCE)
endforeach()
set(WITH_UNCOMPRESSED_CODEC OFF CACHE BOOL "" FORCE)
set(WITH_WEBCODECS OFF CACHE BOOL "" FORCE)
set(WITH_LIBSHARPYUV OFF CACHE BOOL "" FORCE)
set(WITH_HEADER_COMPRESSION OFF CACHE BOOL "" FORCE)
set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WITH_GDK_PIXBUF OFF CACHE BOOL "" FORCE)
set(WITH_FUZZERS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(BUILD_DEVELOPMENT_TOOLS OFF CACHE BOOL "" FORCE)
set(WITH_REDUCED_VISIBILITY ON CACHE BOOL "" FORCE)

add_subdirectory("${LD_THIRD_PARTY}/libheif" "${CMAKE_CURRENT_BINARY_DIR}/libheif" EXCLUDE_FROM_ALL)

# libheif keeps its public API headers PRIVATE to the 'heif' target (there is no
# installed include/ tree in this checkout), so name them for our own targets:
#   - .../libheif/api        resolves #include <libheif/heif.h> and friends
#   - <heif build dir>       resolves the generated <libheif/heif_version.h>
set(LD_HEIF_INCLUDE_DIRS
  "${LD_THIRD_PARTY}/libheif/libheif/api"
  "${CMAKE_CURRENT_BINARY_DIR}/libheif")

# --- the JNI library ---------------------------------------------------------

add_library(lightdecoder SHARED
  ld_core.cc
  ld_jni.cc)

target_include_directories(lightdecoder PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" ${LD_HEIF_INCLUDE_DIRS})
target_compile_options(lightdecoder PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(lightdecoder PRIVATE heif)

if(ANDROID)
  target_link_libraries(lightdecoder PRIVATE jnigraphics log)
  target_link_options(lightdecoder PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)
else()
  find_package(JNI REQUIRED)
  target_include_directories(lightdecoder PRIVATE ${JNI_INCLUDE_DIRS})
  if(NOT APPLE)
    target_link_options(lightdecoder PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
  endif()

  # Command-line probe: prints what a file contains and can dump decoded pixels.
  add_executable(ldprobe ld_core.cc ld_probe.cc)
  target_include_directories(ldprobe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" ${LD_HEIF_INCLUDE_DIRS})
  target_link_libraries(ldprobe PRIVATE heif)
endif()
