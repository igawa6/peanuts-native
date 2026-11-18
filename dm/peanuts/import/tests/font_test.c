// Firmware font import with synthetic NCAs and made-up keys: a missing key generation is not a missing font archive.
#define _POSIX_C_SOURCE 200809L
#include "nximport.h"
#include "nsp_fixture.h"
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "line %d: %s (%s)\n", __LINE__, #x, nximport_last_error()); return 1; } } while (0)

static void font_nca(const FxKeys *keys, uint64_t title, int generation, const char *path) {
    // A tiny invented font payload, BFTTF-wrapped and encrypted exactly as the importer expects.
    uint8_t font[16] = {0}; fx_w32(font, 0x18029a7f); fx_w32(font + 4, 8); memcpy(font + 8, "testfont", 8);
    for (size_t i = 0; i < sizeof font; i += 4) { uint32_t word; memcpy(&word, font + i, 4); fx_w32(font + i, word ^ 0x06186249u); }
    FxFile file = { .path = "nintendo_std.bfttf", .size = sizeof font, .data = font };
    FxNca n = { .content_type = 3, .title_id = title };
    fx_fill(n.key, sizeof n.key);
    n.sec[0].present = 1; n.sec[0].fs_type = NX_FS_ROMFS; n.sec[0].crypt = 3;
    fx_romfs(&file, 1, &n.sec[0].body);
    FxBuf out = {0}; fx_nca(keys, &n, &out);
    if (generation) {
        NxAes a, b; nx_aes_init(&a, keys->header); nx_aes_init(&b, keys->header + 16);
        nx_aes_xts_decrypt(&a, &b, 0, 0x200, out.p, out.p, 0xC00);
        out.p[0x220] = (uint8_t)(generation + 1);
        fx_xts_encrypt(keys->header, 0, out.p, 0xC00);
    }
    fx_write(path, out.p, out.n); free(out.p); free(n.sec[0].body.p);
}

int main(void) {
    char tmp[384]; const char *temp = getenv("TMPDIR");
    snprintf(tmp, sizeof tmp, "%s/nximport-font-XXXXXX", temp && *temp ? temp : "/tmp"); CHECK(mkdtemp(tmp));
    char keys_path[512], source[512], output[512], standard[512], extended[512];
    snprintf(keys_path, sizeof keys_path, "%s/prod.keys", tmp);
    snprintf(source, sizeof source, "%s/firmware", tmp); CHECK(!mkdir(source, 0700));
    snprintf(output, sizeof output, "%s/fonts", tmp);
    snprintf(standard, sizeof standard, "%s/firmware/standard.nca", tmp);
    snprintf(extended, sizeof extended, "%s/firmware/extended.nca", tmp);
    FxKeys keys; fx_keys_make(&keys, keys_path); CHECK(!nximport_keys(keys_path));
    font_nca(&keys, 0x0100000000000811ull, 0, standard);
    font_nca(&keys, 0x0100000000000810ull, 0, extended);
    CHECK(!nximport_firmware_fonts(source, output));
    char result[512]; snprintf(result, sizeof result, "%s/fonts/standard.ttf", tmp);
    uint8_t got[8]; FILE *f = fopen(result, "rb"); CHECK(f); CHECK(fread(got, 1, sizeof got, f) == sizeof got); fclose(f);
    CHECK(!memcmp(got, "testfont", sizeof got));

    // A rejected firmware must keep both installed fonts, even if one new archive decoded successfully.
    char ext_result[512]; snprintf(ext_result, sizeof ext_result, "%s/fonts/nintendo_ext.ttf", tmp);
    fx_write(result, "old-font", 8); fx_write(ext_result, "old-font", 8);
    font_nca(&keys, 0x0100000000000811ull, 0x16, standard);
    CHECK(nximport_firmware_fonts(source, output) == -1);
    CHECK(strstr(nximport_last_error(), "FontStandard"));
    CHECK(strstr(nximport_last_error(), "key_area_key_application_16"));
    CHECK(!strstr(nximport_last_error(), "not found among"));
    f = fopen(result, "rb"); CHECK(f); CHECK(fread(got, 1, 8, f) == 8); fclose(f); CHECK(!memcmp(got, "old-font", 8));
    f = fopen(ext_result, "rb"); CHECK(f); CHECK(fread(got, 1, 8, f) == 8); fclose(f); CHECK(!memcmp(got, "old-font", 8));

    font_nca(&keys, 0x0100000000000811ull, 0, standard);
    font_nca(&keys, 0x0100000000000810ull, 0x16, extended);
    CHECK(nximport_firmware_fonts(source, output) == -1);
    CHECK(strstr(nximport_last_error(), "FontNintendoExtended"));
    CHECK(strstr(nximport_last_error(), "key_area_key_application_16"));

    f = fopen(result, "rb"); CHECK(f); CHECK(fread(got, 1, 8, f) == 8); fclose(f); CHECK(!memcmp(got, "old-font", 8));
    f = fopen(ext_result, "rb"); CHECK(f); CHECK(fread(got, 1, 8, f) == 8); fclose(f); CHECK(!memcmp(got, "old-font", 8));

    // Supplying the matching made-up generation makes exactly the same NCA import successfully.
    char hex[33]; fx_hex(hex, keys.kaek, sizeof keys.kaek);
    f = fopen(keys_path, "a"); CHECK(f); fprintf(f, "key_area_key_application_16 = %s\n", hex); fclose(f);
    CHECK(!nximport_keys(keys_path)); CHECK(!nximport_firmware_fonts(source, output));

    CHECK(!unlink(standard)); CHECK(!unlink(extended));
    CHECK(nximport_firmware_fonts(source, output) == -1);
    CHECK(strstr(nximport_last_error(), "no NCA files"));
    // An unrelated title with a missing generation must not mask the absence of the required archives.
    font_nca(&keys, 0x0100000000000812ull, 0x17, standard);
    CHECK(nximport_firmware_fonts(source, output) == -1);
    CHECK(strstr(nximport_last_error(), "not found among"));
    CHECK(!unlink(standard)); CHECK(!unlink(result));
    snprintf(result, sizeof result, "%s/fonts/nintendo_ext.ttf", tmp); CHECK(!unlink(result));
    CHECK(!unlink(keys_path)); CHECK(!rmdir(source)); CHECK(!rmdir(output)); CHECK(!rmdir(tmp));
    puts("font_test: passed"); return 0;
}
