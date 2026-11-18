#!/bin/sh
# Builds SDL3 for aarch64 Linux from source (git tag SDL3_VER, default release-3.4.2) in the cross image, with Wayland, X11 and PipeWire / PulseAudio / ALSA:
# the headers and library the host links against when there is no linux/sysroot (a copy of the target system's, linux/README.md), and the copy an AppImage may carry.
# Output: linux/build/sdl3/out/{include/SDL3,lib/libSDL3.so*}
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd); VER=${SDL3_VER:-release-3.4.2}; IMG=${NX_CROSS_IMAGE:-nx-linux-cross:24.04}
[ -d "$ROOT/linux/build/sdl3/src" ] || git clone -q --depth 1 -b "$VER" https://github.com/libsdl-org/SDL "$ROOT/linux/build/sdl3/src"
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT":/src -w /src "$IMG" sh -c "cmake -S linux/build/sdl3/src -B linux/build/sdl3/b -G Ninja -DCMAKE_TOOLCHAIN_FILE=/src/linux/host/aarch64-clang.cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/src/linux/build/sdl3/out -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF -DSDL_KMSDRM=OFF -DSDL_OPENGL=OFF -DSDL_OPENGLES=OFF -DSDL_X11_XTEST=OFF -DSDL_UNIX_CONSOLE_BUILD=OFF >/dev/null && ninja -C linux/build/sdl3/b install >/dev/null"
ls "$ROOT/linux/build/sdl3/out/lib/"libSDL3.so*
