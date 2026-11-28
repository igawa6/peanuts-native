// PEANUTS: Direct ARM64 Vulkan imports for a build with a verified embedded shader bank.
// The Android loader owns dispatch; the VI surface is presented through the current game window.
#if defined(__ANDROID__)
#include "vka.h"
#include "vk_android.h"
#include "vka_guest_ext.h"
#include "vka_guest_crop.h"
#include "game_profile.h"
#include <dlfcn.h>

static void *loader;
static PFN_vkGetInstanceProcAddr gipa;
static PFN_vkCreateInstance create_instance;
static PFN_vkEnumerateInstanceExtensionProperties enumerate_extensions;
static PFN_vkCreateGraphicsPipelines compile_pipelines __attribute__((used));  // read by the entry below

// KH2 draws on a 72 KiB nn::os fiber. A cold Android pipeline compile may use
// more stack than the entire fiber. All six Vulkan arguments are in x0-x5;
// use the same guarded host stack as the Metal backend, without copying them.
__attribute__((naked)) static void compile_on_host_stack(void) {
    __asm__("adrp x16, compile_pipelines\n"
            "ldr x16, [x16, :lo12:compile_pipelines]\n"
            "b dm_bigstack_enter");
}
#define GUEST_WSI(X) X(vkQueuePresentKHR) X(vkCreateSwapchainKHR) X(vkDestroySwapchainKHR) X(vkDestroySurfaceKHR) X(vkAcquireNextImageKHR) \
    X(vkGetPhysicalDeviceSurfaceSupportKHR) X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) X(vkGetPhysicalDeviceSurfaceFormatsKHR) X(vkGetPhysicalDeviceSurfacePresentModesKHR)
#define DECLARE(f) static PFN_##f real_##f;
GUEST_WSI(DECLARE)
#undef DECLARE
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void open_loader(void) {
    loader = vka_driver_open();
    if (!loader) loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader) die("[RED] guest Vulkan: cannot open Android's Vulkan loader: %s", dlerror());
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(loader, "vkGetInstanceProcAddr");
    create_instance = (PFN_vkCreateInstance)dlsym(loader, "vkCreateInstance");
    enumerate_extensions = (PFN_vkEnumerateInstanceExtensionProperties)dlsym(loader, "vkEnumerateInstanceExtensionProperties");
    compile_pipelines = (PFN_vkCreateGraphicsPipelines)dlsym(loader, "vkCreateGraphicsPipelines");
    if (!compile_pipelines) die("[RED] guest Vulkan: selected loader lacks vkCreateGraphicsPipelines");
#define LOAD(f) real_##f = (PFN_##f)dlsym(loader, #f); if (!real_##f) die("[RED] guest Vulkan: selected loader lacks " #f);
    GUEST_WSI(LOAD)
#undef LOAD
    if (!gipa || !create_instance || !enumerate_extensions) die("[RED] guest Vulkan: selected loader lacks Vulkan instance dispatch");
    VLOG("guest Vulkan: Android loader (%s), mapping VI surfaces to the game window", vka_driver_tag() ? vka_driver_tag() : "system");
}

static VkResult enumerate(const char *layer, uint32_t *count, VkExtensionProperties *props) {
    VkResult r = enumerate_extensions(layer, count, props);
    if (props && (r == VK_SUCCESS || r == VK_INCOMPLETE)) guest_extension_names(props, *count);
    return r;
}

