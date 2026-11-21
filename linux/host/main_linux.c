// Entry on Linux: checks the thread slot word (tls_slot.c), then runs the Vulkan selftest (--vk-selftest [frames]) or the host (the arguments dmhost takes: main.c)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
struct ANativeWindow;
int nxhost_main(int argc, char **argv);
int nxhost_vk_selftest(struct ANativeWindow *w, int frames);
void *nx_tls_slot_app_addr(void);
struct ANativeWindow *nx_linux_window(void);   // front_sdl.c
void nxhost_set_window(struct ANativeWindow *w);
int nx_linux_entry(int argc, char **argv) {
    void **tp; __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp));
    if ((void *)(tp + 2) != nx_tls_slot_app_addr()) {
        fprintf(stderr, "[RED] thread slot word: tpidr_el0 + 0x10 is %p but nx_tls_slot_app is at %p: another thread-local variable comes first, or the block is aligned above 16\n", (void *)(tp + 2), nx_tls_slot_app_addr());
        return 70;
    }
    if (argc > 1 && !strcmp(argv[1], "--vk-selftest")) {
        int frames = argc > 2 ? atoi(argv[2]) : 60;
        int r = nxhost_vk_selftest(nx_linux_window(), frames);
        fprintf(stderr, "vk selftest: %d\n", r);
        return r;
    }
    struct ANativeWindow *w = nx_linux_window(); if (w) nxhost_set_window(w);
    return nxhost_main(argc, argv);
}
#undef main
int main(int argc, char **argv) { return nx_linux_entry(argc, argv); }
