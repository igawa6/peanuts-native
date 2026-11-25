// Internal interface of the NVN → Vulkan replay (dm/gfx/vk_android): the device (vka_dev.c), placed guest memory and pools (vka_mem.c), textures and descriptor tables (vka_tex.c),
// the command replay (vka_exec.c) and the present (vka_present.c). They stand in for nvn_mtl.m behind the mtl_* interface hle_nvn.c calls (nvn_mtl.h, nvn_obj.h).
// Vulkan comes from the headers in dm/third_party (newer than the NDK's 1.3.275: VK_EXT_map_memory_placed, VK_KHR_present_wait2); every entry point is loaded through vkGetInstanceProcAddr
#pragma once
#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan_core.h>
#include <android/native_window.h>
#include <vulkan/vulkan_android.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>   // PEANUTS: getenv/atoi (vka_red_repeat)
#include "../nvn/nvn_obj.h"
#include "../../host/dm.h"
#include "vka_fmtlist.h"
#include "vka_soft.h"

#define VLOG(...) LOG("[vk] " __VA_ARGS__)
#define VRED(...) LOG("[RED] vk: " __VA_ARGS__)
// PEANUTS: DM_VK_RED_REPEAT=<n> prints each VRED_ONCE site up to n times (default 1, as before): a failure that recurs later in a run, after its first
// print, was silent (Mario Kart 8 Deluxe's black attract race, issue #6, printed nothing while it was black)
static inline int vka_red_repeat(void) { static int n = -1; if (n < 0) { const char *e = getenv("DM_VK_RED_REPEAT"); n = e && atoi(e) > 0 ? atoi(e) : 1; } return n; }
#define VRED_ONCE(...) do { static _Atomic int once_; if (atomic_fetch_add(&once_, 1) < vka_red_repeat()) VRED(__VA_ARGS__); } while (0)   // PEANUTS: repeat (above)

#define VKA_GLOBAL_FNS(X) X(vkCreateInstance) X(vkEnumerateInstanceExtensionProperties) X(vkEnumerateInstanceLayerProperties) X(vkEnumerateInstanceVersion)
#define VKA_INSTANCE_FNS(X) \
    X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) X(vkGetPhysicalDeviceProperties2) X(vkGetPhysicalDeviceFeatures) X(vkGetPhysicalDeviceFeatures2) \
    X(vkGetPhysicalDeviceMemoryProperties) X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceFormatProperties) X(vkGetPhysicalDeviceImageFormatProperties) X(vkGetPhysicalDeviceSparseImageFormatProperties) \
    X(vkEnumerateDeviceExtensionProperties) X(vkGetPhysicalDeviceExternalBufferProperties) X(vkCreateDevice) X(vkGetDeviceProcAddr) \
    X(vkCreateAndroidSurfaceKHR) X(vkDestroySurfaceKHR) X(vkGetPhysicalDeviceSurfaceSupportKHR) X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR) X(vkGetPhysicalDeviceSurfacePresentModesKHR)
#define VKA_DEVICE_FNS(X) \
    X(vkGetDeviceQueue) X(vkDeviceWaitIdle) X(vkQueueSubmit) X(vkQueueWaitIdle) X(vkQueueBindSparse) X(vkGetImageSparseMemoryRequirements) \
    X(vkAllocateMemory) X(vkFreeMemory) X(vkMapMemory) X(vkUnmapMemory) X(vkMapMemory2KHR) X(vkUnmapMemory2KHR) X(vkGetMemoryFdKHR) \
    X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) X(vkGetBufferDeviceAddress) \
    X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkGetImageMemoryRequirements2) X(vkBindImageMemory) X(vkCreateImageView) X(vkDestroyImageView) \
    X(vkCreateSampler) X(vkDestroySampler) \
    X(vkCreateCommandPool) X(vkAllocateCommandBuffers) X(vkResetCommandBuffer) X(vkBeginCommandBuffer) X(vkEndCommandBuffer) \
    X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkWaitSemaphores) X(vkGetSemaphoreCounterValue) X(vkSignalSemaphore) \
    X(vkCreateFence) X(vkDestroyFence) X(vkWaitForFences) X(vkResetFences) X(vkGetFenceStatus) \
    X(vkCreateEvent) X(vkDestroyEvent) X(vkSetEvent) X(vkCmdWaitEvents) X(vkDestroyCommandPool) \
    X(vkCmdPipelineBarrier) X(vkCmdCopyBuffer) X(vkCmdUpdateBuffer) X(vkCmdFillBuffer) X(vkCmdCopyBufferToImage) X(vkCmdCopyImageToBuffer) \
    X(vkCmdCopyImage) X(vkCmdBlitImage) X(vkCmdResolveImage) X(vkCmdClearColorImage) X(vkCmdClearDepthStencilImage) X(vkCmdClearAttachments) \
    X(vkCmdBeginRendering) X(vkCmdEndRendering) X(vkCmdBeginRenderPass) X(vkCmdEndRenderPass) X(vkCreateRenderPass) X(vkCreateFramebuffer) \
    X(vkCreateDescriptorSetLayout) X(vkCreateDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
    X(vkCreateSwapchainKHR) X(vkDestroySwapchainKHR) X(vkGetSwapchainImagesKHR) X(vkAcquireNextImageKHR) X(vkQueuePresentKHR) \
    X(vkGetRefreshCycleDurationGOOGLE) X(vkGetPastPresentationTimingGOOGLE) \
    X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkDestroyDescriptorSetLayout) X(vkDestroyPipeline) X(vkCreatePipelineCache) X(vkGetPipelineCacheData) \
    X(vkCreateGraphicsPipelines) X(vkCreateComputePipelines) X(vkResetDescriptorPool) X(vkCreateBufferView) X(vkDestroyBufferView) \
    X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdBindVertexBuffers2) X(vkCmdBindIndexBuffer) X(vkCmdBindIndexBuffer2KHR) /* PEANUTS: bounded draw ranges */ \
    X(vkCmdDraw) X(vkCmdDrawIndexed) X(vkCmdDrawIndirect) X(vkCmdDrawIndexedIndirect) X(vkCmdDispatch) X(vkCmdDispatchIndirect) X(vkCmdDispatchBase) \
    X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdSetCullMode) X(vkCmdSetFrontFace) X(vkCmdSetPrimitiveTopology) \
    X(vkCmdSetAttachmentFeedbackLoopEnableEXT) X(vkCmdSetDepthTestEnable) X(vkCmdSetDepthWriteEnable) X(vkCmdSetDepthCompareOp) X(vkCmdSetDepthBoundsTestEnable) X(vkCmdSetStencilTestEnable) X(vkCmdSetStencilOp) \
    X(vkCmdSetStencilCompareMask) X(vkCmdSetStencilWriteMask) X(vkCmdSetStencilReference) X(vkCmdSetDepthBias) X(vkCmdSetDepthBiasEnable) \
    X(vkCmdSetRasterizerDiscardEnable) X(vkCmdSetPrimitiveRestartEnable) X(vkCmdSetBlendConstants) X(vkCmdSetLineWidth) \
    X(vkCmdPushDescriptorSetKHR) X(vkCmdSetVertexInputEXT) X(vkCmdPushConstants) X(vkCmdDrawMeshTasksEXT) X(vkCmdDrawMeshTasksIndirectEXT) \
    X(vkCmdDrawIndirectCount) X(vkCmdDrawIndexedIndirectCount) \
    X(vkCmdBeginQuery) X(vkCmdEndQuery) \
    X(vkCmdBindTransformFeedbackBuffersEXT) X(vkCmdBeginTransformFeedbackEXT) X(vkCmdEndTransformFeedbackEXT)   // PEANUTS: transform feedback (vka_tf.c); NULL without the extension
#define VKA_DECL(f) PFN_##f f;
typedef struct { PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr; VKA_GLOBAL_FNS(VKA_DECL) VKA_INSTANCE_FNS(VKA_DECL) VKA_DEVICE_FNS(VKA_DECL) } vka_fns;
extern vka_fns vk;   // a missing optional entry point is NULL

// ---------- Switches read again while the game runs (vka_ctl.c) ----------
// VKA_ENV("DM_VK_<NAME>", def) is a DM_VK_* switch's value as a number (strtod; unset or empty = def), cached per call site: one relaxed load and a compare while nothing changed.
// A live "env KEY=VALUE" control line (android/TESTING.md "Live control") bumps vka_env_gen at a frame boundary and every call site reads its switch again on its next call.
// Read switches through it, not getenv: a switch that cannot change while running (it sizes a device object, a cache) goes into vka_ctl.c's restart-only table
// A call site can run on two threads at once (the game's and the replay worker's): v is atomic (no torn read), and only one thread at a time refreshes a cache, and only to a
// newer generation (vka_env_slow), so a thread that read the value before a change cannot store it over a newer one while gen says current
typedef struct { _Atomic uint32_t gen; _Atomic double v; } vka_envc;
extern _Atomic uint32_t vka_env_gen;
double vka_env_slow(vka_envc *c, const char *key, double def);
static inline double vka_env_c(vka_envc *c, const char *key, double def) {
    return atomic_load_explicit(&c->gen, memory_order_acquire) == atomic_load_explicit(&vka_env_gen, memory_order_relaxed) ? atomic_load_explicit(&c->v, memory_order_relaxed)
                                                                                                                         : vka_env_slow(c, key, def);
}
#define VKA_ENVF(key, def) ({ static vka_envc c_; vka_env_c(&c_, key, def); })
#define VKA_ENV(key, def) ((int)VKA_ENVF(key, def))
int vka_env_str(const char *key, char *out, size_t n);   // a string switch as live control sees it (1 = set); not cached: for switches read rarely
void vka_floor_span(uint64_t frame, uint64_t ns);   // vka_exec.c: a submission of frame ended ns after the frame began (the adaptive floor)
void vka_floor_stats(char *b, size_t n);
void vka_floor_commit(int counted);   // DM_VK_GPU_FLOOR_AUTO=2: a submission counted by vka_floor_tail_add handed to the queue / done
void vka_floor_done(int counted);
void vka_gpu_floor(void);               // mtl_sub_begin: DM_VK_GPU_FLOOR's GPU clock floor on the queue (vka_dev.c)
void vka_ctl_sub(void);                 // mtl_sub_begin: live switches queued since the last frame are applied
void vka_ctl_frame(uint64_t f);         // mtl_set_frame: per-frame counters for "capture" and "stats"
void vka_exec_counts(uint64_t *ops, uint64_t *subs, uint64_t *rec_ns);   // vka_exec.c: clears / copies, submissions, recording time so far

