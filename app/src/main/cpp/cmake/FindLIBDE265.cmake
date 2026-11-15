# Shadows libheif's own FindLIBDE265 module so that libheif links against the
# libde265 that is built from third_party/libde265 in this same CMake tree,
# instead of looking for one installed on the build machine.
#
# Expects LD_DE265_SOURCE_DIR and LD_DE265_BINARY_DIR to be set by the caller.

if(NOT TARGET de265)
  message(FATAL_ERROR "FindLIBDE265: the in-tree 'de265' target must be added before libheif")
endif()

set(LIBDE265_INCLUDE_DIRS "${LD_DE265_SOURCE_DIR}" "${LD_DE265_BINARY_DIR}")
set(LIBDE265_LIBRARIES de265)
set(LIBDE265_LINK_DIRS "")
set(LIBDE265_CFLAGS "")
set(LIBDE265_VERSION "${libde265_VERSION}")
set(LIBDE265_FOUND TRUE)
