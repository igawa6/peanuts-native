// glibc's <sys/auxv.h> includes <elf.h>, whose Elf64_* types collide with the host's own (dm/host/dm.h); the host needs only getauxval and the AT_ numbers
#pragma once
#include <linux/auxvec.h>
unsigned long getauxval(unsigned long type);
