# The cross build image of the aarch64 Linux host (linux/build.sh builds it when it is missing): Ubuntu 24.04 (so the host needs glibc 2.39 at most),
# clang and lld for --target=aarch64-linux-gnu, the arm64 libraries the host and SDL3 link against, and the Vulkan WSI headers (vulkan.h, the xcb and
# android platform headers: dm/third_party/vulkan-headers has only the core ones; the Android build takes them from the NDK)
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN sed -i 's/^Types: deb$/Types: deb\nArchitectures: amd64/' /etc/apt/sources.list.d/ubuntu.sources && \
    printf 'Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports\nSuites: noble noble-updates noble-security\nComponents: main universe\nArchitectures: arm64\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' > /etc/apt/sources.list.d/arm64.sources && \
    dpkg --add-architecture arm64 && apt-get update && \
    apt-get install -y --no-install-recommends cmake ninja-build pkg-config git ca-certificates python3 clang lld llvm crossbuild-essential-arm64 \
      libvulkan-dev:arm64 zlib1g-dev:arm64 libxcb1-dev:arm64 libx11-dev:arm64 libxext-dev:arm64 libxrandr-dev:arm64 libxi-dev:arm64 libxcursor-dev:arm64 \
      libxfixes-dev:arm64 libxss-dev:arm64 libxkbcommon-dev:arm64 libwayland-dev:arm64 wayland-protocols libdecor-0-dev:arm64 \
      libasound2-dev:arm64 libpulse-dev:arm64 libpipewire-0.3-dev:arm64 libudev-dev:arm64 libdrm-dev:arm64 libdbus-1-dev:arm64 && \
    rm -rf /var/lib/apt/lists/*