// ---------- Device (vka_dev.c) ----------
typedef struct {
    VkInstance inst; VkPhysicalDevice pd; VkDevice dev; VkQueue q; uint32_t qf;
    VkPhysicalDeviceProperties props; VkPhysicalDeviceMemoryProperties memp;
    int placed, placed_range, unmap_reserve, fd_alias, bda, uab, display_timing, has_android_surface;
    int mem_budget;                                 // VK_EXT_memory_budget: the texture cache's limit reads the image heap's budget and usage (vka_tex.c, vka_texlimit.h)
    void *perf_hint;                                // vkQueueSetPerfHintQCOM (VK_QCOM_queue_perf_hint), NULL without it: vka_gpu_floor
    // The draw path's device facts: push descriptors (and how many a set may hold), vertex input dynamic state, robustness2's null descriptors, 8-bit indices,
    // instance-rate divisors, logic operations, dual-source blending, geometry and tessellation stages, depth clamp, fill mode lines, wide lines
    int imageless;   // imagelessFramebuffer (Vulkan 1.2): classic render passes need it (DM_VK_CLASSIC_RP)
    int minmax;      // samplerFilterMinmax (Vulkan 1.2): NVN's min / max sampler reduction (vka_tex.c sampler_of)
    int draw_count;                                 // drawIndirectCount (Vulkan 1.2): an indirect-count multi-draw's count read by the GPU (vka_draw.c vka_mdi_gpu)
    int push_desc, vin_dyn, null_desc, idx8, divisor, logic_op, dual_src, geom, tess, depth_clamp, fill_lines, depth_bias_clamp, multi_vp, a2c;
    int vstores, fstores;   // vertexPipelineStoresAndAtomics, fragmentStoresAndAtomics: texture-use feedback of the pool (vka_tex.c "Texture pool reads") writes from those stages
    // NVN2 task and mesh programs (VK_EXT_mesh_shader: mesh = meshShader, task = taskShader too; DM_VK_MESH=0 leaves it off): their shaders are refused without it (vka_shader.c refusal)
    // and their draws skipped. mesh_inv / task_inv: the largest workgroup (maxMeshWorkGroupInvocations / maxTaskWorkGroupInvocations); mesh_out_v / mesh_out_p: the most vertices /
    // primitives a mesh workgroup may output; task_payload: maxTaskPayloadSize; task_groups / mesh_groups: maxTaskWorkGroupCount[0] / maxMeshWorkGroupCount[0] (a draw's count)
    int mesh, task; uint32_t mesh_inv, task_inv, mesh_out_v, mesh_out_p, task_payload, task_groups, mesh_groups;
    // memu: NVN2 task and mesh draws drawn through compute (vka_memu.c): by default where the device lacks meshShader or taskShader, DM_VK_MESH_EMU=1 on any device, =0
    // never (restart). cs_groups: maxComputeWorkGroupCount[0]
    int memu; uint32_t cs_groups;
    int fb_loop;                                    // PEANUTS: VK_EXT_attachment_feedback_loop_layout + _dynamic_state are on (DM_VK_FB_LOOP=0 leaves them off): a draw sampling its own attachment says so (vka_draw.c)
    int xfb;                                        // PEANUTS: VK_EXT_transform_feedback is on (DM_VK_XFB=0 leaves it off): NVN transform feedback is captured (vka_tf.c)
    int cache_ctl;                                  // pipelineCreationCacheControl (core 1.3): a pipeline may be asked for with FAIL_ON_PIPELINE_COMPILE_REQUIRED (vka_pso.c cs_obtain's cache-only try)
    int gpl;                                        // graphics pipeline libraries with fast linking (VK_EXT_graphics_pipeline_library): a pipeline the cache lacks is linked from stage libraries at once, the optimized one made in the background (vka_pso.c)
    uint32_t max_push, max_vin_bindings, subgroup_size, subgroup_min, subgroup_max, subgroup_stages;   // subgroup_stages: VkShaderStageFlags with subgroup operations
    uint32_t subgroup_req_stages;                   // VkShaderStageFlags a pipeline may pin the subgroup size in (requiredSubgroupSizeStages; 0 without subgroupSizeControl)
    int subgroup_quad;                              // PEANUTS: VK_SUBGROUP_FEATURE_QUAD_BIT (quad operations; in the fragment stage wherever it has subgroup operations)
    // The driver (VkPhysicalDeviceDriverProperties.driverID: VK_DRIVER_ID_QUALCOMM_PROPRIETARY, VK_DRIVER_ID_MESA_TURNIP, ...). A per-driver default keys on this and on
    // feature queries, never on the driver's name; each such default keeps its DM_VK_* switch (android/README.md "Custom GPU drivers")
    int driver_id;
    int qcom_old;                                   // Qualcomm's own driver before 512.800 (512.676 on the Adreno 740): per-driver defaults key on it (vka_dev.c DM_VK_VIN_DYN, vka_pso.c DM_VK_PREWARM)
    VkDeviceSize ub_align, sb_align, ub_range, sb_range;
    VkDeviceSize placed_align, max_alloc;
    uint32_t guest_type, local_type, upload_type;   // guest RAM (DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT|HOST_CACHED, or the nearest), images, upload arenas
    VkSemaphore tl;                                 // the timeline: value n = submission n has completed
    pthread_mutex_t qmu;                            // the queue is shared by the submit and present threads
    int sparse_res;                                 // sparse residency images can be made (vka_sparse.c): binding on the queue, 2D residency, standard block shape, strict non-resident reads
    uint64_t tl_queued;                             // the highest timeline value a vkQueueSubmit so far signals (under qmu): what a later submission on the queue may wait for
    int tl_host;                                    // the GPU signals no timeline: submissions get fences and the host signals their values (vka_dev.c "Host-signalled timelines")
} vka_dev;
extern vka_dev V;
// Custom GPU drivers (vka_driver.c): DM_VK_DRIVER=<folder with meta.json, or a .so> loads that driver through libadrenotools (restart only); unset, empty or "system" = the
// system libvulkan.so. vka_driver_open returns the libvulkan handle (NULL = use the system one); vka_driver_tag is a file-name-safe name of the custom driver in use, NULL for the system's
void *vka_driver_open(void);
const char *vka_driver_tag(void);
int vka_init(void);   // 1 = the device is up (idempotent, any thread); 0 = no Vulkan: every op logs and does nothing
#include "vka_fpenv.h"
// The host's FPCR (0) on this thread (vka_fpenv.h): who names a thread the host made, logged the first few times one started with the game's FPCR (NULL: not logged);
// returns the FPCR the thread had, which vka_fp_back puts back. vka_fp_guest: DM_VK_GUEST_FPCR=1 (diagnostic, restart), every FPCR left as inherited (vka_dev.c)
uint64_t vka_fp_host(const char *who);
static inline void vka_fp_back(uint64_t was) { vka_fp_leave(was); }
int vka_fp_guest(void);
int vka_ok(void);
uint32_t vka_mem_type(uint32_t bits, VkMemoryPropertyFlags want, VkMemoryPropertyFlags avoid);
VkResult vka_submit(VkCommandBuffer cb, uint64_t signal, uint32_t nwait, const VkSemaphore *wait, const uint64_t *wait_vals, const VkPipelineStageFlags *stages, VkSemaphore bin_signal);
VkResult vka_submit_fence(VkCommandBuffer cb, uint64_t signal, VkFence fence, uint64_t gate);   // (vka_exec.c: an unsignalled commit's own fence; gate: the gate value it waits for, 0 = none)
// Gates (vka_dev.c): the GPU waits for an event word the CPU sets (vka_qsched.h "Waits for the CPU"). vka_gate_add: a gate on word w (the NVN predicate mode / value) for the
// submission of fence, returns the gate semaphore value the replay's next submission waits for (0: none made, park instead); park_ns: its time (0: none).
// vka_gate_poll: open the gates that hold now (in order; any thread: nvnEventSignal calls it). vka_gate_cpu_wait: a CPU thread is about to wait for fence: the gates
// before it open (the GPU would wait for that thread). vka_gate_banned: w's gate ran out of time (park it instead). *ev: the gate's host event (VK_NULL_HANDLE: none), set
// when the gate opens; the gated work waits for it with the host's writes in its scope (vka_exec.c vka_rp_gate), destroyed once fence is done
VkSemaphore vka_gate_sem(void);
uint64_t vka_gate_add(volatile uint32_t *w, uint32_t mode, uint32_t value, uint64_t fence, uint64_t park_ns, VkEvent *ev);
void vka_gate_poll(void);
void vka_gate_cpu_wait(uint64_t fence);
int vka_gate_banned(const volatile uint32_t *w);
void vka_gate_host_stall(int on);   // 1 / 0 around the host's own long work on the present path (the game's thread waits behind it): the gates' time stands still
void vka_gate_stats(uint64_t *n_word, uint64_t *n_cpu, uint64_t *n_time, uint64_t *wait_ns);   // cumulative: opened by their word, by a CPU wait, by their time; time armed
int vka_gate_pending(void); int vka_gate_drain(uint64_t timeout_ns);   // gates not yet opened; wait until none is (the present on a driver that holds submissions back behind a gate: vka_dev.c)
void vka_device_lost(const char *what);   // a call returned VK_ERROR_DEVICE_LOST: the GPU hang is declared now (vka_dev.c)
VkResult vka_host_signal(VkSemaphore s, uint64_t v);   // the host's own signal of a timeline value (a failed submission's), in step with host-signalled timelines (vka_dev.c)

// The fence ledger: fence n is "done" once the GPU passed it and its CPU-side effects (events, counters) have run
uint64_t vka_fence_next(void);                      // reserve the next fence value (submit thread)
uint64_t vka_fence_last(void);                      // the last fence reserved (its submission may still be recording on another thread)
uint64_t vka_fence_queued(void);                    // the last fence handed to the queue: the most a submission (the present's) may wait for without a wait-before-signal on its own queue
// The replay (vka_exec.c), entered by the replay worker (vka_worker.c) or inline by the mtl_* calls of the submitting thread
void vka_rp_sub_begin(uint64_t reserved);   // PEANUTS: the worker's fence, or zero for an inline submission that reserves at commit
void vka_rp_sub_cont(int on);   // the next vka_rp_sub_begin goes on with a submission cut at a GPU wait (vka_worker.c): its texture work was done at its first part
void vka_rp_exec(const uint8_t *handle, uint64_t fence);   // fence: the submission's, reserved before it (0 = taken at its commit)
uint64_t vka_rp_sub_commit(uint64_t reserved);
void vka_rp_queue_activate(uint64_t serial);
void vka_rp_queue_forget(uint64_t serial);
void vka_rp_prog_forget(const prog_t *p);   // a finalized program leaves every queue's bindings (replay thread)
void vka_rp_set_queue_origin(int on);
int vka_worker_on(void);                              // DM_VK_WORKER (default 1): submissions are replayed on the replay worker thread
uint64_t vka_present_reserve(void);
void vka_present_fill(uint64_t tk, const tex_t *t, double period_s, const int crop[4]);
uint64_t vka_present_inline(const tex_t *t, double period_s, const int crop[4]);
uint64_t vka_hang_ns(void);                         // how long a fence may make no progress before it is a hang (DM_VK_HANG_S, default 6 s)
int vka_hung(void);
int vka_queue_idle(void);                           // vkQueueWaitIdle bounded by the hang time (0 = not idle: hung or timed out)                                 // 1 = a GPU hang was declared (vka_dev.c): nothing more is submitted, waits return at once
void vka_fence_post(uint64_t fence, void *ops, int n, void (*run)(void *ops, int n));   // effects run by the completion thread when fence completes
uint64_t vka_done(void);
void vka_fence_cpu(uint64_t fence, void *ops, int n, void (*run)(void *ops, int n));   // no GPU behind it (no device, a failed submit): the effects run now and fence is done
int vka_wait_done(uint64_t fence, uint64_t timeout_ns);
int vka_worker_cpu_wait(uint64_t fence);    // vka_worker.c: a CPU thread is about to block on fence (parked queues and gates that hold it back go on once the worker is stuck)
void vka_worker_cpu_wait_end(int was);      // ... and is no longer blocked (was: what vka_worker_cpu_wait returned)
uint64_t vka_worker_cpu_blocked(void);       // the highest fence CPU threads block on now (0: none)
// The GPU-wait trace (vka_worker.c, DM_VK_QS_TRACE): the last events of the queue scheduler and the submissions, printed when a GPU hang is declared
enum { QT_SUB = 1, QT_PARK, QT_PASS, QT_RELEASE, QT_GATE, QT_FORCE, QT_SIGNAL, QT_COMMIT, QT_PART, QT_LAND, QT_PRESENT, QT_CPU_WAIT, QT_SUBMIT_ERR, QT_SLOT };
void vka_tex_slot_counts(uint64_t *writes, uint64_t *changes);   // vka_tex.c: pool table slot writes, and of them changes of a slot's view
void vka_qtrace(int kind, uint64_t q, uint64_t fence, uint64_t x, uint32_t a, uint32_t b, uint32_t c, uint32_t d, const volatile void *w);
int vka_qtrace_on(void);
void vka_qtrace_dump(void);
void vka_after(uint64_t fence, void (*fn)(void *), void *arg);   // deferred destruction: fn(arg) once fence is done