static VkResult create(const VkInstanceCreateInfo *in, const VkAllocationCallbacks *alloc, VkInstance *instance) {
    const char *local[8], **names = local;
    if (in->enabledExtensionCount > sizeof local / sizeof *local) {
        names = malloc((size_t)in->enabledExtensionCount * sizeof *names);
        if (!names) return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    for (uint32_t i = 0; i < in->enabledExtensionCount; ++i) names[i] = android_extension_name(in->ppEnabledExtensionNames[i]);
    VkInstanceCreateInfo ci = *in;
    ci.ppEnabledExtensionNames = names;
    VkResult r = create_instance(&ci, alloc, instance);
    if (names != local) free(names);
    VLOG("guest vkCreateInstance: %d", r);
    return r;
}

// A binding cannot present to a replacement window. The swap extent is in the same
// pre-transform buffer coordinates as the VI source crop (not the display size).
typedef struct Binding { struct Binding *next; uintptr_t handle; uint64_t epoch; VkExtent2D extent; } Binding;
static Binding *surfaces, *swaps;
static pthread_mutex_t bindings_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t present_mu = PTHREAD_MUTEX_INITIALIZER;
static int crop_applied;  // reset on swapchain creation, even for a recycled native window
static guest_crop_rect last_crop;

static void bind_handle(Binding **list, Binding *b, uintptr_t handle, uint64_t epoch) {
    pthread_mutex_lock(&bindings_mu);
    b->handle = handle; b->epoch = epoch; b->next = *list; *list = b;
    pthread_mutex_unlock(&bindings_mu);
}

static uint64_t binding(Binding **list, uintptr_t handle, int remove, VkExtent2D *extent) {
    uint64_t epoch = 0;
    pthread_mutex_lock(&bindings_mu);
    for (Binding **p = list; *p; p = &(*p)->next) if ((*p)->handle == handle) {
        Binding *b = *p;
        epoch = b->epoch;
        if (extent) *extent = b->extent;
        if (remove) { *p = b->next; free(b); }
        break;
    }
    pthread_mutex_unlock(&bindings_mu);
    return epoch;
}

static VkResult surface(VkInstance instance, const VkViSurfaceCreateInfoNN *in, const VkAllocationCallbacks *alloc, VkSurfaceKHR *out) {
    PFN_vkCreateAndroidSurfaceKHR fn = (PFN_vkCreateAndroidSurfaceKHR)gipa(instance, "vkCreateAndroidSurfaceKHR");
    if (!fn) return VK_ERROR_EXTENSION_NOT_PRESENT;
    if (!in || in->sType != VK_STRUCTURE_TYPE_VI_SURFACE_CREATE_INFO_NN || !in->window || in->flags || in->pNext) return VK_ERROR_INITIALIZATION_FAILED;
    // vi::GetNativeWindow returns the guest's VI token, not an ANativeWindow pointer.
    uint64_t epoch;
    ANativeWindow *win = vka_guest_acquire_window(&epoch);
    if (!win) return VK_ERROR_SURFACE_LOST_KHR;
    VkAndroidSurfaceCreateInfoKHR ci = {.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = win};
    Binding *b = malloc(sizeof *b);
    if (b) b->extent = (VkExtent2D){0};
    VkResult r = b ? fn(instance, &ci, alloc, out) : VK_ERROR_OUT_OF_HOST_MEMORY;
    if (r == VK_SUCCESS) bind_handle(&surfaces, b, (uintptr_t)*out, epoch); else free(b);
    ANativeWindow_release(win);
    vka_guest_end_wsi();
    VLOG("guest vkCreateViSurfaceNN: %d", r);
    return r;
}

static void guest_vkDestroySurfaceKHR(VkInstance i, VkSurfaceKHR s, const VkAllocationCallbacks *a) {
    binding(&surfaces, (uintptr_t)s, 1, NULL); real_vkDestroySurfaceKHR(i, s, a);
}

static VkResult guest_vkCreateSwapchainKHR(VkDevice d, const VkSwapchainCreateInfoKHR *ci, const VkAllocationCallbacks *a, VkSwapchainKHR *out) {
    uint64_t epoch = binding(&surfaces, (uintptr_t)ci->surface, 0, NULL);
    if (!vka_guest_begin_wsi(epoch)) return VK_ERROR_SURFACE_LOST_KHR;
    Binding *b = malloc(sizeof *b);
    pthread_mutex_lock(&present_mu);
    VkResult r = b ? real_vkCreateSwapchainKHR(d, ci, a, out) : VK_ERROR_OUT_OF_HOST_MEMORY;
    crop_applied = 0;  // WSI may reconfigure the producer, including on a failed replacement
    pthread_mutex_unlock(&present_mu);
    if (r == VK_SUCCESS) {
        b->extent = ci->imageExtent;
        bind_handle(&swaps, b, (uintptr_t)*out, epoch);
    } else free(b);
    vka_guest_end_wsi();
    return r;
}

static void guest_vkDestroySwapchainKHR(VkDevice d, VkSwapchainKHR s, const VkAllocationCallbacks *a) {
    binding(&swaps, (uintptr_t)s, 1, NULL); real_vkDestroySwapchainKHR(d, s, a);
}

#define SURFACE_QUERY(name, params, args) static VkResult guest_##name params { \
    if (!vka_guest_begin_wsi(binding(&surfaces, (uintptr_t)s, 0, NULL))) return VK_ERROR_SURFACE_LOST_KHR; \
    VkResult r = real_##name args; vka_guest_end_wsi(); return r; }
SURFACE_QUERY(vkGetPhysicalDeviceSurfaceSupportKHR, (VkPhysicalDevice d, uint32_t queue, VkSurfaceKHR s, VkBool32 *out), (d, queue, s, out))
SURFACE_QUERY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR, (VkPhysicalDevice d, VkSurfaceKHR s, VkSurfaceCapabilitiesKHR *out), (d, s, out))
SURFACE_QUERY(vkGetPhysicalDeviceSurfaceFormatsKHR, (VkPhysicalDevice d, VkSurfaceKHR s, uint32_t *n, VkSurfaceFormatKHR *out), (d, s, n, out))
SURFACE_QUERY(vkGetPhysicalDeviceSurfacePresentModesKHR, (VkPhysicalDevice d, VkSurfaceKHR s, uint32_t *n, VkPresentModeKHR *out), (d, s, n, out))
#undef SURFACE_QUERY

