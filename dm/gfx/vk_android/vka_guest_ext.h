// PEANUTS: the guest sees Nintendo VI in place of the Android loader's actual surface extension.
#pragma once
#include <string.h>
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_vi.h>

// The host-only test uses the vendored core and VI headers, without the NDK's Android header.
#define GUEST_ANDROID_SURFACE "VK_KHR_android_surface"

static inline int guest_title_selected(const char *selected, const char *title) {
    return selected && *selected && !strcmp(selected, title);
}

static inline const char *android_extension_name(const char *name) {
    return !strcmp(name, VK_NN_VI_SURFACE_EXTENSION_NAME) ? GUEST_ANDROID_SURFACE : name;
}

static inline void guest_extension_names(VkExtensionProperties *props, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        if (strcmp(props[i].extensionName, GUEST_ANDROID_SURFACE)) continue;
        strcpy(props[i].extensionName, VK_NN_VI_SURFACE_EXTENSION_NAME);
        props[i].specVersion = VK_NN_VI_SURFACE_SPEC_VERSION;
    }
}