// Host-visible scratch memory for one submission (uploads, deswizzled texels, readbacks); valid until the submission's fence
typedef struct { VkBuffer buf; uint8_t *ptr; VkDeviceSize off; } vka_span;
int vka_scratch(VkDeviceSize n, VkDeviceSize align, vka_span *out);
void vka_scratch_retire(uint64_t fence);            // everything handed out since the last call belongs to fence
// Upload batches (vka_dev.c): a new image's first content recorded into a command buffer of its own, whose staging retires on its own completion. Replay thread only.
// vka_up_begin: the open batch (begun when none; NULL = none, record into the submission), and vka_scratch on this thread takes the batch's staging until vka_up_end.
// vka_up_point: in an upload's loop, cb itself, or, when cb is the batch and its staging has passed the batch budget, the batch sent and the next one (NULL: none could
// be begun, the upload stops). vka_up_cur: after such a loop, the open batch when cb was a batch. vka_up_flush: the open batch to the queue (before every part or
// submission the replay hands to the queue)
extern _Thread_local int vka_scr_up;
int vka_up_on(void);
VkCommandBuffer vka_up_begin(void);
void vka_up_end(void);
VkCommandBuffer vka_up_point(VkCommandBuffer cb);
VkCommandBuffer vka_up_cur(VkCommandBuffer cb);
void vka_up_flush(void);
void vka_up_throttle(void);                         // waits while more than DM_VK_UPLOAD_INFLIGHT_MB of batch staging is in flight (no lock held)
void vka_up_stats(uint64_t *batches, uint64_t *waits, double *wait_ms);
VkCommandBuffer vka_rp_cb(void);                    // the replay's command buffer being recorded (vka_exec.c; NULL: none)
// One "memory" line each time the replay's own memory (placed pools, images, scratch) has taken DM_VK_MEMLOG MiB more (allocations counted, frees not) (default 512, 0: none) since the last one: where the memory
// went in a game that the system stops before its first frame (Mario Kart World 1.8.0 on a 16 GB phone). Values read without the owners' locks (a log line, called under them)
void vka_mem_grew(uint64_t bytes);
uint64_t vka_scratch_bytes(uint32_t *blocks);
uint64_t vka_mem_placed_nolock(void);
uint64_t vka_tex_mem_nolock(int *n);

// Sparse residency images (vka_sparse.c): memory only behind the 64 KB tiles a sparse pool's texture has mapped
typedef struct vks_img vks_img;
int vks_on(void);                                   // the device can (V.sparse_res) and DM_VK_SPARSE_RES is not 0
int vks_va_take(uint64_t bytes);                    // sparse address space for an image of that virtual size: 1 = taken
void vks_va_give(uint64_t bytes);
uint64_t vks_va_free(void);                         // sparse address space free now (a texture left at a coarser level tries a finer one once more is free than when it failed)
vks_img *vks_new(VkImage img, uint32_t type, uint32_t w, uint32_t h, uint32_t levels, uint32_t layers, uint32_t tw, uint32_t th, uint32_t tiled);   // NULL: make it dense
int vks_tile(vks_img *s, uint32_t lv, uint32_t ly, uint32_t tx, uint32_t ty, int want);   // bind / unbind one tile of image level lv: 1 = bound now
int vks_tile_bound(vks_img *s, uint32_t lv, uint32_t ly, uint32_t tx, uint32_t ty);
uint32_t vks_tiled(const vks_img *s);               // image levels bound tile by tile (the later ones are bound whole)
uint64_t vks_img_bytes(vks_img *s);
void vks_free(vks_img *s);                          // img_destroy, before vkDestroyImage
void vks_queue_locked(uint32_t *nw, VkSemaphore *ws, uint64_t *wv, VkPipelineStageFlags *wst);   // before a vkQueueSubmit of the replay or an upload batch, under V.qmu
void vks_stats(char *b, size_t n);
uint64_t vks_bytes_nolock(void);

// ---------- Guest memory (vka_mem.c) ----------
// Guest RAM is anonymous memory; each NVN memory pool becomes placed Vulkan memory at its own address when it is made (mtl_pool_add) and anonymous again when it goes (mtl_pool_del):
// the CPU and GPU see the same bytes. A placed range is a segment with one VkBuffer over it
int vka_addr(uint64_t addr, uint64_t len, VkBuffer *buf, VkDeviceSize *off, VkDeviceAddress *bda);   // 1 = [addr, addr + len) lies in one placed segment
int vka_mem_release(uintptr_t lo, uintptr_t hi);    // the host gives [lo, hi) back: segments there are given back first; 0 = the host releases the range itself   // PEANUTS: a segment it covers part of stays, less that part (vka_holes.h)
uint64_t vka_addr_run(uint64_t addr, uint64_t len, VkBuffer *buf, VkDeviceSize *off);   // bytes from addr (at most len) in the segment that holds addr, 0 = none
// PEANUTS: GPU-only lookups also resolve NVN virtual pools; their virtual addresses are still inaccessible to the CPU.
uint64_t vka_gpu_addr_run(uint64_t addr, uint64_t len, VkBuffer *buf, VkDeviceSize *off);
int vka_gpu_addr(uint64_t addr, uint64_t len, VkBuffer *buf, VkDeviceSize *off);
extern _Atomic uint32_t vka_seg_gen;   // vka_mem.c: bumped when a segment's buffer is destroyed (views over segment buffers are made again)
void vka_mem_join_due(void);   // the replay thread at a submission's start: joins the parts of pools placed by parts that a range given to the GPU lies across (vka_mem.c)
int vka_pool_bda(uint64_t addr, VkDeviceAddress *bda);   // the device address of a pool byte (the table the binding side reads)
void *vka_mem_mapped(size_t n);   // no placed maps: a mapped allocation as a segment where the driver maps it (the selftest on an emulator)
int vka_mem_chunks(uint64_t *lo, uint64_t *hi, uint64_t *bda, int max);   // the placed segments by address (guest range and device address); the count
uint64_t vka_mem_sink(void);   // raw global memory's sink for accesses in no pool (entry 0 of its table); 0 if none
uint64_t vka_mem_placed_bytes(uint64_t *peak);     // bytes of placed Vulkan memory now (and the peak)