static VkResult guest_vkAcquireNextImageKHR(VkDevice d, VkSwapchainKHR s, uint64_t timeout, VkSemaphore sem, VkFence f, uint32_t *image) {
    uint64_t epoch = binding(&swaps, (uintptr_t)s, 0, NULL), left = timeout;
    const uint64_t slice = 16000000, start = timeout > slice && timeout != UINT64_MAX ? now_ns() : 0;
    for (;;) {
        if (!vka_guest_begin_wsi(epoch)) return VK_ERROR_SURFACE_LOST_KHR;
        // An infinite acquire on a disappearing window must not deadlock surfaceDestroyed.
        VkResult r = real_vkAcquireNextImageKHR(d, s, left < slice ? left : slice, sem, f, image);
        vka_guest_end_wsi();
        if (r != VK_TIMEOUT || left <= slice) return r;
        if (timeout != UINT64_MAX) { uint64_t elapsed = now_ns() - start; if (elapsed >= timeout) return VK_TIMEOUT; left = timeout - elapsed; }
    }
}

void dm_guest_frame_tick(void);
void dm_vi_layer_crop(int c[4]);
__attribute__((weak)) void peanuts_tick(void);
static VkResult guest_vkQueuePresentKHR(VkQueue q, const VkPresentInfoKHR *info) {
    VkExtent2D extent = {0};
    uint64_t epoch = info->swapchainCount ? binding(&swaps, (uintptr_t)info->pSwapchains[0], 0, &extent) : 0;
    for (uint32_t i = 1; i < info->swapchainCount; ++i)
        if (binding(&swaps, (uintptr_t)info->pSwapchains[i], 0, NULL) != epoch) { epoch = 0; break; }
    ANativeWindow *win = vka_guest_begin_wsi(epoch);
    if (!win) {
        if (info->pResults) for (uint32_t i = 0; i < info->swapchainCount; ++i) info->pResults[i] = VK_ERROR_SURFACE_LOST_KHR;
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    // The Android swapchain scales the queued source rectangle to the window. Do
    // not redraw or resize the guest image. Keep crop and queue together across queues.
    pthread_mutex_lock(&present_mu);
    int xywh[4]; dm_vi_layer_crop(xywh);
    guest_crop_rect rect = guest_buffer_crop(xywh, extent.width, extent.height);
    if (!crop_applied || memcmp(&rect, &last_crop, sizeof rect)) {
        int err = guest_set_buffer_crop(win, &rect);
        if (err) {
            VRED("guest VI buffer crop rejected: %d", err);
            pthread_mutex_unlock(&present_mu);
            vka_guest_end_wsi();
            if (info->pResults) for (uint32_t i = 0; i < info->swapchainCount; ++i) info->pResults[i] = VK_ERROR_SURFACE_LOST_KHR;
            return VK_ERROR_SURFACE_LOST_KHR;
        }
        last_crop = rect; crop_applied = 1;
        VLOG("guest VI source [%d,%d..%d,%d] on %ux%u (empty = full)", rect.left, rect.top, rect.right, rect.bottom, extent.width, extent.height);
    }
    VkResult r = real_vkQueuePresentKHR(q, info);
    pthread_mutex_unlock(&present_mu);
    vka_guest_end_wsi();
    if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) {
        static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
        pthread_mutex_lock(&mu);  // vka_fps_note normally has one present thread; Vulkan permits several queues.
        vka_fps_note(1, 0);
        pthread_mutex_unlock(&mu);
        dm_guest_frame_tick();
        if (peanuts_tick) peanuts_tick();
    }
    return r;
}

