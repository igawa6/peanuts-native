// The word the rewritten game code reads its thread slots through: [tpidr_el0 + 0x10] (patch_nso.py --target android; Bionic's TLS_SLOT_APP, dm/host/plat/plat_android.c).
// With glibc on arm64 the thread pointer points at a 16-byte header and the executable's own thread-local block follows it, so the executable's first thread-local variable is
// that same word, as long as the block's alignment is at most 16. This file is linked first and its variable is initialised (.tdata comes before .tbss), which makes it the first.
// main_linux.c checks the address at start; plat_slot_check (plat_android.c) then checks the load the game makes.
__thread void *nx_tls_slot_app __attribute__((tls_model("local-exec"), used, section(".tdata"))) = (void *)0;   // .tdata by name: a zero initialiser would otherwise put it in .tbss, after every initialised variable
void *nx_tls_slot_app_addr(void) { return &nx_tls_slot_app; }
