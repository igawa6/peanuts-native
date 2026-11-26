// PEANUTS: composite-pass upscaler (DM_VK_CUPSCALE): the GPU side. See vka_cupscale.h for what it does; the hook that finds the pass, rewrites the texel-size field and
// substitutes the composite draw's 3D picture is vka_draw.c cup_pre (with stage_writes). Compute passes with push descriptors, the NDSR passes' shaders with an RGBA8 store
// (vka_cupscale_spv.h). Host objects only: the output image is no guest texture (alias sync, gpu_owned, write-back and the texture pool never see it).
// Memory: one RGBA8 image of the output's size (3.7 MB at 1280x720, 8.3 MB at 1920x1080) and, for FSR with RCAS, an RGBA16F intermediate of the same size (7.4 / 16.6 MB);
// made at the first armed composite, made again when the output size changes (the old ones freed once the GPU is past the last submission), kept for the run otherwise.
#include "vka_cupscale.h"
#include "vka_cupscale_spv.h"
#include "game_profile.h"
#include <stdio.h>

// ---------- The pass to watch ----------
// The profile's upscale_pass (game.tsv -> the runtime profile: dm/peanuts/profile/profile_rt.h has_upscale_pass / upscale_pass_vs / _ps / _binding
// macros below). A build without them (an older profile header) has no profile pass: only DM_VK_CUPSCALE_PASS names one
#if defined(DM_GAME_HAS_UPSCALE_PASS) && defined(DM_GAME_UPSCALE_PASS_PS) && defined(DM_GAME_UPSCALE_PASS_VS) && defined(DM_GAME_UPSCALE_PASS_BINDING)
#define CUP_PROFILE(vs, ps, b) ((vs) = (uint64_t)(DM_GAME_UPSCALE_PASS_VS), (ps) = (uint64_t)(DM_GAME_UPSCALE_PASS_PS), (b) = (uint32_t)(DM_GAME_UPSCALE_PASS_BINDING), (DM_GAME_HAS_UPSCALE_PASS) && (ps) != 0)
#else
#define CUP_PROFILE(vs, ps, b) ((void)(vs), (void)(ps), (void)(b), 0)
#endif
int vka_cupscale_pass(vka_cup_pass *p) {   // read again when a switch changes (vka_env_gen): one load and a compare a draw otherwise
    static uint32_t gen = ~0u; static vka_cup_pass cur; static int ok;
    uint32_t g = atomic_load_explicit(&vka_env_gen, memory_order_relaxed);
    if (g != gen) {
        gen = g; ok = 0; memset(&cur, 0, sizeof cur);
        char b[96]; int set = vka_env_str("DM_VK_CUPSCALE_PASS", b, sizeof b) > 0 && b[0];
        if (set && vka_cup_parse_pass(b, &cur.vs, &cur.ps, &cur.binding)) { ok = 1; cur.from_env = 1; }
        else {
            if (set) { static int noted; if (!noted++) VRED("cupscale: DM_VK_CUPSCALE_PASS=%s is not <vs hex>,<ps hex>,<binding>: the profile's pass, if any", b); }
            ok = CUP_PROFILE(cur.vs, cur.ps, cur.binding);
        }
        static int lok = -1; static uint64_t lps;
        if (ok != lok || cur.ps != lps) { lok = ok; lps = cur.ps;
            if (ok) VLOG("cupscale: composite pass vs %016llx ps %016llx binding %u (%s); DM_VK_CUPSCALE: 0 off, 1 the game's bilinear (default), 2 FSR 1, 4 SGSR 1",
                         (unsigned long long)cur.vs, (unsigned long long)cur.ps, cur.binding, cur.from_env ? "DM_VK_CUPSCALE_PASS" : "profile upscale_pass"); }
    }
    if (ok) *p = cur;
    return ok;
}

