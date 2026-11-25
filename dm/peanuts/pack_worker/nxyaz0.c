// _nxyaz0: Yaz0 decompression in C for shader_split.py (dm/gfx/tools/shader_split.py yaz0). Nearly all of a Nintendo game's shader files are Yaz0 (.szs, and
// .bfres members of SARC archives): Mario Kart 8 Deluxe 4.0.1's split spent almost all of its 452 s (PC, 2 workers) and 8:09 (AYN Thor, 4 workers) decoding
// its 1,359 shader files in Python.
//   _nxyaz0.decode(data: a bytes-like object) -> bytes, or None
// decode does exactly what yaz0's Python loop does and returns the same bytes. Where that loop stops with an error (the stream ends early, a back-reference
// reaches before the start), decode returns None, and yaz0 runs its Python loop on the same stream, which then names the error as before: a damaged file reads
// the same with and without this module. Also None for a size the stream cannot reach (more than 2,184 bytes for each 25 bytes of it: 8 back-references of
// 0x111 bytes, a group's most), so a damaged header of a stream under about 47 MiB does not make decode allocate gigabytes first; a size the allocation
// cannot have (no memory), and a buffer that is not one run of bytes, give None too, and the loop decides.
// The format: big-endian size at +4, the data at +16 in groups of a header byte and 8 operations, high bit first: 1 = one literal byte; 0 = a back-reference
// of 2 bytes {length high nibble, 12-bit distance} (distance + 1); length 0 reads one more byte + 0x12, else + 2. A copy that overlaps itself repeats its
// period. The last operation may reach past the size: what lies past it is not kept.
// Build: dm/peanuts/pack_worker/build.sh (into <stage>/python/lib/python3.14/lib-dynload, as _nxaes); peanuts/scripts/peanuts_build.py builds it for the PC
// builder's Python (peanuts/.build/pymod); dm/tools/tests/test_split_yaz0.py builds it for the tests.
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <stdint.h>
#include <string.h>

// out: n bytes. 0 = decoded, -1 = the Python loop stops with an error on this stream
static int yaz0(const uint8_t *s, size_t len, uint8_t *out, size_t n) {
    size_t o = 0, si = 16;
    while (o < n) {
        if (si >= len) return -1;
        unsigned h = s[si++];
        for (int b = 0; b < 8 && o < n; b++, h <<= 1) {
            if (h & 0x80) {
                if (si >= len) return -1;
                out[o++] = s[si++];
                continue;
            }
            if (len - si < 2) return -1;
            unsigned b1 = s[si], b2 = s[si + 1]; si += 2;
            size_t dist = ((size_t)(b1 & 15) << 8 | b2) + 1, ln = b1 >> 4;
            if (ln) ln += 2;
            else {
                if (si >= len) return -1;
                ln = (size_t)s[si++] + 0x12;
            }
            if (dist > o) return -1;
            if (ln > n - o) ln = n - o;   // past the size: not kept
            uint8_t *d = out + o; const uint8_t *f = d - dist;
            if (dist >= 8 && n - o >= ln + 8) for (size_t i = 0; i < ln; i += 8) memcpy(d + i, f + i, 8);   // 8 bytes at a time, each from bytes already written; up to
                                                                                                         // 7 past the copy, which the operations after it overwrite
            else if (dist == 1) memset(d, f[0], ln);
            else if (dist >= ln) memcpy(d, f, ln);
            else for (size_t i = 0; i < ln; i++) d[i] = f[i];   // overlapping: each byte from one already written
            o += ln;
        }
    }
    return 0;
}

static PyObject *decode(PyObject *self, PyObject *arg) {
    (void)self;
    Py_buffer in;
    if (PyObject_GetBuffer(arg, &in, PyBUF_SIMPLE) < 0) {   // not one run of bytes (a strided memoryview): the Python loop reads it
        if (!PyErr_ExceptionMatches(PyExc_BufferError)) return NULL;
        PyErr_Clear(); Py_RETURN_NONE;
    }
    const uint8_t *s = in.buf; size_t len = (size_t)in.len;
    uint64_t n = 0;
    for (size_t i = 4; i < 8 && i < len; i++) n = n << 8 | s[i];   // int.from_bytes(s[4:8], 'big'), of fewer bytes when the stream is shorter
    PyObject *res = NULL;
    if (n > (uint64_t)len / 25 * 2184 + 2184 || n > PY_SSIZE_T_MAX) { Py_INCREF(Py_None); res = Py_None; }   // more than the stream can reach
    else if (!(res = PyBytes_FromStringAndSize(NULL, (Py_ssize_t)n))) {   // no memory for the size up front: the loop, which grows as it decodes, decides
        if (PyErr_ExceptionMatches(PyExc_MemoryError)) { PyErr_Clear(); Py_INCREF(Py_None); res = Py_None; }
    }
    else {
        int r;
        Py_BEGIN_ALLOW_THREADS
        r = yaz0(s, len, (uint8_t *)PyBytes_AS_STRING(res), (size_t)n);
        Py_END_ALLOW_THREADS
        if (r) { Py_DECREF(res); Py_INCREF(Py_None); res = Py_None; }
    }
    PyBuffer_Release(&in);
    return res;
}

static PyMethodDef METHODS[] = {{"decode", decode, METH_O, "decode(data) -> bytes, or None where shader_split.py's Python loop stops with an error"}, {NULL, NULL, 0, NULL}};
static struct PyModuleDef MOD = {PyModuleDef_HEAD_INIT, "_nxyaz0", NULL, -1, METHODS, NULL, NULL, NULL, NULL};

PyMODINIT_FUNC PyInit__nxyaz0(void) { return PyModule_Create(&MOD); }
