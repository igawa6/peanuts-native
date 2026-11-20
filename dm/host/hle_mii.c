// Mii service layer (nn::mii::Database, the Mii editor applet). On hardware Database talks to the mii:u service (an IDatabaseService the SDK keeps at Database +0) and AppendMii /
// ShowMiiEdit start the MiiEdit library applet; the host has neither. It answers as a console with no Mii of its own: the six default Miis, an empty database, and the editor cancelled.
// Mario Kart 8 Deluxe (SDK 4.4.0) initializes a Database at boot (mii:e and mii:u were the first services it opened itself) and builds 12 random Miis for its title screen's demo race; their
// heads are drawn by the SDK's Mii rendering code linked into main, from the MiiModel system data (the host's stand-in below, mounted by hle_fs.c MountSystemData).
// The CharInfo the host builds (0x58 bytes) is the system's default Mii as the mii service's StoreData::BuildDefault makes it: the 3DS-era default parameters, with the colours converted to
// the Switch's common colour indices (hair 1, eyes 8, mouth 19, glasses 8), a create ID of UUID version 4 (fixed for each default Mii, fresh for a random one) and the nickname "no name";
// every field is within CharInfoRaw::Verify's ranges (SDK 4.4.0 sdk+0x1b68c8), so CharInfoAccessor::IsValid accepts it.
#include "hle.h"
#include <sys/stat.h>

uint64_t dm_entropy64(void);

#define MII_RES_NOT_FOUND 0x87Eu        // nn::mii module 126, description 4
#define MII_RES_CANCELED 0xE7Eu         // description 7: the player closed the applet (the SDK's ShowMiiEdit treats it as success)
#define MII_RES_TEST_MODE 0x1987Eu      // description 204: importing a database needs the system's test mode

typedef struct __attribute__((packed)) {
    uint8_t create_id[16]; uint16_t nickname[11];
    uint8_t font_region, favorite_color, gender, height, build, type, region_move;
    uint8_t faceline_type, faceline_color, faceline_wrinkle, faceline_make;
    uint8_t hair_type, hair_color, hair_flip;
    uint8_t eye_type, eye_color, eye_scale, eye_aspect, eye_rotate, eye_x, eye_y;
    uint8_t eyebrow_type, eyebrow_color, eyebrow_scale, eyebrow_aspect, eyebrow_rotate, eyebrow_x, eyebrow_y;
    uint8_t nose_type, nose_scale, nose_y;
    uint8_t mouth_type, mouth_color, mouth_scale, mouth_aspect, mouth_y;
    uint8_t beard_color, beard_type, mustache_type, mustache_scale, mustache_y;
    uint8_t glass_type, glass_color, glass_scale, glass_y;
    uint8_t mole_type, mole_scale, mole_x, mole_y, reserved;
} mii_charinfo;
_Static_assert(sizeof(mii_charinfo) == 0x58, "nn::mii::CharInfo is 0x58 bytes");

