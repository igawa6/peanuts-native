// The flags of every anonymous mapping inside the guest block (docs/reference/memory.md, "Fixed maps in the guest block never merged with their neighbours").
// Linux merges two neighbouring anonymous mappings only when their flags and names are the same. The block is reserved with MAP_NORESERVE (VM_NORESERVE: address
// space only), so a fixed map made inside it later (a page range given back, a pool's pages given back by the Vulkan replay) must carry MAP_NORESERVE too: without it
// the new mapping has no VM_NORESERVE, and a writable one takes VM_ACCOUNT instead, so it never merged again with the rest of the block, whatever its protection and name.
// Every UnmapPhysicalMemory and every pool give-back then left mappings of their own until vm.max_map_count was reached. The name is set by plat_mem_name_guest.
#pragma once
#include <sys/mman.h>
#define PLAT_GUEST_MAP (MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE)
