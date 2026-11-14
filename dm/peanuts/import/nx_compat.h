// Windows (MinGW-w64 GCC) shims for the POSIX calls the importer makes. Included from nx_internal.h; nothing is defined on other systems.
#ifndef NX_COMPAT_H
#define NX_COMPAT_H
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN   // the importer needs no winsock or shell headers
#endif
#ifndef NOMINMAX
#define NOMINMAX              // the importer does not use the min/max macros; keep those names free
#endif
#include <windows.h>
#include <direct.h>
#include <fcntl.h>
#include <io.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>           // before the defines below, so no later declaration is renamed by them
#include <errno.h>
#include <stddef.h>
#include <stdint.h>

// Windows has no close-on-exec flag to ask for, and every open in the importer is of a binary file, so the flag every open already carries asks for binary mode there.
#ifndef O_CLOEXEC
#define O_CLOEXEC O_BINARY
#endif

// Positioned read and write: a synchronous handle with an OVERLAPPED that carries the 64-bit offset (ReadFile and WriteFile then do not move a shared position for us).
// Returns the bytes done, or -1 with errno set, like pread and pwrite.
static inline ssize_t nx_pread(int fd, void *buf, size_t n, int64_t off) {
    HANDLE h = (HANDLE)_get_osfhandle(fd);
    if (h == INVALID_HANDLE_VALUE) { errno = EBADF; return -1; }
    if (n > 0x7fffffffu) n = 0x7fffffffu;   // ReadFile counts in DWORDs; the callers loop over short reads
    OVERLAPPED ov; memset(&ov, 0, sizeof ov);
    ov.Offset = (DWORD)((uint64_t)off & 0xffffffffu);
    ov.OffsetHigh = (DWORD)((uint64_t)off >> 32);
    DWORD got = 0;
    if (!ReadFile(h, buf, (DWORD)n, &got, &ov)) {
        if (GetLastError() == ERROR_HANDLE_EOF) return 0;   // at or past the end: 0 bytes, as pread
        errno = EIO; return -1;
    }
    return (ssize_t)got;
}

static inline ssize_t nx_pwrite(int fd, const void *buf, size_t n, int64_t off) {
    HANDLE h = (HANDLE)_get_osfhandle(fd);
    if (h == INVALID_HANDLE_VALUE) { errno = EBADF; return -1; }
    if (n > 0x7fffffffu) n = 0x7fffffffu;   // as nx_pread: the callers loop over short writes
    OVERLAPPED ov; memset(&ov, 0, sizeof ov);
    ov.Offset = (DWORD)((uint64_t)off & 0xffffffffu);
    ov.OffsetHigh = (DWORD)((uint64_t)off >> 32);
    DWORD put = 0;
    if (!WriteFile(h, buf, (DWORD)n, &put, &ov)) { errno = EIO; return -1; }
    return (ssize_t)put;
}

#define pread nx_pread
#define pwrite nx_pwrite
#define fsync(fd) _commit(fd)
// _chsize_s takes a 64-bit length (the 32-bit one would fail above 2 GiB); 0 on success, as ftruncate.
#define ftruncate(fd, len) _chsize_s(fd, (__int64)(len))
#define nx_mkdir(p, mode) _mkdir(p)

static inline int nx_cpu_count(void) {
    SYSTEM_INFO si; GetSystemInfo(&si);
    return (int)si.dwNumberOfProcessors;
}
#else
#include <sys/stat.h>
#include <unistd.h>
#define nx_mkdir(p, mode) mkdir(p, mode)

// Only where the constant is visible: nximport_cli's _POSIX_C_SOURCE hides it on macOS, and that file never asks for a CPU count.
// Where nximport.c cannot see it, the build stops rather than quietly picking a thread count.
#ifdef _SC_NPROCESSORS_ONLN
static inline int nx_cpu_count(void) { return (int)sysconf(_SC_NPROCESSORS_ONLN); }
#endif
#endif
#endif