// PEANUTS begin: the console's own six default Miis (three men, three women: their hair, skin, eyes and favourite colours), not one Mii six times.
// Mario Kart 8 Deluxe's Select a Mii showed the host's copies, the first a blank tile (GitHub request: Mii heads from the firmware's MiiModel)
// The console's six default Miis (nn::mii DefaultMii), as StoreData::BuildDefault turns them into a CharInfo: colours through the Ver3 tables (eyebrow_y as is: a CharInfo keeps it 3 above the StoreData's).
// From Ryujinx (MIT; via Eden src/core/hle/service/mii/types/raw_data.cpp), generated; PEANUTS
static const struct mii_def { uint8_t faceline_type, faceline_color, faceline_wrinkle, faceline_make, hair_type, hair_color, hair_flip, eye_type, eye_color, eye_scale, eye_aspect, eye_rotate, eye_x, eye_y,
    eyebrow_type, eyebrow_color, eyebrow_scale, eyebrow_aspect, eyebrow_rotate, eyebrow_x, eyebrow_y, nose_type, nose_scale, nose_y, mouth_type, mouth_color, mouth_scale, mouth_aspect, mouth_y,
    mustache_type, beard_type, beard_color, mustache_scale, mustache_y, glass_type, glass_color, glass_scale, glass_y, mole_type, mole_scale, mole_x, mole_y, height, build, gender, favorite_color,
    region_move, font_region, type; } mii_defs[6] = {
    {0, 4, 0, 0, 68, 8, 0, 2, 8, 4, 3, 4, 2, 12, 6, 8, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 0, 4, 0, 0, 0},
    {0, 0, 0, 0, 55, 6, 0, 2, 12, 4, 3, 4, 2, 12, 6, 6, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 0, 5, 0, 0, 0},
    {0, 1, 0, 0, 33, 1, 0, 2, 8, 4, 3, 4, 2, 12, 6, 1, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 0, 0, 0, 0, 0},
    {0, 2, 0, 0, 24, 8, 0, 4, 8, 4, 3, 3, 2, 12, 0, 8, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 1, 2, 0, 0, 0},
    {0, 0, 0, 0, 14, 7, 0, 4, 13, 4, 3, 3, 2, 12, 0, 7, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 1, 6, 0, 0, 0},
    {0, 0, 0, 0, 12, 1, 0, 4, 8, 4, 3, 3, 2, 12, 0, 1, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 0, 8, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20, 64, 64, 1, 7, 0, 0, 0},
};
// PEANUTS end
// index ≥ 0: default Mii <index> (a fixed create ID, so a game that saved one finds the same Mii again); < 0: a random Mii (a fresh one)
static void mii_default(mii_charinfo *c, int gender, int index) {
    memset(c, 0, sizeof *c);
    uint64_t a = index >= 0 ? 0x4d49494e44454641ull + (uint64_t)index : dm_entropy64(), b = index >= 0 ? 0x554c54444d484f53ull : dm_entropy64();
    memcpy(c->create_id, &a, 8); memcpy(c->create_id + 8, &b, 8);
    c->create_id[6] = (uint8_t)((c->create_id[6] & 0x0f) | 0x40); c->create_id[8] = (uint8_t)((c->create_id[8] & 0x3f) | 0x80);
    if (index >= 0) c->favorite_color = (uint8_t)(index * 2);   // red, yellow, green, sky blue, purple, white
    static const char name[] = "no name";
    for (size_t i = 0; i < sizeof name - 1; i++) c->nickname[i] = (uint16_t)name[i];
    if (index >= 0 && index < 6) {   // PEANUTS: a default Mii from the console's table (mii_defs)
        const struct mii_def *d = &mii_defs[index];
        c->faceline_type = d->faceline_type; c->faceline_color = d->faceline_color; c->faceline_wrinkle = d->faceline_wrinkle; c->faceline_make = d->faceline_make;
        c->hair_type = d->hair_type; c->hair_color = d->hair_color; c->hair_flip = d->hair_flip;
        c->eye_type = d->eye_type; c->eye_color = d->eye_color; c->eye_scale = d->eye_scale; c->eye_aspect = d->eye_aspect; c->eye_rotate = d->eye_rotate; c->eye_x = d->eye_x; c->eye_y = d->eye_y;
        c->eyebrow_type = d->eyebrow_type; c->eyebrow_color = d->eyebrow_color; c->eyebrow_scale = d->eyebrow_scale; c->eyebrow_aspect = d->eyebrow_aspect; c->eyebrow_rotate = d->eyebrow_rotate;
        c->eyebrow_x = d->eyebrow_x; c->eyebrow_y = d->eyebrow_y;
        c->nose_type = d->nose_type; c->nose_scale = d->nose_scale; c->nose_y = d->nose_y;
        c->mouth_type = d->mouth_type; c->mouth_color = d->mouth_color; c->mouth_scale = d->mouth_scale; c->mouth_aspect = d->mouth_aspect; c->mouth_y = d->mouth_y;
        c->mustache_type = d->mustache_type; c->beard_type = d->beard_type; c->beard_color = d->beard_color; c->mustache_scale = d->mustache_scale; c->mustache_y = d->mustache_y;
        c->glass_type = d->glass_type; c->glass_color = d->glass_color; c->glass_scale = d->glass_scale; c->glass_y = d->glass_y;
        c->mole_type = d->mole_type; c->mole_scale = d->mole_scale; c->mole_x = d->mole_x; c->mole_y = d->mole_y;
        c->height = d->height; c->build = d->build; c->gender = d->gender; c->favorite_color = d->favorite_color;
        c->region_move = d->region_move; c->font_region = d->font_region; c->type = d->type;
        return;
    }
    c->gender = gender == 1; c->height = 64; c->build = 64;
    c->hair_type = 33; c->hair_color = 1;
    c->eye_type = 2; c->eye_color = 8; c->eye_scale = 4; c->eye_aspect = 3; c->eye_rotate = 4; c->eye_x = 2; c->eye_y = 12;
    c->eyebrow_type = 6; c->eyebrow_color = 1; c->eyebrow_scale = 4; c->eyebrow_aspect = 3; c->eyebrow_rotate = 6; c->eyebrow_x = 2; c->eyebrow_y = 10;
    c->nose_type = 1; c->nose_scale = 4; c->nose_y = 9;
    c->mouth_type = 23; c->mouth_color = 19; c->mouth_scale = 4; c->mouth_aspect = 3; c->mouth_y = 13;
    c->beard_color = 8; c->mustache_scale = 4; c->mustache_y = 10;
    c->glass_color = 8; c->glass_scale = 4; c->glass_y = 10;
    c->mole_scale = 4; c->mole_x = 2; c->mole_y = 20;
}

