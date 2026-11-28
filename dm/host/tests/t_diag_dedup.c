// nn::diag::detail::PutImpl collapses identical consecutive game log lines.
#include "../hle_sdk16.c"

static int checks, fails;
static uint64_t g_mock_ns;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s\n", msg); } } while (0)

void *guest_sym(const char *name) { (void)name; return NULL; }
module_t *g_mod;
int g_nmod;
uint64_t g_t0_ns;
uint64_t now_ns(void) { return g_mock_ns; }
int dm_sdk_major(void) { return 3; }
int dm_sdk_ounce(void) { return 0; }
int dm_game_imports(const char *prefix) { (void)prefix; return 0; }
module_t *module_of(const void *p) { (void)p; return NULL; }
void dm_backtrace(uint64_t fp) { (void)fp; }
void hle_bind_system_event(void *ev, int autoclear) { (void)ev; (void)autoclear; }
void hle_signal_system_event(void *ev) { (void)ev; }
uint64_t hle_system_event_binding(void *ev) { (void)ev; abort(); }
int hle_signal_system_event_binding(uint64_t binding) { (void)binding; abort(); }
uint32_t hle_hid_connected_npad(void) { return 0; }
void dm_host_helper_qos(qos_class_t q) { (void)q; }
void dm_applet_enter(const char *name) { (void)name; }
void dm_applet_leave(void) {}

static void read_new_log(FILE *tmp, long *pos, char *buf, size_t cap) {
    fflush(stderr);
    fseek(tmp, *pos, SEEK_SET);
    size_t got = fread(buf, 1, cap - 1, tmp);
    buf[got] = '\0';
    *pos += (long)got;
}

int main(void) {
    FILE *tmp = tmpfile();
    if (!tmp) return 1;
    int saved_err = dup(STDERR_FILENO);
    if (saved_err < 0) {
        fclose(tmp);
        return 1;
    }
    if (dup2(fileno(tmp), STDERR_FILENO) < 0) {
        close(saved_err);
        fclose(tmp);
        return 1;
    }
    long pos = 0;
    char buf[4096];

    // 1. First message logs immediately.
    g_mock_ns = 1000000000ULL;
    h_diag_Put(NULL, "msgA", 4);
    read_new_log(tmp, &pos, buf, sizeof buf);
    CHECK(strcmp(buf, "[dm] [game] msgA\n") == 0, "first message logs once");

    // 2. 100 identical calls within < 10 s produce no additional lines.
    g_mock_ns = 5000000000ULL;
    for (int i = 0; i < 100; i++) h_diag_Put(NULL, "msgA", 4);
    read_new_log(tmp, &pos, buf, sizeof buf);
    CHECK(buf[0] == '\0', "identical messages within 10s are suppressed");

    // 3. Advancing by >= 10 s and logging the same message flushes the repeat count.
    g_mock_ns = 11000000000ULL;
    h_diag_Put(NULL, "msgA", 4);
    read_new_log(tmp, &pos, buf, sizeof buf);
    CHECK(strcmp(buf, "[dm] [game] (last message repeated 101 times)\n") == 0, "periodic flush after 10s emits repeat count");

    // 4. Trailing newline/NUL normalization still collapses into msgA, and msgB flushes summary first.
    g_mock_ns = 12000000000ULL;
    h_diag_Put(NULL, "msgA\n\0", 6);
    h_diag_Put(NULL, "msgA\0", 5);
    h_diag_Put(NULL, "msgA", 4);
    read_new_log(tmp, &pos, buf, sizeof buf);
    CHECK(buf[0] == '\0', "trailing newline/NUL normalized duplicates stay suppressed");

    h_diag_Put(NULL, "msgB", 4);
    read_new_log(tmp, &pos, buf, sizeof buf);
    CHECK(strcmp(buf, "[dm] [game] (last message repeated 3 times)\n[dm] [game] msgB\n") == 0, "new message flushes repeat count before printing");

    // 5. Messages larger than 512 bytes are both logged without false collapsing.
    char long_msg[600];
    memset(long_msg, 'x', sizeof long_msg);
    h_diag_Put(NULL, long_msg, sizeof long_msg);
    h_diag_Put(NULL, long_msg, sizeof long_msg);
    read_new_log(tmp, &pos, buf, sizeof buf);
    char *first = strstr(buf, "[dm] [game] ");
    char *second = first ? strstr(first + 12, "[dm] [game] ") : NULL;
    CHECK(first != NULL && second != NULL, "messages over 512 bytes are both logged");

    dup2(saved_err, STDERR_FILENO);
    close(saved_err);
    fclose(tmp);
    printf("diag dedup: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
