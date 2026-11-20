"""Actual Warehouse evaluator: guarded pointers, exact build gates and live targets."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]

class WarehouseRows(unittest.TestCase):
    def test_runtime_writes(self):
        source = r'''
#include <assert.h>
#include "fpslocker_rows.h"
static unsigned char mem[4096] __attribute__((aligned(8)));
static unsigned writes32, writes64;
static int read_guest(uint64_t a, void *out, size_t n) {
    uintptr_t lo = (uintptr_t)mem;
    if (a < lo || a > lo + sizeof mem || n > lo + sizeof mem - a) return 0;
    memcpy(out, (void *)(uintptr_t)a, n); return 1;
}
static void write_guest(uint64_t a, uint32_t v) { writes32++; uint32_t old; assert(read_guest(a, &old, 4)); memcpy((void *)(uintptr_t)a, &v, 4); }
static void write_guest16(uint64_t a, uint16_t v) { uint16_t old; assert(read_guest(a, &old, 2)); memcpy((void *)(uintptr_t)a, &v, 2); }
static void write_guest64(uint64_t a, uint64_t v) { uint64_t old; assert(!(a & 7) && read_guest(a, &old, 8)); writes64++; __atomic_store_n((uint64_t *)(uintptr_t)a, v, __ATOMIC_RELEASE); }
static void bind(unsigned slot, unsigned data) { uintptr_t p = (uintptr_t)(mem + data); memcpy(mem + slot, &p, 8); }
static float val(unsigned offset) { float f; memcpy(&f, mem + offset, 4); return f; }
int main(void) {
    const char *bid = "9fe60a212cff9b9a3656ab63d4f882e5";
    const char *cfg = "9fe60a212cff9b9a3656ab63d4f882e5,zero_i32,0x100,2,0;"
                      "9fe60a212cff9b9a3656ab63d4f882e5,target_f32,0x108,2,0;"
                      "9fe60a212cff9b9a3656ab63d4f882e5,frame_ms_f32,0x110,2,0;"
                      "9fe60a212cff9b9a3656ab63d4f882e5,frame_ms2_f32,0x118,2,0";
    struct fpw_rows r = {0}; uint64_t base = (uintptr_t)mem;
    assert(fpw_parse(cfg, bid, &r) == 4);
    assert(fpw_apply(&r, base, 120, read_guest, write_guest) == 0); // not initialized
    uintptr_t bad = 1; memcpy(mem + 0x100, &bad, 8);
    assert(fpw_apply(&r, base, 120, read_guest, write_guest) == 0); // inaccessible
    for (unsigned i = 0; i < 4; i++) bind(0x100 + i*8, 0x200 + i*8);
    memset(mem + 0x200, 0x7f, 32);
    assert(fpw_apply(&r, base, 120, read_guest, write_guest) == 8);
    uint32_t zero; memcpy(&zero, mem + 0x200, 4); assert(zero == 0);
    assert(val(0x208) == 120 && val(0x20c) == 120);
    assert(fabsf(val(0x210) - 1000.f/120) < 0.0001f && fabsf(val(0x218) - 8.33f) < 0.0001f);
    assert(fpw_apply(&r, base, 120, read_guest, write_guest) == 0); // no redundant writes
    assert(fpw_apply(&r, base, 90, read_guest, write_guest) == 6);
    assert(val(0x208) == 90 && fabsf(val(0x218) - 11.11f) < 0.0001f);
    assert(fpw_apply(&r, base, 30, read_guest, write_guest) == 6);
    assert(val(0x208) == 30 && fabsf(val(0x218) - 33.33f) < 0.0001f);
    assert(fpw_apply(&r, base, 0, read_guest, write_guest) == 0);
    assert(fpw_apply(&r, base, 240, read_guest, write_guest) == 0);
    bind(0x108, 0x300); assert(fpw_apply(&r, base, 60, read_guest, write_guest) == 6);
    assert(val(0x300) == 60 && val(0x208) == 30); // moved object
    assert(fpw_parse(cfg, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", &r) == 0 && r.n == 0);
    assert(fpw_apply(&r, base, 120, read_guest, write_guest) == 0);
    assert(fpw_parse("bad,asm_a64,0x100,2,0", bid, &r) == -1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,target_f32,0xffffffffffffffff,2,0", bid, &r) == -1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,target_f32,0x100,3,0", bid, &r) == -1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,exec,0x100,2,0", bid, &r) == -1);
    char huge[FPW_SPEC_SIZE + 1]; memset(huge, 'x', sizeof huge - 1); huge[sizeof huge - 1] = 0;
    assert(fpw_parse(huge, bid, &r) == -1);

    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,target_i32,@0x700,1,0",bid,&r)==1);
    for (int rate=20;rate<=120;rate+=10) {
        fpw_apply(&r,base,rate,read_guest,write_guest);
        uint32_t integer;memcpy(&integer,mem+0x700,4);assert(integer==(unsigned)rate);
    }
    // PEANUTS: a dynamic clock must agree across ratio, reciprocal FPS and seconds,
    // including slow GPU frames, startup, and a movie's 32-bit state flag.
    const char *dynamic =
      "9fe60a212cff9b9a3656ab63d4f882e5,constant_i32,@0x740,2,1:1;"
      "9fe60a212cff9b9a3656ab63d4f882e5,time_ratio_f32,%0x0,1,30:2;"
      "9fe60a212cff9b9a3656ab63d4f882e5,elapsed_fps_f32,%0x4,1,1:0.0666666667;"
      "9fe60a212cff9b9a3656ab63d4f882e5,time_ratio_f32,%0x8,1,1:0.0666666667;"
      "9fe60a212cff9b9a3656ab63d4f882e5,inverse_f32,%0xc,1,1:1;"
      "9fe60a212cff9b9a3656ab63d4f882e5,flag_cap_u32_gt,@0x750,1,2:30";
    assert(fpw_parse(dynamic,bid,&r)==6);
    for (int rate=30;rate<=120;rate*=2) {
      const double samples[]={0,1./240,1./120,1./60,1./30,.05,.1};
      for (unsigned i=0;i<sizeof samples/sizeof *samples;i++) {
        double seconds=fmax(1./rate,fmin(samples[i],1./15));
        fpw_apply_context(&r,base,base+0x780,rate,samples[i],read_guest,write_guest);
        assert(fabsf(val(0x780)-(float)(30*seconds))<1e-6);
        assert(fabsf(val(0x784)-(float)(1/seconds))<1e-5);
        assert(fabsf(val(0x788)-(float)seconds)<1e-7);
        assert(fabsf(val(0x78c)-1.f/rate)<1e-7);
        uint32_t interval[2];memcpy(interval,mem+0x740,8);
        assert(interval[0]==1 && interval[1]==1);
      }
    }
    for (unsigned state=0;state<5;state++) {
      memcpy(mem+0x750,&state,4);
      int effective=fpw_effective_cap(&r,base,base+0x780,120,read_guest);
      assert(effective==(state>2?30:120));
      fpw_apply_context(&r,base,base+0x780,effective,.001,read_guest,write_guest);
      assert(fabsf(val(0x78c)-1.f/effective)<1e-7);
    }
    unsigned wide_state=0x100;memcpy(mem+0x750,&wide_state,4);
    assert(fpw_effective_cap(&r,base,base+0x780,120,read_guest)==30);
    assert(fpw_parse(dynamic,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",&r)==0);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,constant_i32,@0x740,1,1.5:1",bid,&r)==-1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,constant_i32,@0x740,1,4294967296:1",bid,&r)==-1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,flag_cap_u32_gt,@0x750,1,2:240",bid,&r)==-1);
    // PEANUTS: Unity keeps fixedDeltaTime as integer ticks behind a pointer.
    const char *ticks = "9fe60a212cff9b9a3656ab63d4f882e5,step_ticks_u32,0x158/0x50,1,141120000:1";
    assert(fpw_parse(ticks, bid, &r) == 1);
    assert(!fpw_apply(&r, base, 20, read_guest, write_guest));
    bind(0x158, 0xa00);
    memset(mem + 0xa50, 0xa5, 8);
    const int caps[] = {20, 30, 40, 45, 60, 90, 120};
    for (unsigned i = 0; i < sizeof caps / sizeof *caps; i++) {
        assert(fpw_apply(&r, base, caps[i], read_guest, write_guest) == 1);
        uint32_t count, neighbor;
        memcpy(&count, mem + 0xa50, 4);
        memcpy(&neighbor, mem + 0xa54, 4);
        assert(count == 141120000u / (unsigned)caps[i] && neighbor == 0xa5a5a5a5u);
    }
    assert(fpw_parse(ticks, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", &r) == 0);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,step_ticks_u32,@0x750,1,4294967296:1",bid,&r)==-1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,step_ticks_u32,@0x750,1,141120000.5:1",bid,&r)==-1);
    // PEANUTS: a measured Trinity step is bounded by the selected cap and the stock ceiling.
    const char *elapsed_ticks = "9fe60a212cff9b9a3656ab63d4f882e5,step_ticks_u32,%0x0,1,1000000000:83333334";
    assert(fpw_parse(elapsed_ticks, bid, &r) == 1);
    const double intervals[] = {0, .01, .02, .2};
    const uint32_t periods[] = {16666666, 16666666, 20000000, 83333334};
    for (unsigned i = 0; i < sizeof intervals / sizeof *intervals; i++) {
      fpw_apply_context(&r,base,base+0xa50,60,intervals[i],read_guest,write_guest);
      uint32_t period;
      memcpy(&period,mem+0xa50,4);
      assert(period == periods[i]);
    }
    // Halfword governor writes must preserve both neighboring bytes and
    // work at the final two bytes of a readable allocation.
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,zero_u16,0x100/0x2,1,0",bid,&r)==1);
    bind(0x100,0x800);memset(mem+0x800,0x7f,8);
    assert(fpw_apply_context_width(&r,base,0,120,0,read_guest,write_guest,write_guest16)==1);
    assert(mem[0x801]==0x7f && !mem[0x802] && !mem[0x803] && mem[0x804]==0x7f);
    bind(0x100,4092);memset(mem+4092,0x7f,4);
    assert(fpw_apply_context_width(&r,base,0,120,0,read_guest,write_guest,write_guest16)==1);
    assert(mem[4093]==0x7f && !mem[4094] && !mem[4095]);
    bind(0x100,4093);
    assert(!fpw_apply_context_width(&r,base,0,120,0,read_guest,write_guest,write_guest16));
    bind(0x100,0x801); // odd field, no atomic halfword store
    assert(!fpw_apply_context_width(&r,base,0,120,0,read_guest,write_guest,write_guest16));
    // One atomic pair publishes coherent ratio/seconds without touching its
    // neighbors. A fast elapsed sample must not be floored to the chosen cap.
    const char *pair = "9fe60a212cff9b9a3656ab63d4f882e5,time_step_pair_f32,%0x0,1,60:0.066666667";
    assert(fpw_parse(pair,bid,&r)==1 && r.row[0].kind==13);
    memset(mem+0x900,0xa5,24);unsigned old32=writes32,old64=writes64;
    assert(fpw_apply_context_width(&r,base,base+0x908,20,1./120,read_guest,write_guest,write_guest16)==0);
    assert(fpw_apply_context_storage(&r,base,base+0x908,20,1./20,1./120,read_guest,write_guest,write_guest16,write_guest64)==1);
    assert(fabsf(val(0x908)-.5f)<1e-6 && fabsf(val(0x90c)-1.f/120)<1e-8);
    assert(mem[0x907]==0xa5 && mem[0x910]==0xa5 && writes32==old32 && writes64==old64+1);
    assert(fpw_apply_context_storage(&r,base,base+0x908,20,1./20,1./120,read_guest,write_guest,write_guest16,write_guest64)==0);
    // Invalid time holds the previous complete pair, including no-time startup.
    const double invalid[] = {0,-1,NAN,INFINITY};
    for (unsigned i=0;i<sizeof invalid/sizeof *invalid;i++)
        assert(!fpw_apply_context_storage(&r,base,base+0x908,120,.02,invalid[i],read_guest,write_guest,write_guest16,write_guest64));
    assert(writes64==old64+1 && fabsf(val(0x908)-.5f)<1e-6);
    fpw_apply_context_storage(&r,base,base+0x908,120,1,1,read_guest,write_guest,write_guest16,write_guest64);
    assert(fabsf(val(0x908)-4.f)<1e-6 && fabsf(val(0x90c)-1.f/15)<1e-8);
    for (int rate=20;rate<=120;rate+=10) {
        fpw_apply_context_storage(&r,base,base+0x908,rate,1.0/rate,1.0/rate,read_guest,write_guest,write_guest16,write_guest64);
        assert(fabsf(val(0x908)-60.f/rate)<1e-6 && fabsf(val(0x90c)-1.f/rate)<1e-8);
    }
    // A complete aligned pair at the read boundary is valid; misaligned and
    // truncated pairs cannot reach any writer.
    assert(fpw_apply_context_storage(&r,base,base+4088,60,.02,.02,read_guest,write_guest,write_guest16,write_guest64)==1);
    old64=writes64;
    assert(!fpw_apply_context_storage(&r,base,base+4092,60,.03,.03,read_guest,write_guest,write_guest16,write_guest64));
    assert(!fpw_apply_context_storage(&r,base,base+4096,60,.03,.03,read_guest,write_guest,write_guest16,write_guest64));
    assert(!fpw_apply_context_storage(&r,base,UINT64_MAX-7,60,.03,.03,read_guest,write_guest,write_guest16,write_guest64));
    assert(writes64==old64);
    assert(fpw_parse(pair,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",&r)==0);
    assert(!fpw_apply_context_storage(&r,base,base+0x908,60,.03,.03,read_guest,write_guest,write_guest16,write_guest64));
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,time_step_pair_f32,%0x0,2,60:0.07",bid,&r)==-1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,time_step_pair_f32,%0x4,1,60:0.07",bid,&r)==-1);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,time_step_pair_f32,%0x0,1,0:0.07",bid,&r)==-1);
    struct fpw_step_filter filter={0};double filtered=99;
    assert(!fpw_step_filter_push(&filter,0,&filtered) && filtered==99);
    const double jitter[]={.004,.008,.012,.016,.020};
    for (unsigned i=0;i<5;i++) {
        assert(fpw_step_filter_push(&filter,jitter[i],&filtered));
        assert(fabs(filtered-.004*(i+2)/2)<1e-12);
    }
    double real_sum=0,step_sum=0;
    for (unsigned i=0;i<500;i++) {
        double dt=jitter[i%5];assert(fpw_step_filter_push(&filter,dt,&filtered));
        real_sum+=dt;step_sum+=filtered;
    }
    assert(fabs(step_sum-real_sum)<1e-10); // five-sample averaging keeps wall time
    assert(!fpw_step_filter_push(&filter,.251,&filtered) && !filter.count && !filter.next);
    assert(fpw_step_filter_push(&filter,.020,&filtered) && fabs(filtered-.020)<1e-12);
    assert(!fpw_step_filter_push(&filter,.0009,&filtered) && !filter.count);
    assert(!fpw_step_filter_push(&filter,NAN,&filtered) && !filter.count);
    assert(fpw_step_filter_push(&filter,.2,&filtered) && filtered==.2);
    assert(fpw_parse(pair,bid,&r)==1);
    fpw_apply_context_storage(&r,base,base+0x908,120,.2,filtered,read_guest,write_guest,write_guest16,write_guest64);
    assert(fabsf(val(0x908)-4.f)<1e-6); // valid slow sample capped after averaging
    // Filtering/holding the pair does not replace another row's raw elapsed.
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,time_step_pair_f32,%0x0,1,60:0.066666667;9fe60a212cff9b9a3656ab63d4f882e5,time_ratio_f32,%0x8,1,30:2",bid,&r)==2);
    fpw_apply_context_storage(&r,base,base+0x908,120,.02,0,read_guest,write_guest,write_guest16,write_guest64);
    assert(fabsf(val(0x908)-4.f)<1e-6 && fabsf(val(0x910)-.6f)<1e-6);
    const char *unity = "9fe60a212cff9b9a3656ab63d4f882e5,vsync_u32,0x100/0x30/0x68,1,0;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,vsync_u32,@0x120,1,0;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,inverse_f32,0x108/0xb8/0x0/0x20,1,30:0.14";
    memset(mem,0,sizeof mem);bind(0x100,0x200);bind(0x230,0x300);
    bind(0x108,0x400);bind(0x4b8,0x500);bind(0x500,0x600);
    assert(fpw_parse(unity,bid,&r)==3 && fpw_rate_ok(&r,20) && fpw_rate_ok(&r,60));
    assert(!fpw_rate_ok(&r,40) && !fpw_rate_ok(&r,120));
    assert(fpw_apply(&r,base,20,read_guest,write_guest)==3);
    uint32_t count;memcpy(&count,mem+0x368,4);assert(count==3);
    memcpy(&count,mem+0x120,4);assert(count==3 && fabsf(val(0x620)-.21f)<.00001f);
    assert(fpw_apply(&r,base,40,read_guest,write_guest)==0 && fabsf(val(0x620)-.21f)<.00001f);
    assert(fpw_apply(&r,base,60,read_guest,write_guest)==3 && fabsf(val(0x620)-.07f)<.00001f);
    const char *clock = "9fe60a212cff9b9a3656ab63d4f882e5,time_ratio_f32,%0x0,1,30:2;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,target_scaled_f32,%0x8,1,30:100;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,flag_cap_u8,%0x10,1,0:30";
    assert(fpw_parse(clock,bid,&r)==3);mem[0x810]=1;
    assert(fpw_effective_cap(&r,base,base+0x800,120,read_guest)==120);
    assert(fpw_apply_context(&r,base,base+0x800,120,1./80,read_guest,write_guest)==2);
    assert(fabsf(val(0x800)-.375f)<.00001f && val(0x808)==400);
    fpw_apply_context(&r,base,base+0x800,120,0,read_guest,write_guest);assert(val(0x800)==.25f);
    fpw_apply_context(&r,base,base+0x800,120,1,read_guest,write_guest);assert(val(0x800)==2);
    // One real second advances the same 30 reference ticks at every offered cap.
    const int targets[] = {20,30,40,60,90,120};
    for (unsigned j=0;j<sizeof targets/sizeof *targets;j++) {
        int rate=targets[j];double ticks=0;
        for (int frame=0;frame<rate;frame++) {
            fpw_apply_context(&r,base,base+0x800,rate,1.0/rate,read_guest,write_guest);
            ticks+=val(0x800);
        }
        assert(fabs(ticks-30.0)<.0001);
    }
    mem[0x810]=0;assert(fpw_effective_cap(&r,base,base+0x800,120,read_guest)==30);
    assert(fpw_effective_cap(&r,base,base+0x800,20,read_guest)==30); // required cutscene rate
    // PEANUTS: Kirby and the Forgotten Land 1.1.0: the interval behind one pointer, and the
    // frame-time budget pair behind four (Warehouse [MAIN, a, b, c, d, e]).
    const char *kirby = "9fe60a212cff9b9a3656ab63d4f882e5,zero_i32,0x100/0x50,1,0;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,inverse_f32,0x108/0x140/0x1b0/0x20/0x34,1,30:1.95;"
                        "9fe60a212cff9b9a3656ab63d4f882e5,inverse_f32,0x108/0x140/0x1b0/0x20/0x38,1,30:1.7";
    memset(mem,0,sizeof mem);
    assert(fpw_parse(kirby,bid,&r)==3 && fpw_rate_ok(&r,20) && fpw_rate_ok(&r,120));
    assert(fpw_apply(&r,base,60,read_guest,write_guest)==0); // nothing bound yet
    bind(0x100,0x200);mem[0x250]=2;
    bind(0x108,0x300);bind(0x440,0x500);bind(0x6b0,0x700);
    assert(fpw_apply(&r,base,60,read_guest,write_guest)==1); // a chain not built yet leaves its rows unwritten
    memcpy(&count,mem+0x250,4);assert(count==0);
    bind(0x720,0x780);
    assert(fpw_apply(&r,base,60,read_guest,write_guest)==2);
    assert(fabsf(val(0x7b4)-.975f)<.00001f && fabsf(val(0x7b8)-.85f)<.00001f);
    assert(fpw_apply(&r,base,30,read_guest,write_guest)==2);
    assert(fabsf(val(0x7b4)-1.95f)<.00001f && fabsf(val(0x7b8)-1.7f)<.00001f); // the game's own budget at 30
    assert(fpw_apply(&r,base,120,read_guest,write_guest)==2 && fabsf(val(0x7b4)-.4875f)<.00001f);
    assert(fpw_parse("9fe60a212cff9b9a3656ab63d4f882e5,inverse_f32,0x108/0x8/0x8/0x8/0x8/0x8,1,30:1",bid,&r)==-1); // five: refused
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / 'rows.c'
            exe = pathlib.Path(tmp) / 'rows'
            src.write_text(source)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-fsanitize=undefined',
                            '-I', str(ROOT / 'dm/host'), str(src), '-lm', '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

if __name__ == '__main__':
    unittest.main()
