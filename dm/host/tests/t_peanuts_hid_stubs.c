// PEANUTS: for building t_peanuts_padgate.c (over t_hid.c) on a Linux PC: three Android-side services hle_hid.c names that the Mac's test build gets elsewhere
#include <stdlib.h>
int plat_sort_stable(void *base, size_t n, size_t size, int (*cmp)(const void *, const void *)) { qsort(base, n, size, cmp); return 0; }
void dm_gfx_ctl_env(const char *key, const char *val) { (void)key; (void)val; }
int dm_gfx_ctl(const char *line) { (void)line; return 0; }

// The front end's join screen (hle_hid.c has the weak default): counts its openings, one player joins
int front_calls;
int front_controller_support(const void *arg, int *count) { (void)arg; front_calls++; if (count) *count = 1; return 1; }
