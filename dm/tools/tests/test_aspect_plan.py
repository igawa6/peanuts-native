"""PEANUTS: aspect rows (dm/host/aspect_plan.h): the shape rule, each kind's word, and the all-or-nothing write."""
import pathlib
import re
import subprocess
import tempfile
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[3]
class AspectPlans(unittest.TestCase):
    def test_kinds_and_transaction(self):
        c=r'''
#include <assert.h>
#include "aspect_plan.h"
static unsigned char image[0x400];
static void put(unsigned o,uint32_t v) { memcpy(image+o,&v,4); }
static uint32_t get(unsigned o) { uint32_t v;memcpy(&v,image+o,4);return v; }
static float getf(unsigned o) { float f;memcpy(&f,image+o,4);return f; }
static void seed(void) { float r=16.0f/9.0f;memset(image,0,sizeof image);put(0x100,0xd280f008);put(0x104,0xf2c08708);memcpy(image+0x201,&r,4);put(0x300,1920); }
int main(void) {
 const char *bid="a6ce40dc3aedb1becacd2f1ff4515307";
 // Kirby and the Forgotten Land 1.1.0's row (mov x8,#1920), a float at an odd offset, a 32-bit width, and a row of another build
 const char *s="a6ce40dc3aedb1becacd2f1ff4515307,width_movz,m0x100,08f080d2;"
 "a6ce40dc3aedb1becacd2f1ff4515307,ratio_f32,m0x201,398ee33f;"
 "a6ce40dc3aedb1becacd2f1ff4515307,width_u32,m0x300,80070000;"
 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb,width_u32,m0x300,80070000";
 struct asp_plan p;assert(asp_plan_parse(s,bid,&p)==3);
 // the shape: w:h, nothing for 16:9, junk or a shape outside 0.4..4
 assert(asp_shape("21:9")>2.33 && asp_shape("21:9")<2.34 && asp_shape("8:7")>1.14 && asp_shape("8:7")<1.15);
 assert(!asp_shape("16:9") && !asp_shape("1920:1080") && !asp_shape(NULL) && !asp_shape("") && !asp_shape("21:9x") && !asp_shape("0:9") && !asp_shape("9:1") && !asp_shape("1:9"));
 // 21:9: 1920 -> 2520, the MOVZ keeps its register and width; the half beside it is not touched
 seed();assert(asp_plan_apply(&p,image,sizeof image,asp_shape("21:9"))==3);
 assert(get(0x100)==(0xd2800008u|(2520u<<5)) && get(0x104)==0xf2c08708 && get(0x300)==2520);
 assert(getf(0x201)>2.3333f && getf(0x201)<2.3334f);
 // 8:7 (the second screen): 1920 -> 1234, even
 seed();assert(asp_plan_apply(&p,image,sizeof image,asp_shape("8:7"))==3);
 assert(get(0x100)==(0xd2800008u|(1234u<<5)) && get(0x300)==1234 && getf(0x201)>1.1428f && getf(0x201)<1.1429f);
 // one word that is not the expected one: nothing is written
 seed();put(0x300,1280);assert(asp_plan_apply(&p,image,sizeof image,asp_shape("21:9"))==-2);
 assert(get(0x100)==0xd280f008 && getf(0x201)>1.7777f && getf(0x201)<1.7778f);
 // a site outside the image
 seed();assert(asp_plan_apply(&p,image,0x300,asp_shape("21:9"))==-1 && get(0x100)==0xd280f008);
 // another build: no row; malformed specs
 assert(asp_plan_parse(s,"cccccccccccccccccccccccccccccccc",&p)==0 && p.n==0);
 assert(asp_plan_parse("",bid,&p)==0);
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,width_movz,m0x100",bid,&p)==-1);            // a field short
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,height_movz,m0x100,08f080d2",bid,&p)==-1);   // no such kind
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,width_movz,m0x102,08f080d2",bid,&p)==-1);    // code is word aligned
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,width_movz,m0x100,0887c0f2",bid,&p)==-1);    // a MOVK is not a MOVZ
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,ratio_f32,m0x200,0000803f",bid,&p)==-1);     // 1.0 is not 16/9
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,width_u32,m0x300,10000000",bid,&p)==-1);     // 16 is not a width
 assert(asp_plan_parse("A6CE40DC3AEDB1BECACD2F1FF4515307,width_u32,m0x300,80070000",bid,&p)==-1);     // build IDs are lower case
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,width_u32,c0x300,80070000",bid,&p)==-1);     // main only
 // Metroid Dread 2.1.0's pair (1600x900): the shape with about the same pixel count; Fire Emblem: Three Houses 1.2.0's two calls made NOPs
 const char *d="a6ce40dc3aedb1becacd2f1ff4515307,area_width_u32,m0x300,40060000;a6ce40dc3aedb1becacd2f1ff4515307,area_height_u32,m0x304,84030000;"
 "a6ce40dc3aedb1becacd2f1ff4515307,nop,m0x100,bb611394";
 assert(asp_plan_parse(d,bid,&p)==3);
 memset(image,0,sizeof image);put(0x300,1600);put(0x304,900);put(0x100,0x941361bb);
 assert(asp_plan_apply(&p,image,sizeof image,asp_shape("21:9"))==3 && get(0x300)==1834 && get(0x304)==786 && get(0x100)==0xd503201f);
 memset(image,0,sizeof image);put(0x300,1600);put(0x304,900);put(0x100,0x941361bb);
 assert(asp_plan_apply(&p,image,sizeof image,asp_shape("8:7"))==3 && get(0x300)==1282 && get(0x304)==1122);
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,nop,m0x100,08f080d2",bid,&p)==-1);           // only a BL becomes a NOP
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,nop,m0x101,bb611394",bid,&p)==-1);           // code is word aligned
 // Breath of the Wild 1.6.0's sites: fdiv s0,s0,s1 and ldr s0,[x0,#0xb0] become fmov s0,#imm8. 4:3 gives the word of the community's 4:3 patch (1.3125)
 const char *b="a6ce40dc3aedb1becacd2f1ff4515307,ratio_fmov,m0x100,0018211e;a6ce40dc3aedb1becacd2f1ff4515307,ratio_fmov,m0x104,00b040bd";
 assert(asp_plan_parse(b,bid,&p)==2);
 memset(image,0,sizeof image);put(0x100,0x1e211800);put(0x104,0xbd40b000);
 assert(asp_plan_apply(&p,image,sizeof image,asp_shape("4:3"))==2 && get(0x100)==0x1e2eb000 && get(0x104)==0x1e2eb000);
 assert(asp_fmov_value(0x75)==1.3125 && asp_fmov_value(0x70)==1.0 && asp_fmov_value(0x00)==2.0 && asp_fmov_value(0x60)==0.5);
 for (double s=0.4;s<4.0;s+=0.01) { double v=asp_fmov_value(asp_fmov_imm8(s));assert(fabs(v/s-1)<0.032); }   // every shape has a value within 3.2 %
 assert(asp_fmov_value(asp_fmov_imm8(21.0/9))==2.375 && asp_fmov_value(asp_fmov_imm8(31.0/27))==1.125);
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,ratio_fmov,m0x100,08f080d2",bid,&p)==-1);    // a MOVZ leaves no ratio in a float register
 // the float 16/9 built in a register (mov w8,#0x8e39; movk w8,#0x3fe3,lsl #16): the two halves become the shape's, the register kept
 const char *m="a6ce40dc3aedb1becacd2f1ff4515307,ratio_movz,m0x100,28c79152;a6ce40dc3aedb1becacd2f1ff4515307,ratio_movk,m0x104,68fca772";
 assert(asp_plan_parse(m,bid,&p)==2);
 memset(image,0,sizeof image);put(0x100,0x5291c728);put(0x104,0x72a7fc68);
 assert(asp_plan_apply(&p,image,sizeof image,asp_shape("21:9"))==2);
 { float f=21.0f/9.0f;uint32_t b;memcpy(&b,&f,4);assert(get(0x100)==(0x52800008u|((b&0xffff)<<5)) && get(0x104)==(0x72a00008u|((b>>16)<<5))); }
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,ratio_movz,m0x100,08f080d2",bid,&p)==-1);    // a MOVZ of another number
 assert(asp_plan_parse("a6ce40dc3aedb1becacd2f1ff4515307,ratio_movk,m0x104,28c79152",bid,&p)==-1);    // the halves are not interchangeable
 return 0;
}'''
        with tempfile.TemporaryDirectory() as d:
            src=pathlib.Path(d)/'t.c';src.write_text(c)
            exe=pathlib.Path(d)/'t'
            subprocess.run(['cc','-std=c11','-D_GNU_SOURCE','-Wall','-Werror','-I',str(ROOT/'dm/host'),str(src),'-lm','-o',str(exe)],check=True)
            subprocess.run([str(exe)],check=True)
    def test_profile_rows_are_well_formed(self):
        """Every aspect_plan row in dm/games: five fields, a known kind, and a main build of the same profile (build.gradle aspectPlanOf's rule)."""
        seen=0
        for tsv in sorted((ROOT/'dm/games').glob('*/game.tsv')):
            rows=[l.split('\t') for l in tsv.read_text(encoding='utf-8').splitlines() if l.startswith('aspect_plan\t')]
            if not rows: continue
            seen+=len(rows)
            text=tsv.read_text(encoding='utf-8')
            for r in rows:
                self.assertEqual(len(r),5,f'{tsv}: {r}')
                self.assertRegex(r[1],r'^[0-9a-f]{32}$');self.assertIn(r[2],('ratio_f32','width_movz','width_u32','area_width_u32','area_height_u32','nop','ratio_fmov','ratio_movz','ratio_movk'))
                self.assertRegex(r[3],r'^m0x[0-9a-f]+$');self.assertRegex(r[4],r'^[0-9a-f]{8}$')
                self.assertIn(f'main={r[1]}',text,f'{tsv}: {r[1]} is not one of the profile\'s main builds')
        self.assertGreater(seen,0)
    def test_every_profile_plan_passes_the_loader_parser(self):
        """Each profile's rows as the launcher passes them (DM_ASPECT_PLAN): the loader's parser takes every row, and every row has a word for a wide and a narrow shape."""
        c=r'''
#include <assert.h>
#include "aspect_plan.h"
int main(int argc,char **argv) {
 struct asp_plan p;int n=asp_plan_parse(argv[2],argv[1],&p);
 if (n!=atoi(argv[3])) { printf("parse %d, want %s\n",n,argv[3]);return 1; }
 const char *shapes[]={"21:9","8:7","31:27","4:3","20:9"};
 for (int s=0;s<5;s++) for (unsigned i=0;i<p.n;i++) { uint32_t w;if (!asp_word(&p.row[i],asp_shape(shapes[s]),&w)) { printf("row %u has no word for %s\n",i,shapes[s]);return 1; } }
 return 0;
}'''
        with tempfile.TemporaryDirectory() as d:
            src=pathlib.Path(d)/'t.c';src.write_text(c)
            exe=pathlib.Path(d)/'t'
            subprocess.run(['cc','-std=c11','-D_GNU_SOURCE','-Wall','-Werror','-I',str(ROOT/'dm/host'),str(src),'-lm','-o',str(exe)],check=True)
            games=0
            for tsv in sorted((ROOT/'dm/games').glob('*/game.tsv')):
                rows=[l.split('\t') for l in tsv.read_text(encoding='utf-8').splitlines() if l.startswith('aspect_plan\t')]
                builds=sorted({r[1] for r in rows})
                for b in builds:
                    mine=[r for r in rows if r[1]==b]
                    spec=';'.join(','.join(r[1:5]) for r in rows)
                    out=subprocess.run([str(exe),b,spec,str(len(mine))],capture_output=True,text=True)
                    self.assertEqual(out.returncode,0,f'{tsv}: {out.stdout}')
                games+=bool(rows)
            self.assertGreater(games,0)
if __name__=='__main__':
    unittest.main()
