// PEANUTS: NVN transform feedback on the Vulkan replay (vka_tf.c, vka_tf_spv.c). The first part is plain C with no Vulkan, so the host test
// (dm/host/tests/t_android_tf.c) runs the same code
#pragma once
#include <stddef.h>
#include <stdint.h>

// What a vertex shader's control section says it captures (dm/gfx/tools/dmsh_build.py tf_layout reads the same bytes for the Metal library): for each of the four
// feedback buffers its bytes per vertex and the outputs written there one after the other, each `comp` float components (from x on) of the generic output at Location `loc`
enum { VKA_TF_OUTS = 32 };
typedef struct { uint16_t stride[4]; uint8_t n[4], loc[4][VKA_TF_OUTS], comp[4][VKA_TF_OUTS]; } vka_tf_layout;
// A Maxwell control section's layout: 1 = it captures and out says what, 0 = it captures nothing (or the section is an NVN2 one), -1 = a form this does not know
int vka_tf_layout_read(const uint8_t *ctl, size_t n, vka_tf_layout *out);
// The vertex module w (nw words) made to capture L with VK_EXT_transform_feedback: for each captured output a new Output variable of exactly the captured components
// (a float or a vector, at a Location below max_loc the module does not use, decorated XfbBuffer / XfbStride / Offset), stored from the module's vec4 output before each
// return of its entry point (zeros where the module has no such output); the TransformFeedback capability and the Xfb execution mode. A malloc'd copy (*out_nw words),
// or NULL with the reason in err
uint32_t *vka_spv_xfb(const uint32_t *w, size_t nw, const vka_tf_layout *L, uint32_t max_loc, size_t *out_nw, char *err, size_t errn);

#if defined(__ANDROID__)
#include "vka.h"
// The capture variant of the draw's vertex shader when this draw can be captured (feedback is on in d): a shader of its own key whose modules carry the capture, to draw
// with in place of sh[0], followed by vka_tf_begin / vka_tf_end around the draw call. NULL = not captured, the reason logged once (the caller draws as it did before)
vka_shader *vka_tf_capture(vka_shader *const sh[VKA_NSTAGE], const vka_dstate *d, const p_draw *dw, const p_draw_idx *di);
void vka_tf_begin(VkCommandBuffer cb);   // inside the pass, the capture pipeline bound
void vka_tf_end(VkCommandBuffer cb);     // after the draw call
void vka_tf_flush(VkCommandBuffer cb);   // vka_rp_end, outside the pass: what the pass's draws captured is copied into the game's buffers
void vka_tf_state(const p_tf_state *s, VkCommandBuffer cb);   // vka_exec.c at OP_TF_STATE (the replayed one, not a look-ahead)
void vka_tf_prep(uint64_t key, uint32_t **w, size_t *nb);     // vka_shader.c prep_words: a capture variant's words made to capture
// Smoother next start (vka_pso.c sw_tf_*, learning on): whether v is a capture variant; the layout capture variant v was made with (0: none); the capture variant of
// library vertex shader s made ahead on a prewarm thread with layout L an earlier run noted (NULL: refused; a draw's first use of it checks this run's layout)
int vka_tf_is_variant(const vka_shader *v);
int vka_tf_layout_of(const vka_shader *v, vka_tf_layout *L);
vka_shader *vka_tf_learned(vka_shader *s, const vka_tf_layout *L);
#endif