// ---------- Textures (vka_tex.c) ----------
typedef struct {
    VkFormat vk;           // the image's own format (8-bit sRGB formats are stored as their UNORM twin: the present and copies move raw bytes, views add the sRGB decode)
    VkFormat view;         // the format views and clears use
    uint8_t kind;          // 0 float / unorm / snorm, 1 uint, 2 sint, 3 depth, 4 stencil, 5 depth-stencil
    uint8_t srgb;          // stored as UNORM, cleared with an sRGB-encoded colour
    uint8_t bpb, bw, bh;
} vka_fmt;
int vka_fmt_of(int nvn_format, vka_fmt *f);         // 0 = no Vulkan format (logged once per format)
// An image's memory (vka_imgmem.c): space in a shared block (blk, at off, units of 256 bytes) or a dedicated allocation (blk NULL, off 0); size = its memory requirements' size
// z: zero-shared memory (vka_imem_bind_zero; dm is the shared allocation, freed with its last image)
typedef struct { VkDeviceMemory dm; uint64_t off, size; void *blk; uint32_t units; int z; } vka_imem;
VkResult vka_imem_bind(VkImage img, uint32_t type, const VkMemoryRequirements *mr, vka_imem *m);   // allocated and bound, or the error with nothing allocated
VkResult vka_imem_bind_zero(VkImage img, uint32_t type, const VkMemoryRequirements *mr, const void *key, uint32_t klen, vka_imem *m, int *fresh);   // bound to the zeroed allocation of its key (vka_zshare.h)
uint64_t vka_imem_zbytes(void); uint64_t vka_imem_zbytes_nolock(void);   // bytes of the zero-shared allocations
void vka_imem_zowned(void);   // a zero-shared image got memory of its own (vka_tex.c img_own): counted for the stats line
void vka_imem_free(vka_imem *m);   // after vkDestroyImage, once the GPU is done with the image
void vka_imem_stats(char *b, size_t n);
uint64_t vka_imem_allocations(void);   // VkDeviceMemory objects the images hold (blocks + dedicated)
typedef struct vka_img {
    uint64_t serial; const uint8_t *mem; nvn_texdesc d; nvn_texlayout L;
    VkImage img; vka_imem m; vka_fmt f; VkImageUsageFlags usage; VkImageAspectFlags aspect;
    uint32_t w, h, depth, layers, levels, samples; uint64_t bytes;
    VkImageCreateFlags cflags; uint32_t nvf; VkFormat vf[6];   // as created: flags and the view format list (none: nvf 0), for imageless framebuffers (DM_VK_CLASSIC_RP)
    int refs, inited, gpu_owned, cpu_dirty;
    int is_video;
    uint64_t last_frame;
    uint64_t linger;         // PEANUTS: the frame a GPU-drawn image's last texture object was finalized in, kept for one of the same storage and shape (vka_tex.c linger_*); 0 = not lingering
    uint64_t pool_frame;     // the last prune that found a live texture pool slot naming it (vka_tex_prune_locked): shaders may read it through the pool, so it is in use
    uint64_t last_fence;
    int no_storage;          // remaking it with storage use was refused (img_storage_upgrade): not tried again
    int holds;               // references taken from outside the recording thread (vka_tex_acquire / vka_tex_ref: the present, frame generation, capture), which read img unlocked: the image is not remade while held (img_storage_upgrade)
    // Content from guest storage (vka_tex.c storage_mode): track = re-uploaded when the CPU writes its pages (write-protected after each upload, nvn_dirty.c),
    // stamp = the dirty-page generation taken before the last upload, span = the tracked pool range that holds the storage
    int track, wb_pending; uint64_t stamp; const void *span;
    uint32_t tiled_wb_levels;   // PEANUTS: render-written mips pending block-linear write-back
    uint64_t wb_fence;       // the fence of the submission that holds the last copy of its GPU content back into its storage (vka_tex_writeback; 0: none): vka_wbfollow.h
    int wbf_waiting;         // the prepare's last scan found it waiting for that copy-back; a scan that only runs for waiting images visits just these (wbf_visit)
    int wt;                  // the game writes it through WriteTexels (vka_tex.c mtl_tex_write_region): never tracked again
    uint64_t edge_h[2]; int edged;
    uint64_t hash;           // DM_VK_NANSCAN: a hash of the storage at the last upload (vka_tex_id_check)
    uint64_t gw_seen; int from_mem; uint64_t head, up_fence; uint8_t *snap;   // head, up_fence: DM_VK_NANSCAN's storage watch   // the newest GPU write into guest memory its last upload included (vka_tex.c gw_t); content from guest storage (ST_TRACK / ST_FILL)
    // In a sparse virtual pool (hle_nvn.c nvn_sparse_*): the storage is read through the pool's 4 KB page table, never at mem (unmapped pages there are inaccessible);
    // vtrack = re-uploaded when a mapping in its range or a physical page behind it changes, vgen = the page table's generation at the last upload
    const void *vpool; int vtrack; uint64_t vgen;
    int vbind;               // a GPU-written sparse texture's residency image (vka_tex.c sparse_gpu): its tiles follow the mappings (sparse_bind), nothing uploaded
    // base: the texture's finer levels left out of the image (vka_tex.c sparse_base): image level 0 is the texture's level base, made only of the levels where pages are mapped.
    // Texture levels in views, render targets and copies go through vka_img_lv
    // vmin: the texture level its sampled views start at (vka_tex.c sparse_base: the finest level from which every level is wholly mapped)
    uint32_t base, vmin;
    // A level child (vka_tex.c level_child): a texture whose storage is levels lvk.. of par's (nvn_tex_level_of), seen through par's image: its VkImage, geometry and levels are
    // par's, its texture level j is the image's level j + lvk (vka_img_lv, view_of), and it has no memory or upload of its own. nkids: a parent's live children (it is kept and not remade)
    struct vka_img *par; uint32_t lvk, nkids;
    // A sparse residency image (vka_sparse.c): memory only behind its mapped 64 KB tiles (rs_tw x rs_th texels), va bytes of the sparse address space; bfloor: the finest level it
    // may start at (levels left out to fit the address space, vka_tex.c img_create), a hint kept while vks_va_free() is less than bneed (the address space the next finer start asked for when it did not fit)
    struct vks_img *rs; uint32_t rs_tw, rs_th, bfloor; uint64_t va, bneed;
    void *views;             // the image views made of it (vka_view)
    vkfl fl;                 // its format list (vka_fmtlist.h, vka_tex.c img_create): the formats its views may take besides its own; taken whole when the image is remade in place
    uint8_t mode;            // its content at first use (vka_tex.c ST_ZERO / ST_FILL / ST_TRACK)
    uint8_t pool_fit;        // its memory requirements fit its storage in the pool (vka_tex_alias_stats): type, size and alignment
    // pool: the texture's pool at the image's creation, pgen its Initialize serial then (hle_nvn.c nvn_pool_live; 0: none or not live); pfin: nvn_pool_fin at the last check;
    // pdead: that pool was finalized since (vka_tex.c img_pool_dead): its storage is not read again
    const void *pool; uint64_t pgen, pfin; int pdead;
    uint8_t dcap;            // a sparse texture's dense image whose finer levels DM_VK_SPARSE_DENSE_MB left out (vka_tex.c dense_floor)
    struct vka_img *zold;    // the zero-shared image it left while a holder had it (vka_tex.c img_own): destroyed once holds drop to 0
    // Large images (DM_VK_BIGIMG_MB, vka_tex.c "Large images"): draws that bound it for sampling, other textures found over its storage (the first serials)
    uint32_t nsamp, novl; uint64_t ovl[4];
    // A soft texture's image (vka_soft.h, profile tex_soft): base 1, its finest level left out; soft_saved: the storage bytes that level takes (the stats' soft line)
    uint8_t soft; uint64_t soft_saved;
    // The texture cache's own bookkeeping (vka_tex.c "Live images"): its place in the live list + 1 (0: not in it), the next live image in its storage address's chain,
    // and the video pool registrations count (dm_codec_video_epoch) at which it was last checked for lying in a video pool
    int live_ix; struct vka_img *mnext; uint64_t vep;
    // PEANUTS: resolution scale (vka_resscale.inc): rsc: its VkImage is DM_RES_SCALE times the texture's size (w, h are the image's, d.width / d.height the texture's);
    // rs_no: it is not to be scaled (what it is, or its shape was denied): not asked again
    uint8_t rsc, rs_no;
    // PEANUTS: region writes queued for it and not yet copied (vka_tex.c mtl_tex_write_region; each holds a reference): it is not evicted meanwhile, and a pool drop
    // takes them back (rw_cancel_locked), since both give the image up whatever its reference count says
    uint32_t rw_pend;
    // PEANUTS: texture readback for the companion runtime (vka_readback.inc), under the texture cache's lock; all 0 until a companion first asks for a texture:
    // rb_gen: raised at each GPU write, rb_fence: the fence of the batch that holds the last one; rb_pgen, rb_pfence: the same of the batch before (rb_pok: there is
    // one); rb_rt: drawn into or cleared as a colour target, first in frame rb_frame; rb_redrawn: and again in a later frame; rb_srgb: the view it was last drawn
    // through (1 linear, 2 sRGB); rb_holds: how many of its holds are a readback's (they read no field of it unlocked: img_storage_upgrade does not count them)
    uint64_t rb_gen, rb_fence, rb_pgen, rb_pfence, rb_frame; uint8_t rb_rt, rb_redrawn, rb_pok, rb_srgb; uint16_t rb_holds;
    uint32_t smap_n;   // PEANUTS: its entries in the serial map (vka_smap.h vksm_del_im), under g_tmu
} vka_img;
// PEANUTS begin: resolution scale (DM_RES_SCALE, restart only): vka_resscale.h the arithmetic, vka_resscale.inc (in vka_tex.c) the images and what the rest is for
#include "vka_resscale.h"
extern uint32_t vka_rs_q;   // the factor in quarters (VKRS_ONE = 1x: off), set once by vka_rs_init before anything is replayed
static inline int vka_rs_on(void) { return vka_rs_q != VKRS_ONE; }
// Why a scaled image went back to 1x (counted; the first 16 of each logged)
enum { RS_WHY_PASS_MIX, RS_WHY_B2T, RS_WHY_T2B, RS_WHY_STORAGE, RS_WHY_COPY_KIND, RS_WHY_RESOLVE, RS_WHY_FETCH_MIX, RS_WHY_STANDIN, RS_WHY_UPLOAD, RS_WHY_N };
// The counts of the `res scale:` statistics line (written by the recording thread, or under the texture cache's lock; read for the line without either)
typedef struct {
    uint64_t up, up_no_mem, busy, down[RS_WHY_N];   // images scaled; not scaled for their size; a change put off because a holder read the image; scaled down, by reason
    uint64_t skip;                                   // work left out once because a change of scale could not be made then (a draw, a copy, a clear, a binding)
    uint64_t conv;                                   // texture copies between a scaled and an unscaled image, done as a converting blit
    uint64_t pass_s, pass_1, pass_forced;            // passes over scaled targets; over targets at 1x; of those, ones with a target that could have been scaled
    uint64_t fd_all, fd_none, fd_mixed;              // draws and dispatches of a shader that fetches by pixel: all its 2D textures scaled, none, some
    uint64_t deny_full;
    uint64_t ctx_key;                                // the shader of the draw being recorded, for a scale-down's log line
} vka_rs_stat;
extern vka_rs_stat vka_rs_st;
extern _Atomic uint32_t vka_rs_sh[3];   // shaders made while the scale is on: all, with the fetch helper, with a pixel fetch or size query and no helper (vka_shader.c)
extern pthread_mutex_t vka_rs_mu;       // a holder outside the recording thread reads a held image's size, scale and handle under it; a change of scale only tries it
void vka_rs_init(void);
int vka_tex_rs_can(vka_img *im);                                  // im could be scaled now
int vka_tex_rs_up(vka_img *im, VkCommandBuffer cb);               // 1 = scaled (now or already), 0 = not and not later, -1 = not this time; no render pass open in cb
int vka_tex_rs_down(vka_img *im, VkCommandBuffer cb, int why);    // 1 = at 1x (now or already), 0 = it could not be made 1x now; no render pass open in cb
void vka_rs_stats(char *b, size_t n, double frames);
int vka_rp_rs(const p_set_rt *rt, int has_rt);                    // vka_draw.c: the targets brought to one scale: 1 scaled, 0 at 1x, -1 not possible this time
// A rectangle of the texture's level 0 as im's pixels (unchanged for an unscaled image); 0 = nothing of it lies inside
static inline int vka_rs_rect(const vka_img *im, int32_t *x, int32_t *y, int32_t *w, int32_t *h) {
    if (!im->rsc) return 1;
    return vkrs_region(x, w, (uint32_t)im->d.width, vka_rs_q) && vkrs_region(y, h, (uint32_t)im->d.height, vka_rs_q);
}
// PEANUTS end
// Texture level lv as a level of im (a sparse or soft texture's image starts at level im->base): 0 when the image does not hold it
static inline int vka_img_lv(const vka_img *im, uint32_t *lv) { if (!vksoft_lv(im->base, im->levels - im->lvk, lv)) return 0; *lv += im->lvk; return 1; }   // (a level child's: + lvk)
// The layers a render target view of level lv can name: a 3D texture's slices at that level (drawn into through 2D / 2D-array views, VK_IMAGE_CREATE_2D_ARRAY_COMPATIBLE_BIT), else the array layers
static inline uint32_t vka_img_slices(const vka_img *im, uint32_t lv) { return im->d.target == NVN_TEXTURE_TARGET_3D ? (im->depth >> lv ? im->depth >> lv : 1) : im->layers; }
vka_img *vka_tex_get(const tex_t *t, VkCommandBuffer cb);   // the texture's image, made (and filled from guest memory, or cleared) on first use in cb; NULL = no image
vka_img *vka_tex_get_src(const tex_t *t, VkCommandBuffer cb);   // PEANUTS: as vka_tex_get for a copy source (copy_t2t / texture → buffer): tracks CPU writes on first use under tex_dirty_upload
int vka_tex_mirror_mip(const p_copy_t2t *c, vka_img *s, vka_img *d);   // PEANUTS(upstream-candidate): a whole mip copied into both the image and its CPU backing
uint64_t vka_tex_mem(int *n, uint64_t *gpu_written);   // bytes of image memory now: n images, of which gpu_written bytes the GPU wrote (the rest copies pool storage)
void vka_tex_alias_stats(char *b, size_t n);        // image bytes by content (cleared, filled once, tracked) and how many of them could live on their pool storage
void vka_tex_cache_stats(char *b, size_t n);        // the texture cache's limit, images, working set, MemAvailable, PSI and reserve, and the images it made and evicted (vka_texlimit.h)
vka_img *vka_tex_acquire(uint64_t serial);          // a reference that keeps the image alive (the present thread), dropped with vka_tex_drop
void vka_tex_drop(vka_img *im);
int vka_tex_live_refs(vka_img **out, int max);       // references to every live image (the replayer's --dump-gpu), each dropped with vka_tex_drop
vka_img *vka_tex_ref(vka_img *im);                  // one more reference to im (dropped with vka_tex_drop)
void vka_tex_drop_pool(const void *pool);            // drop all images belonging to a finalized memory pool
// The GPU writes im (render target, clear, copy destination, storage image): its content is the GPU's from now on (no re-upload from storage), and a linear texture's level 0
// is copied back into its storage when the submission ends (vka_tex_writeback), where the CPU reads it as it would on the Switch
void vka_img_written_at(vka_img *im, int color_level, const char *file, int line);   // PEANUTS: color_level, the colour target mip written (-1: any other write)
#define vka_img_written(im) vka_img_written_at(im, -1, __FILE_NAME__, __LINE__)   // (the site is logged for a large image's first write, DM_VK_BIGIMG_MB)
#define vka_img_color_written(im, lv) vka_img_written_at(im, (int)(lv), __FILE_NAME__, __LINE__)
// Before a write is recorded into im (an attachment, a clear, a copy destination, a storage image): a zero-shared image gets memory of its own, cleared (vka_tex.c img_own).
// Call it before taking a view of im for the write. Any thread that records (takes the table lock); no render pass may be open in cb unless upload batches are on
void vka_tex_own(vka_img *im, VkCommandBuffer cb);
void vka_lrz_touch(VkCommandBuffer cb, vka_img *cur, int loads);   // PEANUTS: Adreno LRZ across depth buffers (vka_tex.c, DM_VK_LRZ_TOUCH)
// Before a copy out of im, or into its texture level dst_level (-1: a copy out of it): a soft texture (vka_soft.h) the game copies from, or into at its finest level, is made
// whole again from its storage and stays whole (vka_tex.c soft_harden). Any thread that records; no render pass may be open in cb unless upload batches are on
void vka_tex_soft_copy(vka_img *im, int dst_level, VkCommandBuffer cb);
void vka_tex_soft_stats(char *b, size_t n);          // soft textures now: how many, the storage bytes their finest levels take, and how many went whole
void vka_tex_big_stats(char *b, size_t n);           // the largest images the GPU has not written, with what sampled them and what lies over their storage (DM_VK_BIGIMG_MB)
void vka_tex_kind_log(void);                          // log lines: the live images by content and by why the cache keeps them, and the largest (the ctl stats)
int vka_rp_is_open(void);                            // a render pass is open in the replay's command buffer (vka_draw.c)
void vka_tex_writeback(VkCommandBuffer cb);   // mtl_sub_commit, after the last pass
// PEANUTS(upstream-candidate): a buffer -> texture copy's bytes into the texture's block-linear storage, in queue order (vka_tex.c; LEGO CITY UNDERCOVER's GPU defragmenter copies that storage); DM_VK_B2T_MIRROR
int vka_tex_b2t_mirror(VkCommandBuffer cb, const vka_img *im, const tex_t *t, unsigned level, unsigned layer, const NVNcopyRegion *r, VkBuffer sbuf, VkDeviceSize soff, size_t row, size_t span);
// Texture pool reads (vka_tex.c, DM_VK_POOL_FB): the texture IDs shaders read through the pool, reported by the GPU, so their images are made and kept while read
// The bitmap, a storage buffer of 2^20 bits, in a set of its own after the pool's: on Qualcomm's driver a storage buffer in the pool's set, after its 2^20 sampled images,
// is never written (docs/reference/rendering.md "Texture pool reads report nothing on Qualcomm's driver")
#define VKA_POOL_FB_SET 2
#define VKA_POOL_FB_BINDING 0
VkDescriptorSetLayout vka_pfb_layout(void);   // the bitmap's set layout (set VKA_POOL_FB_SET of every pipeline layout), VK_NULL_HANDLE when pool reads are not reported
VkDescriptorSet vka_pfb_set(void);            // the bitmap's set, bound with the pool's set (vka_draw.c sets_push)
unsigned vka_tex_pfb_flags(void);              // 0: off; else the VKA_PFB_* flags of vka_spv_pool_fb for every module made (vka_shader.c's vkCreateShaderModule hook)
void vka_tex_pfb_draw(void);                   // the draw or dispatch being recorded reads the pool (vka_draw.c): this submission copies the bitmap out
void vka_tex_pfb_commit(VkCommandBuffer cb, uint64_t fence);   // the submission's end (vka_exec.c): the bitmap copied to a host slot and cleared
VkImageView vka_view(vka_img *im, VkFormat fmt, VkImageViewType type, VkImageAspectFlags aspect, uint32_t level, uint32_t nlevels, uint32_t layer, uint32_t nlayers, const VkComponentMapping *swz);
void vka_tex_prepare(VkCommandBuffer cb, uint64_t fence);   // PEANUTS: submission recording starts at this fence, including uploads not bound by a draw
void vka_tex_uploads(VkCommandBuffer cb);
VkDescriptorSet vka_bindless_set(void);
uint32_t vka_bindless_tex_slots(void);        // PEANUTS: the pool's sampled-image table length, 0 without the pool set
VkDescriptorSetLayout vka_bindless_layout(void);
void vka_barrier_at(VkCommandBuffer cb, const char *file, int line);   // a full memory barrier (the replay orders every transfer op after the previous one)
void vka_xp_ts(VkCommandBuffer cb, int site);   // DM_VK_XP_TS experiment (vka_tex.c)
#define vka_barrier(cb) vka_barrier_at(cb, __FILE_NAME__, __LINE__)   // the site names the step that ends there in DM_VK_GPUPROF's report (vka_gprof.c)
// The barrier a dispatch leaves pending (vka_draw.c vka_dispatch, "Dispatch order"): NVN orders one dispatch against the next only through the game's own barriers, so the
// replay records the barrier after a dispatch only before other GPU work in the same command buffer (vka_disp_settle). Any full barrier recorded into it meanwhile settles it
extern _Atomic(VkCommandBuffer) vka_disp_pend;
void vka_disp_settle(VkCommandBuffer cb);
void vka_disp_census(uint64_t frame);   // the dispatches since the last call by shader and call site, one log line (vka_exec.c's statistics, every 1,800 frames)
void vka_dobj_census(uint64_t frame);   // DM_VK_DISP_OBJ: the dispatch objects line(s), every DM_VK_DISP_OBJ_EVERY frames (vka_draw.c)
// DM_VK_GPUPROF (vka_gprof.c): GPU timestamps between the steps of a profiled frame
extern int vka_gp_active;
int vka_gp_split(void);   // DM_VK_GPUPROF_SPLIT of the frame being profiled, 0 when none is
void vka_gp_mark(VkCommandBuffer cb, const char *file, int line);
void vka_gp_pass(const char *desc);
void vka_gp_note(char k, uint64_t key, uint64_t pkey, uint64_t work);   // DM_VK_GPUPROF: a draw ('v' / 'm') or dispatch ('c') in the open step
void vka_gp_part(VkCommandBuffer cb);
void vka_gp_sub_begin(VkCommandBuffer cb, uint64_t frame);
void vka_gp_commit(VkCommandBuffer cb, uint64_t fence);
void vka_gp_sub_frame(uint64_t f);
void vka_img_barrier(VkCommandBuffer cb, VkImage img, VkImageAspectFlags aspect, VkImageLayout from, VkImageLayout to);
// NVN2's DLSS stand-in upscalers (vka_dlss.c): mode 2 = FSR 1 with the jitter taken out, 3 = Snapdragon GSR2 3-pass; writes the output rectangle of d (level dl) from
// the input rectangle of s (level sl), outside any render pass. Returns the mode done (3, or 2 when GSR2 lacks depth / motion), 0 = not done (the caller blits).
// sfmt: the input texture's view format (an sRGB texture's decodes; its image may be shared with the UNORM twin); enc: the output texture is sRGB (stored as its UNORM
// twin), so the result is written sRGB-encoded
int vka_dlss_fsr3(VkCommandBuffer cb, vka_img *src, VkFormat sfmt, vka_img *dst, int enc, vka_img *depth, vka_img *motion, const p_dlss_t2t *o);   // vka_fsr3.c: FSR 3.1 for DM_VK_DLSS=4; 0 = not done (the caller falls back to FSR 1)
int vka_dlss_run(VkCommandBuffer cb, const p_dlss *o, int mode, vka_img *s, uint32_t sl, VkFormat sfmt, int sx, int sy, int iw, int ih, vka_img *d, uint32_t dl, int enc, int dx, int dy, int ow, int oh);
int vka_dlss_scale(VkCommandBuffer cb, VkImageView in, int sx, int sy, int iw, int ih, uint32_t tw, uint32_t th, vka_img *d, uint32_t dl, int dx, int dy, int ow, int oh, int enc);   // a scaled copy, sRGB-encoded with enc
int vka_dlss_ready(void);   // the stand-in's pipelines are made (vka_dlss_scale can run)
// The control line "screenshot" (vka_ctl.c): the present copies the window texture's crop into its command buffer, then hands the texels to the PNG writer once that submission is done
void vka_ctl_shot_record(VkCommandBuffer cb, vka_img *im, VkFormat fmt, int cx, int cy, int cw, int ch);
void vka_ctl_shot_done(VkFence fence);
// PEANUTS begin: texture readback for the companion runtime (vka_readback.h the contract, vka_readback.inc in vka_tex.c the work). vka_rb_on: requests queued or in
// flight (the present's hooks run only then); vka_rb_track: GPU writes and colour targets are noted (from the first request on); vka_rb_color: set by a colour
// target's site while vka_rb_track, for the vka_img_written that follows it on the same thread (1: drawn through a linear view, 2: an sRGB one)
extern _Atomic int vka_rb_on, vka_rb_track;
extern _Thread_local uint8_t vka_rb_color;
void vka_rb_poll(VkFence reuse, VkResult waited);      // present thread: after the wait for a frame slot's fence (its result), before the fence is reset
void vka_rb_record(VkCommandBuffer cb);                // present thread: into the present's command buffer, before it ends
void vka_rb_submitted(VkFence fence, int ok);          // present thread: after the present's submission
// PEANUTS end
int vka_png_rgb(const char *path, const uint8_t *rgb, int w, int h);   // vka_ctl.c: an RGB8 picture to a PNG on the caller's thread
const char *vka_ctl_dir(void);                                         // vka_ctl.c: the folder of DM_CTL's file
int vka_swap_rb(void);