// Only these direct, platform-independent loader exports are exposed. Never resolve arbitrary
// guest strings with dlsym: a handle also searches dependencies. Loader trampolines dispatch on
// VkInstance/VkDevice/VkQueue etc.; no process-global guest instance or device is assumed.
static const char *const direct[] = {
    "vkAllocateCommandBuffers", "vkAllocateDescriptorSets", "vkAllocateMemory", "vkBeginCommandBuffer",
    "vkBindBufferMemory", "vkBindImageMemory", "vkCmdBeginRenderPass", "vkCmdBindDescriptorSets", "vkCmdBindIndexBuffer",
    "vkCmdBindPipeline", "vkCmdBindVertexBuffers", "vkCmdClearAttachments", "vkCmdCopyBufferToImage", "vkCmdCopyImage",
    "vkCmdCopyImageToBuffer", "vkCmdDraw", "vkCmdDrawIndexed", "vkCmdEndRenderPass", "vkCmdPipelineBarrier", "vkCmdResetQueryPool",
    "vkCmdResolveImage", "vkCmdSetBlendConstants", "vkCmdSetScissor", "vkCmdSetStencilReference", "vkCmdSetViewport", "vkCmdWriteTimestamp",
    "vkCreateBuffer", "vkCreateCommandPool", "vkCreateDescriptorPool", "vkCreateDescriptorSetLayout", "vkCreateDevice", "vkCreateFence",
    "vkCreateFramebuffer", "vkCreateImage", "vkCreateImageView", "vkCreatePipelineLayout", "vkCreateQueryPool",
    "vkCreateRenderPass", "vkCreateSampler", "vkCreateSemaphore", "vkCreateShaderModule", "vkDestroyBuffer",
    "vkDestroyCommandPool", "vkDestroyDescriptorPool", "vkDestroyDescriptorSetLayout", "vkDestroyDevice", "vkDestroyFence", "vkDestroyFramebuffer",
    "vkDestroyImage", "vkDestroyImageView", "vkDestroyInstance", "vkDestroyPipeline", "vkDestroyPipelineLayout", "vkDestroyQueryPool",
    "vkDestroyRenderPass", "vkDestroySampler", "vkDestroySemaphore", "vkDestroyShaderModule",
    "vkDeviceWaitIdle", "vkEndCommandBuffer", "vkEnumerateDeviceExtensionProperties", "vkEnumeratePhysicalDevices", "vkFlushMappedMemoryRanges",
    "vkFreeCommandBuffers", "vkFreeDescriptorSets", "vkFreeMemory", "vkGetBufferMemoryRequirements", "vkGetDeviceQueue", "vkGetImageMemoryRequirements",
    "vkGetPhysicalDeviceFeatures", "vkGetPhysicalDeviceMemoryProperties", "vkGetPhysicalDeviceProperties", "vkGetPhysicalDeviceQueueFamilyProperties",
    "vkGetQueryPoolResults", "vkGetSwapchainImagesKHR", "vkMapMemory", "vkQueueSubmit", "vkQueueWaitIdle",
    "vkResetFences", "vkUnmapMemory", "vkUpdateDescriptorSets", "vkWaitForFences"
};

int vka_guest_enabled(void) {
    return guest_title_selected(getenv("DM_GUEST_VULKAN"), DM_GAME_ID);
}

void *vka_guest_import(const char *name) {
    if (name[0] != 'v' || name[1] != 'k') return NULL;
    if (!vka_guest_enabled()) return NULL;
    pthread_once(&once, open_loader);
    if (!strcmp(name, "vkCreateInstance")) return create;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties")) return enumerate;
    if (!strcmp(name, "vkCreateViSurfaceNN")) return surface;
    if (!strcmp(name, "vkCreateGraphicsPipelines")) return compile_on_host_stack;
#define WRAPPED(f) if (!strcmp(name, #f)) return guest_##f;
    GUEST_WSI(WRAPPED)
#undef WRAPPED
    for (size_t i = 0; i < sizeof direct / sizeof *direct; ++i) if (!strcmp(name, direct[i])) {
        void *fn = dlsym(loader, direct[i]);
        if (!fn) die("[RED] guest Vulkan: selected Android loader does not export %s", direct[i]);
        return fn;
    }
    return NULL;
}
#endif