// ---------- Objects ----------
enum { CP_EASU_MID, CP_EASU, CP_RCAS, CP_SGSR, CP_N };
static struct {
    int state; VkDescriptorSetLayout dsl; VkPipelineLayout lay; VkSampler samp; VkPipeline p[CP_N]; signed char bad[CP_N];
    VkImage out, mid; VkDeviceMemory out_mem, mid_mem; VkImageView out_v, mid_v; uint32_t w, h, mw, mh;
} C;
static int cp_ready(void) {
    if (C.state) return C.state > 0;
    C.state = -1;
    if (!V.push_desc) { VRED("cupscale: no push descriptors: the game's own stretch"); return 0; }
    VkDescriptorSetLayoutBinding b[2] = {{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL}, {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL}};
    VkDescriptorSetLayoutCreateInfo dl = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR, .bindingCount = 2, .pBindings = b};
    VkPushConstantRange pc = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 72};
    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR, .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                              .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .maxLod = 0.25f};
    VkResult r = vk.vkCreateDescriptorSetLayout(V.dev, &dl, NULL, &C.dsl);
    VkPipelineLayoutCreateInfo li = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &C.dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pc};
    if (!r) r = vk.vkCreatePipelineLayout(V.dev, &li, NULL, &C.lay);
    if (!r) r = vk.vkCreateSampler(V.dev, &si, NULL, &C.samp);
    if (r) { VRED("cupscale: layouts: %d: the game's own stretch", r); return 0; }
    C.state = 1; return 1;
}
static VkPipeline cp_pipe(int which) {   // made at first use; specialization constants 0 (SQ, off) and 1 (EASU's KERNEL: 0, EASU)
    if (C.p[which] || C.bad[which]) return C.p[which];
    static const uint32_t *const code[CP_N] = {vka_cup_easu_mid, vka_cup_easu_rgba8, vka_cup_rcas_rgba8, vka_cup_sgsr_rgba8};
    static const size_t size[CP_N] = {sizeof vka_cup_easu_mid, sizeof vka_cup_easu_rgba8, sizeof vka_cup_rcas_rgba8, sizeof vka_cup_sgsr_rgba8};
    static const char *const name[CP_N] = {"EASU (to RGBA16F)", "EASU (to RGBA8)", "RCAS", "SGSR 1"};
    VkShaderModuleCreateInfo mi = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = size[which], .pCode = code[which]}; VkShaderModule m = VK_NULL_HANDLE;
    VkResult r = vk.vkCreateShaderModule(V.dev, &mi, NULL, &m);
    uint32_t sv[2] = {0, 0}; VkSpecializationMapEntry se[2] = {{0, 0, 4}, {1, 4, 4}};   // (an entry for an ID a module lacks is allowed)
    VkSpecializationInfo spi = {2, se, sizeof sv, sv};
    VkComputePipelineCreateInfo ci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, .stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                                      .module = m, .pName = "main", .pSpecializationInfo = &spi}, .layout = C.lay};
    if (!r) r = vk.vkCreateComputePipelines(V.dev, VK_NULL_HANDLE, 1, &ci, NULL, &C.p[which]);
    if (m) vk.vkDestroyShaderModule(V.dev, m, NULL);
    if (r) { C.p[which] = VK_NULL_HANDLE; C.bad[which] = 1; VRED("cupscale: pipeline %s: %d", name[which], r); }
    return C.p[which];
}
static void img_free(void *p) { uint64_t *a = p; vk.vkDestroyImageView(V.dev, (VkImageView)(uintptr_t)a[1], NULL); vk.vkDestroyImage(V.dev, (VkImage)(uintptr_t)a[0], NULL); vk.vkFreeMemory(V.dev, (VkDeviceMemory)(uintptr_t)a[2], NULL); free(p); }
static void img_drop(VkImage *im, VkImageView *v, VkDeviceMemory *m) {   // once the GPU is past the last submission (it may still sample it: the previous frame's composite)
    if (*im || *v || *m) { uint64_t *a = malloc(3 * sizeof *a);
        if (a) { a[0] = (uint64_t)(uintptr_t)*im; a[1] = (uint64_t)(uintptr_t)*v; a[2] = (uint64_t)(uintptr_t)*m; vka_after(vka_fence_last(), img_free, a); } }
    *im = VK_NULL_HANDLE; *v = VK_NULL_HANDLE; *m = VK_NULL_HANDLE;
}
// A storage + sampled image of w x h in format f, in GENERAL from here on (the transition recorded into cb, outside any pass)
static int img_make(VkCommandBuffer cb, VkFormat f, uint32_t w, uint32_t h, VkImage *im, VkImageView *v, VkDeviceMemory *m) {
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D, .format = f, .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT};
    VkResult r = vk.vkCreateImage(V.dev, &ci, NULL, im);
    VkMemoryRequirements mr = {0}; if (!r) vk.vkGetImageMemoryRequirements(V.dev, *im, &mr);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)};
    if (!r && ai.memoryTypeIndex == UINT32_MAX) r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (!r) r = vk.vkAllocateMemory(V.dev, &ai, NULL, m);
    if (!r) r = vk.vkBindImageMemory(V.dev, *im, *m, 0);
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = *im, .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = f, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (!r) r = vk.vkCreateImageView(V.dev, &vi, NULL, v);
    if (r) {   // (nothing of it was used yet: destroyed now)
        if (*v) vk.vkDestroyImageView(V.dev, *v, NULL);
        if (*im) vk.vkDestroyImage(V.dev, *im, NULL);
        if (*m) vk.vkFreeMemory(V.dev, *m, NULL);
        *v = VK_NULL_HANDLE; *im = VK_NULL_HANDLE; *m = VK_NULL_HANDLE;
        VRED_ONCE("cupscale: image %ux%u VkFormat %d: %d", w, h, f, r); return 0;
    }
    vka_img_barrier(cb, *im, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    VLOG("cupscale: image %ux%u VkFormat %d made (%.1f MB)", w, h, f, mr.size / 1048576.0);
    return 1;
}
static uint32_t bits(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }
static void pass(VkCommandBuffer cb, VkPipeline p, VkImageView src, VkImageView dst, const uint32_t pc[18], uint32_t w, uint32_t h) {
    VkDescriptorImageInfo ii[2] = {{C.samp, src, VK_IMAGE_LAYOUT_GENERAL}, {VK_NULL_HANDLE, dst, VK_IMAGE_LAYOUT_GENERAL}};
    VkWriteDescriptorSet wr[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = 0, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &ii[0]},
                                  {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = 1, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &ii[1]}};
    vk.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p);
    vk.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, C.lay, 0, 2, wr);
    vk.vkCmdPushConstants(cb, C.lay, VK_SHADER_STAGE_COMPUTE_BIT, 0, 72, pc);
    vk.vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
}
VkImageView vka_cupscale_run(VkCommandBuffer cb, int mode, VkImageView sv, uint32_t sw, uint32_t sh, uint32_t ow, uint32_t oh, float stops, int *why) {
    *why = VKA_CUP_GPU;
    if (!cb || !sv || !cp_ready()) return VK_NULL_HANDLE;
    int rcas = mode == 2 && stops >= 0;
    VkPipeline p1 = cp_pipe(mode == 4 ? CP_SGSR : rcas ? CP_EASU_MID : CP_EASU), p2 = rcas ? cp_pipe(CP_RCAS) : VK_NULL_HANDLE;
    if (!p1 || (rcas && !p2)) return VK_NULL_HANDLE;
    vka_barrier(cb);   // the 3D picture's pass and any pending dispatch barrier settled; the last frame's composite done sampling the output (WAR)
    if (C.out && (C.w != ow || C.h != oh)) img_drop(&C.out, &C.out_v, &C.out_mem);
    if (!C.out) { if (!img_make(cb, VK_FORMAT_R8G8B8A8_UNORM, ow, oh, &C.out, &C.out_v, &C.out_mem)) return VK_NULL_HANDLE; C.w = ow; C.h = oh; }
    if (rcas && C.mid && (C.mw != ow || C.mh != oh)) img_drop(&C.mid, &C.mid_v, &C.mid_mem);
    if (rcas && !C.mid) { if (!img_make(cb, VK_FORMAT_R16G16B16A16_SFLOAT, ow, oh, &C.mid, &C.mid_v, &C.mid_mem)) return VK_NULL_HANDLE; C.mw = ow; C.mh = oh; }
    uint32_t pc[18] = {0};
    if (mode == 4) {   // SGSR's ViewportInfo, the output size, EdgeSharpness
        float vp[4] = {1.0f / sw, 1.0f / sh, (float)sw, (float)sh};
        for (int i = 0; i < 4; i++) pc[i] = bits(vp[i]);
        pc[4] = ow; pc[5] = oh; pc[6] = bits(vka_cup_sgsr_edge(stops));
        pass(cb, p1, sv, C.out_v, pc, ow, oh);
    } else {   // AMD's FsrEasuCon for the whole picture, then RCAS (FsrRcasCon con0.x)
        float sx = (float)sw / ow, sy = (float)sh / oh, rw = 1.0f / sw, rh = 1.0f / sh;
        float easu[16] = {sx, sy, 0.5f * sx - 0.5f, 0.5f * sy - 0.5f, rw, rh, rw, -rh, -rw, 2 * rh, rw, 2 * rh, 0, 4 * rh, 0, 0};
        for (int i = 0; i < 16; i++) pc[i] = bits(easu[i]);
        pc[16] = ow; pc[17] = oh;
        pass(cb, p1, sv, rcas ? C.mid_v : C.out_v, pc, ow, oh);
        if (rcas) {
            vka_barrier(cb);
            memset(pc, 0, 64); pc[0] = bits(vka_cup_rcas(stops));
            pass(cb, p2, C.mid_v, C.out_v, pc, ow, oh);
        }
    }
    vka_barrier(cb);   // the stores before the composite's samples
    *why = VKA_CUP_ARM;
    return C.out_v;
}