VkImageView vka_tex_id_view(int id, VkCommandBuffer cb, uint8_t dim, int storage, VkImageLayout *layout);   // texture ID id as a view of shape dim (DMSH_VK_DIM_*); VK_NULL_HANDLE = none fits
// (storage: 0 sampled, else VKA_VIEW_* of vka_wbfollow.h: VKA_VIEW_STORAGE a storage view the GPU writes, with VKA_VIEW_RO one the shader only reads)
void vka_tex_id_desc(int id, char *b, size_t n);   // texture ID id described for a [RED] line
void vka_tex_id_peek(int id, char *b, size_t n);   // the same with its storage address and its image's (DM_VK_TEX_PEEK)
uint32_t vka_tex_fb_loop_take(void);   // PEANUTS: the attachment aspects (VkImageAspectFlags: colour, depth) the views made since the last call sample at an attached level of the open pass, then cleared (recording thread)
void vka_tex_feedback(vka_img *const *ims, const uint32_t *levels, int n);   // the open pass's attachments and their levels (n 0: none), for vka_tex_id_view
int vka_tex_id_in_pass(int id);   // texture ID id's image is an attachment of the open pass (1 = colour, 2 = depth / stencil, 0 = neither)
int vka_tex_id_in_pass_rec(int id, uint8_t dim);
int vka_tex_id_target(int id);                      // the NVN target texture ID id stands for (its view's), -1 = not registered
int vka_tex_id_buffer(int id, uint64_t *addr, uint32_t *elems, int *format);   // a buffer texture: its storage's guest address, elements and NVN format; 0 = not one
int vka_tex_id_is_buffer(int id);                   // texture ID id was registered as a buffer texture (no lock)
// ---------- Buffer textures (vka_tbuf.c) ----------
VkBufferView vka_tbuf_view(int id, int storage);     // a texel buffer view over buffer texture id's guest memory; VK_NULL_HANDLE = none (logged)
int vka_tbuf_pending(int id, uint8_t dim);           // buffer texture id read as an image of shape dim needs its copy refreshed (outside a render pass: vka_tbuf_refresh)
void vka_tbuf_refresh(int id, uint8_t dim, VkCommandBuffer cb);   // copies buffer texture id's guest memory into its image (outside a render pass)
VkImageView vka_tbuf_image_view(int id, uint8_t dim, VkFormat *actual);   // PEANUTS: on success, optionally returns the copied image's actual view format
void vka_tbuf_wrote(uint64_t addr, uint64_t n);     // GPU work that writes guest memory [addr, addr+n) was recorded (vka_tex.c gw_add): images of buffer textures over it are copied again at their next use
int vka_tex_id_pending(int id);                     // texture ID id is registered and its image is not made yet (its first use records commands)
VkImageView vka_tex_id_view_rec(int id, VkCommandBuffer cb, uint8_t dim, int storage, VkFormat *actual);   // PEANUTS: recording-thread memo; GENERAL layout, actual optionally receives the resolved view format on success
VkImageView vka_tex_id_view_samp_rec(int id, int sid, VkCommandBuffer cb, uint8_t dim, int storage, VkFormat *actual);   // PEANUTS(upstream-candidate): the same through sampler ID sid (0: not known), whose pinned level a view of the pass's own target follows (vka_tex.c fb_view_levels); PEANUTS: actual as above
VkFormat vka_tex_id_vkfmt(int id);   // the format texture ID id is viewed in, not its (possibly shared) image's (vka_tex.c)
VkImageView vka_tex_id_view_bits(int id, VkCommandBuffer cb, VkImageLayout *layout);   // a storage view of its image as R32_UINT (4-byte texels), not written into the bindless table (vka_tex.c)
int vka_tex_id_pending_rec(int id, uint8_t dim);
int vka_tex_id_storage_pending(int id);            // texture ID id's image exists without storage use and would be remade with it at its next storage binding (a draw ends its pass first)
int vka_tex_id_tbuf(int id, int want, int storage, uint64_t *addr, uint64_t *bytes, VkFormat *fmt, uint32_t *texel);   // PEANUTS: a buffer texture's elements as a texel buffer: guest address, bytes, view format (vka_tex.c)
int vka_tex_id_class(int id);                       // the numeric class of texture ID id's view format: 1 uint, 2 sint, 0 other
int vka_tex_id_class_rec(int id);                   // the same, memoized for the recording thread (by the ID's registration version)
VkSampler vka_samp_id_rec(int id, VkFormat view_fmt);   // PEANUTS: direct combined sampler for this view; UNDEFINED = separate sampler; NULL = not registered
VkSampler vka_samp_default(void);                   // the sampler an unbound sampler slot reads with (nearest, clamp to edge)