// The object keeps its service pointer at +0, which the constructor zeroes and the destructor releases through its vtable when set; the host never sets it
HLE_FORCE("_ZN2nn3mii8Database10InitializeEv", "mii:u service; the host's database: the six default Miis, none of the console's own", Result, h_mii_Init, void *self) {
    (void)self; LOG("mii: Database::Initialize (the six default Miis, none of the console's own)"); return RES_OK;
}
HLE_FORCE("_ZN2nn3mii8Database8FinalizeEv", "mii:u service; nothing to release", void, h_mii_Fin, void *self) { (void)self; }
HLE_FORCE("_ZNK2nn3mii8Database11IsAvailableEv", "mii:u service; always available", int, h_mii_Avail, const void *self) { (void)self; return 1; }
HLE_FORCE("_ZN2nn3mii8Database9IsUpdatedEi", "mii:u service; the empty database never changes", int, h_mii_Updated, void *self, int src) { (void)self; (void)src; return 0; }
HLE_FORCE("_ZNK2nn3mii8Database14IsFullDatabaseEv", "mii:u service; empty, so not full", int, h_mii_Full, const void *self) { (void)self; return 0; }
// PEANUTS begin: the player's own Miis, made in the Android app's Settings › Miis (dev.peanuts.mii.MiiStore) and handed over as DM_MII_DB=<file>: "PNMII1\0\0", a u32
// count, then that many 0x58-byte CharInfo records. They are the console's database (source 1): Database::Get lists them before the six default Miis, GetIndex and
// UpdateLatest find them by create ID. A record that fails the ranges CharInfoRaw::Verify checks is skipped (a game's CharInfoAccessor::IsValid would refuse it)
enum { MII_DB_MAX = 100 };
static mii_charinfo g_mii_db[MII_DB_MAX]; static int g_mii_db_n = -1;
static int mii_ok(const mii_charinfo *c) {
    static const struct { uint8_t off, max; } r[] = {
        {38, 3}, {39, 11}, {40, 1}, {41, 127}, {42, 127}, {43, 1}, {44, 3}, {45, 11}, {46, 9}, {47, 11}, {48, 11}, {49, 131}, {50, 99}, {51, 1},
        {52, 59}, {53, 99}, {54, 7}, {55, 6}, {56, 7}, {57, 12}, {58, 18}, {59, 23}, {60, 99}, {61, 8}, {62, 6}, {63, 11}, {64, 12},
        {66, 17}, {67, 8}, {68, 18}, {69, 35}, {70, 99}, {71, 8}, {72, 6}, {73, 18}, {74, 99}, {75, 5}, {76, 5}, {77, 8}, {78, 16},
        {79, 19}, {80, 99}, {81, 7}, {82, 20}, {83, 1}, {84, 8}, {85, 16}, {86, 30}};
    const uint8_t *b = (const uint8_t *)c;
    for (size_t i = 0; i < sizeof r / sizeof r[0]; i++) if (b[r[i].off] > r[i].max) return 0;
    if (c->eyebrow_y < 3 || c->eyebrow_y - 3 > 15 || !c->nickname[0]) return 0;
    return 1;
}
static int mii_db(void) {
    if (g_mii_db_n >= 0) return g_mii_db_n;
    g_mii_db_n = 0;
    const char *path = getenv("DM_MII_DB"); if (!path || !*path) return 0;
    FILE *f = fopen(path, "rb"); if (!f) { if (errno != ENOENT) LOG("[RED] mii: cannot open DM_MII_DB %s: %s", path, strerror(errno)); return 0; }   // no file yet: no Miis of the player's
    uint8_t h[12]; uint32_t n = 0;
    if (fread(h, 1, 12, f) == 12 && !memcmp(h, "PNMII1\0\0", 8)) {
        memcpy(&n, h + 8, 4);
        for (uint32_t i = 0; i < n && g_mii_db_n < MII_DB_MAX; i++) {
            mii_charinfo c; if (fread(&c, 1, sizeof c, f) != sizeof c) break;
            if (mii_ok(&c)) g_mii_db[g_mii_db_n++] = c; else LOG("[RED] mii: Mii %u of DM_MII_DB is out of range, skipped", i);
        }
    } else LOG("[RED] mii: DM_MII_DB %s is not a Mii file", path);
    fclose(f);
    LOG("mii: %d of the player's own Miis (DM_MII_DB)", g_mii_db_n);
    return g_mii_db_n;
}
static int mii_db_find(const void *info) {
    for (int i = 0; info && i < mii_db(); i++) if (!memcmp(g_mii_db[i].create_id, info, 16)) return i;
    return -1;
}
// PEANUTS end
// Source flags: 1 the console's database (empty here), 2 the six default Miis every console has. Mario Kart 8 Deluxe asks for the defaults with room for 6 and never reads the count
// (main+0x81e954): its boot-time list of Mii icons (main+0x419dc8) builds a head for each of the six, and zeroed entries failed the model's CharInfo check
enum { MII_SRC_DATABASE = 1, MII_SRC_DEFAULT = 2, MII_DEFAULTS = 6 };
static int mii_get(void *buf, int n, int src, size_t stride) {
    int k = 0;
    for (int i = 0; (src & MII_SRC_DATABASE) && buf && k < n && i < mii_db(); i++, k++) {   // PEANUTS: the player's own Miis first (source 0 = database)
        uint8_t *e = (uint8_t *)buf + (size_t)k * stride;
        memcpy(e, &g_mii_db[i], sizeof(mii_charinfo));
        if (stride > sizeof(mii_charinfo)) { uint32_t source = 0; memcpy(e + sizeof(mii_charinfo), &source, 4); }
    }
    for (int d = 0; (src & MII_SRC_DEFAULT) && buf && k < n && d < MII_DEFAULTS; d++, k++) {
        uint8_t *e = (uint8_t *)buf + (size_t)k * stride;
        mii_default((mii_charinfo *)e, d >= 3, d);
        if (stride > sizeof(mii_charinfo)) { uint32_t source = 1; memcpy(e + sizeof(mii_charinfo), &source, 4); }   // CharInfoElement: the CharInfo and its source (1 = default)
    }
    return k;
}
HLE_FORCE("_ZNK2nn3mii8Database8GetCountEi", "mii:u service; the player's Miis and the six default Miis", int, h_mii_Count, const void *self, int src) {
    (void)self; return (src & MII_SRC_DATABASE ? mii_db() : 0) + (src & MII_SRC_DEFAULT ? MII_DEFAULTS : 0);   // PEANUTS: the player's Miis count too
}
HLE_FORCE("_ZN2nn3mii8Database3GetEPiPNS0_15CharInfoElementEii", "mii:u service; the six default Miis", Result, h_mii_GetE, void *self, int *out, void *buf, int n, int src) {
    (void)self; *out = mii_get(buf, n, src, sizeof(mii_charinfo) + 4); return RES_OK;
}
HLE_FORCE("_ZN2nn3mii8Database3GetEPiPNS0_8CharInfoEii", "mii:u service; the six default Miis", Result, h_mii_Get, void *self, int *out, void *buf, int n, int src) {
    (void)self; *out = mii_get(buf, n, src, sizeof(mii_charinfo)); return RES_OK;
}
HLE_FORCE("_ZNK2nn3mii8Database8GetIndexEPiRKNS0_8CharInfoE", "mii:u service; the player's Miis by create ID", Result, h_mii_Index, const void *self, int *out, const void *info) {
    (void)self; int i = mii_db_find(info); *out = i; return i >= 0 ? RES_OK : MII_RES_NOT_FOUND;   // PEANUTS
}
HLE_FORCE("_ZNK2nn3mii8Database12UpdateLatestEPNS0_8CharInfoEi", "mii:u service; the player's Miis by create ID", Result, h_mii_Latest, const void *self, void *info, int src) {
    (void)self; int i = (src & MII_SRC_DATABASE) ? mii_db_find(info) : -1;   // PEANUTS: a saved Mii of the player's comes back as it is now
    if (i < 0) return MII_RES_NOT_FOUND;
    memcpy(info, &g_mii_db[i], sizeof(mii_charinfo)); return RES_OK;
}
// RandomDesc {age, gender, race}: gender 0 male, 1 female, 2 either. The host's "random" Mii is the default one of that gender
HLE_FORCE("_ZN2nn3mii8Database11BuildRandomEPNS0_8CharInfoERKNS0_10RandomDescE", "mii:u service; the default Mii", Result, h_mii_Random, void *self, mii_charinfo *out, const int *desc) {
    (void)self; mii_default(out, desc ? desc[1] : 0, -1); return RES_OK;
}
HLE_FORCE("_ZNK2nn3mii8Database12BuildDefaultEPNS0_8CharInfoEi", "mii:u service; default Mii <index>", Result, h_mii_Default, const void *self, mii_charinfo *out, int index) {
    (void)self; mii_default(out, index >= 3, index < 0 || index >= MII_DEFAULTS ? 0 : index); return RES_OK;
}
HLE_FORCE("_ZN2nn3mii8Database6FormatEv", "mii:u service; the database is already empty", Result, h_mii_Format, void *self) { (void)self; return RES_OK; }
HLE_FORCE("_ZN2nn3mii8Database6ImportEPKvm", "mii:u service; importing needs the system's test mode", Result, h_mii_Import, void *self, const void *p, size_t n) {
    (void)self; (void)p; LOG("[RED] mii: Database::Import of %zu bytes refused (the host has no test mode)", n); return MII_RES_TEST_MODE;
}
HLE_FORCE("_ZNK2nn3mii8Database6ExportEPvm", "mii:u service; nothing to export", Result, h_mii_Export, const void *self, void *p, size_t n) {
    (void)self; (void)p; (void)n; LOG("[RED] mii: Database::Export refused"); return MII_RES_TEST_MODE;
}
// MiiModel system data (0100000000000802) stand-in. The Mii rendering code an application links (Mario Kart 8 Deluxe 4.0.0: main+0x8f1400..+0x8fc000) reads one texture file
// (NXTexture{Low,Mid}{Linear,SRGB}.dat) and one shape file (Shape{Mid,High}.dat) whole and builds each part of a head from them; the firmware's files are not the host's to ship, so it writes
// files of the same layout in which every part is absent:
//   texture file: +0x0c u32 largest decompressed size of each of the 11 part kinds, +0x38 their alignments, then from +0x64 one 0x1c-byte entry per part (u32 offset, u32 size, u32 stored size,
//     4 zlib parameters, then the texture: u16 width / height at +0x14, format, mip count and kind at +0x18), hats 132, eyes 62, eyebrows 24, beards 2, wrinkles 12, make-up 12, glasses 20,
//     moles 2, mouths 37, mustaches 6, noselines 18 (main+0x8fb0dc);
//   shape file: +0x0c the 12 largest sizes, +0x3c their alignments, from +0x6c one 0x58-byte entry per part (offset, size, stored size, zlib parameters, five vertex attribute offsets
//     and sizes, index offset and size, bounding box), beards 4, facelines 12, masks 12, hats / foreheads / hair in normal and cap form 132 each, glasses 1, noses 18, noselines 18
//     (main+0x8faee8); then the hair transforms (132 × 0x48, +0x12704) and faceline transforms (12 × 0x24, +0x14c24).
// A texture whose size is 0 is skipped when the model is built (main+0x8faaf4); the model's own render targets (face, mask) do not come from the file. Shapes cannot all be absent: the
// faceline is drawn without a check (its position buffer, main+0x8286c4: a null buffer read at main+0xa690d0), so every shape entry points at one shared mesh, one vertex at the origin
// (attribute 0, the position: 4 halves) and the triangle (0, 0, 0) (u16 indices at +0x10), 0x20 bytes of zeros inflated with nn::util::DecompressZlib, stored here as one raw deflate block.
// It builds, and draws nothing. The largest sizes size the model's memory pool (main+0x8f2e6c) and the first alignment is that pool's alignment, so they are one page each instead of 0.
// The first 12 bytes of each header are not read
enum { MII_TEX_SIZE = 0x64 + 0x1c * 327, MII_SHAPE_TABLES = 0x6c + 0x58 * 857 + 0x48 * 132 + 0x24 * 12, MII_MESH_AT = 0x14de0, MII_MESH = 0x20 };
_Static_assert(MII_TEX_SIZE == 0x2428 && MII_SHAPE_TABLES == 0x14dd4, "MiiModel stand-in layout");
static const uint8_t mii_mesh_z[] = {0x78, 0x01, 0x01, MII_MESH, 0, (uint8_t)~MII_MESH, 0xff, [7 ... 7 + MII_MESH - 1] = 0, 0x00, MII_MESH, 0x00, 0x01};   // zlib: stored block, Adler-32 of 32 zeros
enum { MII_SHAPE_SIZE = MII_MESH_AT + sizeof mii_mesh_z };
static void mii_shapes(uint8_t *b) {
    for (int i = 0; i < 857; i++) {
        uint32_t *e = (uint32_t *)(b + 0x6c + 0x58 * i);
        e[0] = MII_MESH_AT; e[1] = MII_MESH; e[2] = sizeof mii_mesh_z;   // data offset, inflated size, stored size
        e[4 + 5] = 8;                                                    // attribute 0 (offset 0) is one 8-byte vertex; attributes 1-4 absent
        e[14] = 0x10; e[15] = 6;                                         // index offset and size: three u16 indices
    }
    memcpy(b + MII_MESH_AT, mii_mesh_z, sizeof mii_mesh_z);
}
static int mii_write(const char *dir, const char *name, int parts, size_t size) {
    char p[1280]; snprintf(p, sizeof p, "%s/%s", dir, name);
    uint8_t *b = calloc(1, size); FILE *f = fopen(p, "wb");
    for (int i = 0; b && i < parts; i++) { uint32_t page = 0x1000; memcpy(b + 0xc + 4 * i, &page, 4); memcpy(b + 0xc + 4 * parts + 4 * i, &page, 4); }
    if (b && parts == 12) mii_shapes(b);
    int ok = b && f && fwrite(b, 1, size, f) == size;
    if (f && fclose(f)) ok = 0;
    free(b);
    if (!ok) LOG("[RED] mii: cannot write the MiiModel stand-in %s", p);
    return ok;
}
int dm_mii_standin(const char *dir) {   // written once per process (the query and the mount both ask), so a host with another layout never leaves old files behind
    static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER; static int done;
    pthread_mutex_lock(&mu);
    if (done) { pthread_mutex_unlock(&mu); return done > 0; }
    char p[1024]; strlcpy(p, dir, sizeof p);
    for (char *s = p + 1; *s; s++) if (*s == '/') { *s = 0; mkdir(p, 0755); *s = '/'; }
    mkdir(p, 0755);
    static const char *tex[] = {"NXTextureLowLinear.dat", "NXTextureLowSRGB.dat", "NXTextureMidLinear.dat", "NXTextureMidSRGB.dat"}, *shape[] = {"ShapeMid.dat", "ShapeHigh.dat"};
    int ok = 1;
    for (int i = 0; i < 4; i++) ok &= mii_write(dir, tex[i], 11, MII_TEX_SIZE);
    for (int i = 0; i < 2; i++) ok &= mii_write(dir, shape[i], 12, MII_SHAPE_SIZE);
    LOG("mii: MiiModel system data is the host's stand-in (no textures, every shape one degenerate triangle: Mii heads draw nothing) in %s", dir);
    done = ok ? 1 : -1;
    pthread_mutex_unlock(&mu);
    return ok;
}

// The Mii editor (MiiEdit library applet): the player closed it without adding a Mii
HLE_FORCE("_ZN2nn3mii9AppendMiiEPi", "MiiEdit applet; cancelled", Result, h_mii_Append, int *index) { DM_APPLET("Mii editor"); (void)index; LOG("mii: AppendMii (the host has no Mii editor: cancelled)"); return MII_RES_CANCELED; }
HLE_FORCE("_ZN2nn3mii11ShowMiiEditEv", "MiiEdit applet; closed at once", Result, h_mii_Edit, void) { DM_APPLET("Mii editor"); LOG("mii: ShowMiiEdit (the host has no Mii editor)"); return RES_OK; }