// ---------- Shaders (vka_shader.c) ----------
// The game's Vulkan shader library, <pack>/shaders/v-<DMSH_VK_CHAIN>/dmsh_vk.lib (DM_VK_SHLIB overrides the path), loaded at the first program the replay resolves.
// A shader is one library entry with its module made; it lives as long as the process
#include "vka_shlib.h"
#include "vka_bind.h"
#include "vka_memu.h"
#define VKA_MOD_TERRAIN_NORMAL 5       // PEANUTS (PR #164): runtime-only single-sample variant; never stored in the shader library or prewarm keys
typedef struct vka_shader {
    uint64_t key; const dmsh_vk_entry *e; const dmsh_vk_bind *b;
    const vka_shlib *lib;               // the library the entry is in (its header's translator answers; its other modules)
    VkShaderModule mods[VKA_MOD_TERRAIN_NORMAL + 1]; // PEANUTS: library and runtime variants; the base is made when the shader is made
    struct vka_vinmod *vin;             // vertex modules with integer-fed inputs retyped, by masks (vka_shader_vin_module)
    struct vka_vinmod *fout;            // pixel modules with float outputs retyped for integer targets, by masks (vka_shader_fout_module)
    vka_span c1; VkDeviceSize c1_len;   // the c1 bytes in static memory (none when c1_len is 0)
    int bad;                            // refused: no module, an unknown binding, an NV-only capability (logged once with its key)
    int gs_fx;                          // a geometry shader's memory writes (vka_spv_gs_claim): above 0, its draws bind a claim buffer at VKA_GS_CLAIM_BINDING
    VkShaderModule gs_claim;            // its module with those writes under the per-primitive claim, made when a pipeline first asks
    VkShaderModule frz[2]; int frz_none;   // a task / mesh module with its specialization constants folded, by GL depth (vka_shader_spec_module); frz_none: it needs none
    struct vka_imgmod *img;             // compute modules with float storage images retyped for integer textures, by masks (vka_shader_img_module)
    struct vka_texmod *tex;             // stage modules with float textures and images retyped for integer textures (vka_shader_tex_module)
    uint32_t inst_bind;                 // an instanced variant: the uniform binding it was made for + 1 (the prewarm list records it with the base key)
    struct vka_gslink *gslink;          // a geometry shader's modules with outputs added for a pixel shader's inputs, by vertex / pixel pair (vka_shader_gs_link_module)
    uint8_t fcomp[8];                   // a pixel shader: the components of each colour output its module can write (vka_spv_fout_comps), for the write mask; all 0xf when not read
    struct vka_memu_sh *memu_d;         // a task / mesh shader drawn through compute: its layout and compute / vertex forms (vka_memu.c), made at its first draw
    uint8_t memu;                       // such a form itself: 1 a task / mesh shader as compute (the arena, records and push constant in its layout), 2 a generated vertex shader (the arena)
    uint64_t chash;                     // its library entry's hash (vka_shlib_entry_hash), for the pipeline cache's stamp (vka_pso.c); 0 = not in the library
    uint8_t rs_fetch;                   // PEANUTS: it reads the support buffer's render_scale[1 + i] (a fetch by pixel or a size query of a 2D texture: vka_resscale.h vkrs_spv_scan), read only while DM_RES_SCALE is on
} vka_shader;
// An instanced variant's key (vka_draw.c's instanced runs): its base shader's key XOR this. It is no key of the shader library, and the variant depends on the uniform
// binding it was made for, which a pipeline key does not record: the prewarm list records the base key and the binding instead (pwrec.inst)
#define VKA_INST_KEY_XOR 0x494e5354494e5354ull
static inline int vka_shader_is_variant(const vka_shader *s) { return s && s->e && s->key != s->e->key; }
vka_shader *vka_inst_variant(vka_shader *s, uint32_t binding);   // the instanced variant of s for that binding (vka_draw.c), for the prewarm threads; NULL with DM_VK_INSTANCE off
int vka_shader_variant_key(uint64_t key);   // 1 when a key is no library key but an instanced variant's (an older prewarm list's record), without a red line
typedef struct { vka_shader *sh[VKA_NSTAGE]; unsigned bad; } vka_progsh;   // by NVN stage; bad: stages whose shader is refused (bit = NVN stage) or VKA_BAD_MISSING (their draws are skipped)
#define VKA_BAD_MISSING (1u << VKA_NSTAGE)   // vka_progsh.bad: a stage's shader is not in the library (its NVN stage unknown)
int vka_prog_shaders(const prog_t *p, vka_progsh *out);      // 1 = resolved (stages the program lacks are NULL); 0 = no library or no program
void vka_pso_prefetch(const uint8_t md[6][8], unsigned n);   // PEANUTS: a program's shader parts made from now (vka_pso.c pf_main), from nvnProgramSetShaders
int vka_shaders_by_keys(const uint8_t md[6][8], unsigned n, vka_shader *sh[VKA_NSTAGE]);   // PEANUTS: (vka_shader.c)
void vka_pso_prefetch_line(char *out, size_t n);   // PEANUTS
void vka_tex_bindless_ready(void);   // PEANUTS: (vka_tex.c) before a pipeline layout is made
size_t vka_shader_drop_modules(void);   // PEANUTS: (vka_shader.c) the end of the preparing step
void vka_prog_put(uint64_t serial, const vka_progsh *ps);   // a program made by the host itself (the selftest), by serial
vka_shader *vka_shader_make(const vka_shlib *L, uint64_t key);
const char *vka_shader_lib_path(void);   // the library file loaded ("" before or without one): this version's, or an older one of the same ABI or of a compatible older one (vka_abi_compat.h)
uint64_t vka_shader_lib_id(void);        // the library played, as a hash of its header (loaded first if needed); 0 = none (the pipeline cache file's stamp, vka_pso.c)
#include "vka_pckeep.h"
uint64_t vka_shader_content(uint64_t key);           // key's entry in the game's library as a hash of its bytes (vka_shlib_entry_hash), 0 = not there (the cache stamp, vka_pckeep.h)
vka_pck_ent *vka_shader_made(size_t *n);             // the game library's shaders this run made, with their entry hashes (malloc'd; the cache stamp)
unsigned vka_shader_lib_differs(void);   // what the loaded library's modules do otherwise than this host's ABI (vka_abi_compat.h VKA_ABI_*; 0 = nothing, or none loaded yet)
int vka_shader_lib_has(uint64_t key);       // key is an entry of the game's library
int vka_shader_lib_stage_keys(int stage, uint64_t *out, int max);   // PEANUTS: the library's keys of one stage (count; -1 no library)
int vka_shader_memu_key(uint64_t key);      // key is no library entry but a task / mesh shader's compute form or vertex shader (vka_memu.h VKA_MEMU_KEY_*: a run through compute)
uint32_t *vka_shader_memu_words(vka_shader *s, size_t *nb, char *err, size_t errn);   // a task / mesh shader's words for its compute form (vka_memu.c), malloc'd
vka_shader *vka_shader_by_key(uint64_t key);   // the game library's shader of key (the library loaded first if needed); NULL = not there or refused
VkShaderModule vka_shader_module(vka_shader *s, int variant);
vka_shader *vka_shader_remap(vka_shader *s, uint64_t map);   // a pixel shader with its inputs moved / made constant for another mesh module (vka_spv_input_remap), NULL = refused
void vka_shader_pfb_hook(void);               // vka_dev.c, once the device's functions are loaded: modules made from then on report their pool reads (vka_tex_pfb_flags)
VkShaderModule vka_shader_pfb_pick(VkShaderModule m, int a2c, int alpha_func);   // a pixel module given early tests, or its twin without them for a pipeline that needs late ones
uint32_t vka_shader_sg_pin(int stage);              // DMSH_VK_STAGE_*: the subgroup size its subgroup shaders' pipelines require (vka_shader.c sg_plan), 0 = none
VkShaderModule vka_shader_cs_module(vka_shader *s);  // a compute shader's module: its DMSH_VK_MOD_SHUFFLE_LDS one where the device takes it (vka_shader.c shuffle_plan) and the shader does not run as one-invocation subgroups, else the base
VkShaderModule vka_shader_spec_module(vka_shader *s, int gl);   // a task / mesh module with its specialization constants folded (GL depth = gl), VK_NULL_HANDLE = not needed
VkShaderModule vka_shader_img_module(vka_shader *s, uint64_t umask, uint64_t smask);   // a compute module with the float storage images at bindings umask / smask as uint / int (vka_spv_img_retype); VK_NULL_HANDLE = cannot
VkShaderModule vka_shader_inst_module(vka_shader *s, uint32_t binding, uint32_t loc);   // an instanced variant of the base module (vka_spv_instance), made each call
VkShaderModule vka_shader_gs_module(vka_shader *s);   // a geometry shader's module with its memory writes made once per primitive (vka_spv_gs_claim)
VkShaderModule vka_shader_gs_link_module(vka_shader *gs, vka_shader *vs, vka_shader *fs, int claim);   // with the pixel inputs it does not write added (VK_NULL_HANDLE: none to add)
#define VKA_GS_CLAIM_BINDING (DMSH_VK_STAGE_GS * DMSH_VK_STAGE_BINDINGS + DMSH_VK_STAGE_BINDINGS - 1)   // set 0: the claim words of such a shader (the stage's last binding)
VkShaderModule vka_shader_fout_module(vka_shader *s, uint32_t umask, uint32_t smask);
typedef struct { int variant; uint32_t vin_u, vin_s, fout_u, fout_s; int frz; uint64_t tex_u, tex_s; } vka_texmod_key;   // variant: DMSH_VK_MOD_*; frz: -1 or the folded GL depth
VkShaderModule vka_shader_tex_module(vka_shader *s, const vka_texmod_key *k);   // that stage module (vin / fout retypes, the fold) with its textures at tex_u / tex_s retyped; VK_NULL_HANDLE = refused (logged)
int vka_shader_tex_range(const vka_shader *s);   // the stage's binding range (set 0 bindings range * DMSH_VK_STAGE_BINDINGS + n)
int vka_shader_fout_retyped(vka_shader *s, uint32_t umask, uint32_t smask);   // 1 when that module is a rewritten one (not the base module it falls back to)   // the base pixel module with those float outputs as uint / int vectors of their bits (vka_spv_fout_retype), made once per masks
#define VKA_FS_RETYPE 4                  // vka_gkey.fs_variant beyond the library's pixel DMSH_VK_MOD_* (4 is DMSH_VK_MOD_SHUFFLE_LDS of compute modules, never a pixel variant): the base pixel module with its float outputs at integer attachments retyped (vka_pso.c fout_masks)
VkShaderModule vka_shader_vin_module(vka_shader *s, uint32_t umask, uint32_t smask);   // the base module with those float inputs as uvec4 / ivec4 (vka_spv_vin_retype), made once per masks   // the module of that variant (DMSH_VK_MOD_*), made on first use; VK_NULL_HANDLE = the entry has none   // the shader of key in library L (the game's, or the selftest's own); NULL = not there or refused
int vka_shlib_check_all(const vka_shlib *L);   // every module and compute pipeline of L made (DM_VK_SHLIB_CHECK=1): the failures
int vka_static(VkDeviceSize n, VkDeviceSize align, vka_span *out);   // host-visible memory for the process's life (c1 bytes, the support and zero buffers)

// ---------- Pipelines (vka_pso.c) ----------
#include "vka_gkey.h"   // vka_gkey: what a graphics pipeline is made from beyond its shaders and layout (Vulkan-free: the prewarm list's unit test reads it)
typedef struct { VkPipelineLayout layout; VkDescriptorSetLayout set0; int push; uint32_t ndesc; } vka_layout;
int vka_layout_of(vka_shader *const sh[VKA_NSTAGE], int compute, vka_layout *out);   // the set-0 layout of these stages' records with the pools' set 1
VkPipeline vka_gfx_pipeline(vka_shader *const sh[VKA_NSTAGE], const vka_layout *lay, const vka_gkey *k);
VkPipeline vka_gfx_pipeline_now(vka_shader *const sh[VKA_NSTAGE], int why, long long count, uint32_t w, uint32_t h);   // PEANUTS: the last lookup's pipeline made on the frame path when a background thread was to make it (DM_VK_PSO_ASYNC; vka_psosync.h)
VkPipeline vka_cs_pipeline(vka_shader *cs, const vka_layout *lay);
int vka_pso_last_pending(void);   // PEANUTS: the frame path's last lookup returned no pipeline because a background thread is to make it (DM_VK_PSO_ASYNC), not a refusal
int vka_pso_waiting(void);        // PEANUTS: pipelines the asynchronous threads have queued or are making
VkPipeline vka_cs_pipeline_frame(vka_shader *cs, const vka_layout *lay);   // a dispatch's: VK_NULL_HANDLE while the asynchronous threads make it (DM_VK_CS_ASYNC), the dispatch then skipped
VkPipeline vka_cs_pipeline_img(vka_shader *cs, const vka_layout *lay, uint64_t umask, uint64_t smask);   // with vka_shader_img_module's module; VK_NULL_HANDLE = none (then the base one)
void vka_pso_save(int force);         // the VkPipelineCache and the prewarm list to the app's files (DM_TEMP): due DM_VK_PCACHE_SAVE_S after the last save, on the saver thread; force 1 = now, on the caller's; 2 = after a slow pipeline (due DM_VK_PCACHE_SAVE_SLOW_S)
void vka_pso_waits(uint64_t *save_ns, uint64_t *pw_wait_ns);   // cumulative: time saving the cache (saver thread), the frame path's waits for a prewarm thread making the same pipeline
double vka_gpu_span_ms(uint64_t f);   // frame f's submissions from first part's start to last part's end (vka_exec.c); -1 = not in yet

// ---------- Draws and dispatches (vka_draw.c) ----------
// The NVN state a queue keeps between submissions, as nvn_mtl.m's S (state_reset gives NVN's defaults)
typedef struct {
    vka_bstate b;
    const prog_t *bst[VKA_NSTAGE], *cprog;   // the program bound per graphics stage (NVN stage bits; [5] unused, [6] / [7] NVN2's task and mesh bits), the compute program
    blend_st blend[8]; color_st color; chmask_st chmask; ms_st ms; poly_st poly; ds_st ds;
    va_st attr[16]; int nattr; vs_st stream[16]; int nstream; NVNbufferRange vbuf[16];
    uint32_t st_vmask[2], st_wmask[2], st_ref[2];
    p_poly_offset po; float vp[16][4]; float dr[2]; int has_vp, depth_clamp; uint8_t vp_sw[16][4];
    float blend_color[4], alpha_ref;
    p_prim_restart restart; int rast_discard, tf_on;
    int patch;                         // PEANUTS(upstream-candidate): nvnCommandBufferSetPatchSize (OP_PATCH_SIZE); NVN's default 3
    p_tf_bind tf[4];                   // PEANUTS: the transform feedback buffers bound (OP_TF_BIND), for vka_tf.c's captures
    int inited;
    uint32_t bind_gen;                 // raised by every state record but a uniform buffer bind (vka_draw.c's instanced runs: a draw that joins without its walk)
    uint32_t ubo_dirty[VKA_NSTAGE];    // uniform buffer slots bound since the open run's last draw, a bit per slot
    // Stamps (vka_stamp: unique across every queue's state) of what a state record last changed, so a draw can tell an input it derived from is unchanged without comparing
    // it: the vertex attribute formats and streams, the vertex buffers, each stage's storage buffers, its texture / sampler / image handles, the pushed constants
    uint64_t s_vfmt, s_vbuf, s_ssbo[VKA_NSTAGE], s_tex[VKA_NSTAGE], s_push;
} vka_dstate;
uint64_t vka_stamp(void);   // a new stamp, never 0 and never given twice
void vka_dstate_reset(vka_dstate *d);
int vka_classic_rp(void);   // DM_VK_CLASSIC_RP: the game's passes as VkRenderPasses with imageless framebuffers (vka_draw.c)
VkRenderPass vka_rp_compat(const VkFormat *cf, uint32_t n, VkFormat df, VkFormat sf, uint32_t samples);   // a render pass pipelines of those targets are made against
int vka_state_op(vka_dstate *d, uint32_t op, const void *a);   // 1 = a state record, taken
// The render pass the draws of a submission go into: opened at a draw, closed by anything that is not a draw or state (vka_rp_end), and by a new SET_RT
void vka_draw_sub_begin(VkCommandBuffer cb, unsigned slot);    // the slot's per-draw descriptor sets are free again (its last fence is done)
void vka_rp_end(void);
int vka_rp_split_ok(const p_set_rt *rt, int has_rt);
void vka_draw_cb_switch(VkCommandBuffer cb);
void vka_dyn_invalidate(void);                       // the draws' dynamic state is set again in full at the next draw (another pipeline was bound)
int vka_pass_keep(void);                             // DM_VK_PASS_KEEP=0: a SET_RT ends the pass, as before
void vka_rp_set_rt(const p_set_rt *rt);              // OP_SET_RT while a pass stays open: notes a continuation of the same targets
int vka_rp_ub_defer(uint64_t dst, uint64_t n, const void *data);   // OP_UPDATE_UB while a pass is open: 1 = kept for the pass's UBOs and flushed at vka_rp_end
int vka_rp_open_for(const p_set_rt *rt, int has_rt);   // a pass is open over these targets
int vka_rp_on(void);                                 // a pass is open
int vka_mesh_on(void);                               // VK_EXT_mesh_shader is on (and its draws are not drawn through compute)
int vka_cpu_read(uint64_t addr, void *out, size_t n);   // guest bytes as the CPU sees them now (1 = read)
int vka_mdi_gpu(int indexed, int prim, uint32_t n, uint32_t stride);   // 1 = this indirect-count draw can issue all n records as one native multi-draw   // PEANUTS: bounded draw ranges
int vka_mdx_args(uint64_t ind, VkBuffer *b, VkDeviceSize *o);   // an indirect mesh draw's {x, y, z} arguments: its record's {count, 1, 1} copy (vka_exec.c)
int vka_rp_depth(uint32_t *w, uint32_t *h);   // the open pass has a depth / stencil attachment (*w, *h: its render area)
void vka_rp_zstats(char *b, size_t n);   // DM_VK_PARTLOG: the depth state of the part's draws into depth targets, then reset (vka_draw.c)
void vka_rp_note_ts(void);                 // a timestamp recorded (inside the open pass or not: counted when one is open)
int vka_rp_game_barrier(uint32_t bits);                // a game barrier (OP_BARRIER): 1 = taken inside the open pass (DM_VK_BAR_KEEP), 0 = the caller ends the pass and records a full barrier
void vka_rp_bar_stats(char *b, size_t n, double frames);
void vka_rp_prep_clear(const p_set_rt *rt, int has_rt); // before a clear of these targets: a pass or pending clears of other targets end
// A clear of a bound target: everything the clear outside a pass needs (vka_clear_outside, vka_exec.c), and the value for the attachment inside one
typedef struct { int ci; vka_img *im; VkFormat fmt; VkImageAspectFlags asp; uint32_t lv, l0, nl; VkRect2D rc; int whole; VkClearValue out, in; } vka_pclear;
void vka_clear_outside(const vka_pclear *c);         // the clear on the image, outside any pass, then a barrier
// A clear of bound target c->ci (< 0: depth / stencil) into the render pass over those targets: an open one takes it as vkCmdClearAttachments; with none open it waits for
// the next draw's pass (loadOp CLEAR), or is done outside when anything else comes first (vka_rp_end). 0 = not taken: the caller ends the pass and clears outside
int vka_rp_clear(const p_set_rt *rt, int has_rt, const vka_dstate *d, const vka_pclear *c);
void vka_draw(vka_dstate *d, const p_set_rt *rt, int has_rt, const p_scissor *sc, int has_sc, const p_draw *dw, const p_draw_idx *di);
void vka_dispatch(vka_dstate *d, const p_dispatch *dp);
// Whether the game is a Switch 2 one (NVN2: its compute reads the group counts at c0 + DMSH_C0_DISPATCH_NVN2): dm_sdk_ounce() unless set (the replayer sets it from the capture)
int vka_nvn2(void);
void vka_set_nvn2(int on);
void vka_draw_force(int on);   // draws on whatever the memory (the selftest, whose memory may be a mapped allocation)
// DM_VK_NANSCAN (vka_nanscan.c): NaN / infinite texels in each pass's float attachments, logged with the pass's shaders
int vka_nan_on(void);
// The command encoder thread (vka_enc.c, DM_VK_ENC): the replay thread's vkCmd* calls into its command buffer, made on a thread of their own
void vka_enc_install(void);                 // vka_dev.c: the vk table's vkCmd* entries go through the encoder
// The game's barriers between compute dispatches (vka_gbar.c): a compute-to-compute barrier instead of a full one where only dispatches follow (DM_VK_BAR_NARROW)
void vka_gbar_install(void);                // vka_dev.c, after vka_enc_install: the vk table's work commands go through it
void vka_gbar_cb(VkCommandBuffer cb);       // the replay's command buffer (a submission's, or a part's)
void vka_gbar_settle(VkCommandBuffer cb);   // before it ends: what is pending, recorded
int vka_gbar_game(VkCommandBuffer cb, uint32_t bits);   // a game barrier outside a draw pass: 1 = taken, 0 = record the full barrier
void vka_gbar_stats(char *b, size_t n, double frames);
void vka_enc_start(VkCommandBuffer cb);     // after vkBeginCommandBuffer on the replay thread: its calls into cb are deferred (when DM_VK_ENC is on)
void vka_enc_stop(void);                    // before vkEndCommandBuffer / a submit / a pool use: every deferred call made, none deferred from now on
void vka_enc_sync(void);                    // every deferred call made (a direct call that must land in order follows)
int vka_enc_on(void);
int vka_enc_call(void (*fn)(const void *), const void *arg, size_t n);   // fn on the encoder thread in order (1), or 0: encoder off
void vka_enc_switch(VkCommandBuffer cb);    // recording goes on in cb (queued reset / begin)
void vka_enc_stats(char *b, size_t n, double frames);   // the replay statistics' line (empty when unused)
int vka_nan_split(vka_img *const *ims, int n);
int vka_nan_probe(uint64_t fs);
void vka_nan_inputs(VkCommandBuffer cb, vka_img *const *ims, const int *binds, const int *tids, int n, uint64_t stats);   // stats: DM_VK_TEX_STATS's fragment shader (level 0 scanned, every input logged), 0 = NaN scan
void vka_rtlog_skip(const p_set_rt *rt, int has_rt, const vka_dstate *d, const char *why);   // DM_VK_RT_LOG: a draw not recorded into a logged pass
int vka_texstats_on(void);   // DM_VK_TEX_STATS (live) is set: the encoder thread stays off
int vka_texstats_probe(uint64_t fs);   // 1 = scan this draw's inputs (DM_VK_TEX_STATS)
int vka_texstats_room(void);   // 1 = a `tex stats:` value line may be logged (counts it)
int vka_texstats_desc_new(uint64_t fs, int bind, int tid);   // 1 = log this input's description (first time for this value of DM_VK_TEX_STATS)
int vka_texstats_table_new(int tid);   // 1 = scan this texture (DM_VK_TEX_STATS): not yet scanned this frame
vka_img *vka_tex_id_img(int id, VkCommandBuffer cb);
void vka_tex_id_dump(int id);
void vka_tex_id_check(int id);                      // DM_VK_NANSCAN: has texture ID id's storage changed since its upload, and does the tracker know
// GPU writes into guest memory, as recorded (vka_tex.c gw_t): textures over them are uploaded with their bytes, or again once they are done
void vka_tex_gw_copy(uint64_t src, uint64_t dst, uint64_t n);
void vka_tex_gw_fill(uint64_t dst, uint64_t n, uint32_t value);
void vka_tex_gw_update(uint64_t dst, uint64_t n, const void *data);
void vka_tex_gw_opaque(uint64_t dst, uint64_t n);   // bytes the host does not know at recording (shader stores, texture → buffer copies)
void vka_tex_gw_tbuf(uint64_t dst, uint64_t n);   // PEANUTS: an opaque write made by stores through a storage texel buffer (vka_tbuf.h)
uint64_t vka_tex_gw_seq(void);   // PEANUTS: the newest GPU write's sequence number
int vka_tex_gw_since(uint64_t addr, uint64_t n, uint64_t seq);   // PEANUTS: a GPU write recorded after seq overlaps the range
int vka_tex_gw_tbuf_pending(uint64_t addr, uint64_t n);   // PEANUTS: 1 = such a write over [addr, addr + n) is not done yet: the CPU does not have its bytes
int vka_tex_gw_read(uint64_t addr, uint64_t n, void *out);   // [addr, addr + n) as the GPU sees it now (guest RAM + pending gw_t writes); out NULL = only whether known (no opaque write)
int vka_tex_gml_take(VkDescriptorBufferInfo *bi);  // a raw global memory write log for one draw or dispatch, read when its submission is done (0 = none free)
void vka_tex_gw_commit(uint64_t fence);
void vka_tex_gw_part(void);   // PEANUTS(upstream-candidate): a part of the submission goes to the queue: the GPU writes recorded so far are on it (vka_tex.c gw_src_overwritten)
void vka_tex_watch_dispatch(uint64_t key);         // DM_VK_NANSCAN: compute shaders by submission, for the storage watch             // the writes recorded since the last commit belong to this submission
void vka_nan_draw(uint64_t vs, uint64_t fs);
void vka_nan_pass(VkCommandBuffer cb, vka_img *const *ims, const uint32_t *lvs, const uint32_t *l0s, int n);
void vka_draw_peek_commit(uint64_t fence);   // vka_draw.c, DM_VK_SB_PEEK: the storage buffers this submission's draws and dispatches named, logged again at that fence
void vka_rw_pre(VkCommandBuffer cb);         // vka_draw.c, DM_VK_RANGE_WATCH: before a dispatch or transfer (the submission's first takes the bytes before it)
void vka_rw_point(VkCommandBuffer cb, char kind, uint64_t key, uint64_t a, uint64_t b);   // after one: the watched bytes copied for the log
void vka_rw_commit(uint64_t fence);          // the submission's copies, logged once fence is done
void vka_nan_dispatch(VkCommandBuffer cb, uint64_t key, vka_img *const *ims, int n);   // after a dispatch (its writes made visible): its float storage images, as a pass's attachments
void vka_nan_commit(uint64_t fence);
void vka_draw_stats(uint64_t *draws, uint64_t *skipped, uint64_t *dispatches, uint64_t *pipelines);
void vka_draw_run_break(void);   // an instanced run (DM_VK_INSTANCE) is not extended past this point (an occlusion query boundary)
void vka_oq_draw(void);          // vka_exec.c Occlusion queries: a draw about to be recorded in the open pass (begins a query while counting)
void vka_oq_pass_end(void);      // the open pass ends, or a clear is recorded inside it: its query ends
int vka_is_ndve_key(uint64_t k);

// ---------- Present (vka_present.c) ----------
int vka_origin_ll(void);
int vka_queue_origin_ll(void);   // the submitting queue's raster origin (nvnQueueBuilder flags): the winding flips with it
int vka_depth_zero(void);        // nvnDeviceSetDepthMode NEAR_IS_ZERO (else GL's near = -w)
void vka_present_init(void);
// The overlay's counts (vka_fps.c): each image the present thread handed to the swapchain (shown), the game's own or a generated one; frame generation's state
void vka_fps_note(int shown, int generated);
void vka_fps_fg(int state, int mult);
// Frame generation (vka_framegen.c; LSFG in lsfg/, GPL-3.0-or-later): for the game frame about to be presented (its window texture, crop, period, timeline value and NVN format), the images
// to present before it (gen, at most VKA_FG_MAX, the present takes its own references), how long each image of this frame should stay (hold, seconds; 0 = the game's period)
// and the output period for the window's frame rate (0 = frame generation off). Present thread only
enum { VKA_FG_MAX = 3 };
int vka_fg_frame(uint64_t serial, const int *crop, double period, uint64_t fence, int format, vka_img **gen, double *hold, double *out_period);
uint64_t vka_refresh_ns(void);   // vka_present.c: the panel's refresh period in ns from display timing, 0 when not known (present thread)
#include "vka_tf.h"   // PEANUTS: NVN transform feedback (vka_tf.c)
