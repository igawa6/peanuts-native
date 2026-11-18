"""Expected-byte transactions and ARM64 relocations in the actual pre-seal Warehouse loader."""
import pathlib
import subprocess
import tempfile
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[3]
class CodePlans(unittest.TestCase):
    def test_transaction_and_relocations(self):
        c=r'''
#include <assert.h>
#include <stdio.h>
#include "fpslocker_plan.h"
static unsigned char image[0xc000] __attribute__((aligned(0x4000)));
static void put(unsigned o,uint32_t v) { memcpy(image+o,&v,4); }
static uint32_t get(unsigned o) { uint32_t v;memcpy(&v,image+o,4);return v; }
static void seed(void) { memset(image,0,sizeof image);put(0x100,0x52800033);put(0x110,1);for (unsigned o=0x200;o<0x20c;o+=4) put(o,0xd503201f); }
int main(void) {
 const char *bid="8c08b9719e085f91847b5e0f935d9488";
 const char *s="8c08b9719e085f91847b5e0f935d9488,r0x100,33008052,f303002a;"
 "8c08b9719e085f91847b5e0f935d9488,o0x110,01000000,02000000;"
 "8c08b9719e085f91847b5e0f935d9488,m0x200,1f2003d5,p10:0x4;"
 "8c08b9719e085f91847b5e0f935d9488,m0x204,1f2003d5,420540bd;"
 "8c08b9719e085f91847b5e0f935d9488,m0x208,1f2003d5,l0x0;"
 "8c08b9719e085f91847b5e0f935d9488,c0x0,-,p7:0x10;"
 "8c08b9719e085f91847b5e0f935d9488,c0x4,-,e1400039;"
 "8c08b9719e085f91847b5e0f935d9488,c0x8,-,t0x300;"
 "8c08b9719e085f91847b5e0f935d9488,d0x0,-,0000803f;"
 "8c08b9719e085f91847b5e0f935d9488,clock,-,1";
 struct fpw_plan p={0};assert(fpw_plan_parse(s,bid,&p)==9 && p.clock && p.aux);
 seed();assert(fpw_plan_apply(&p,image,0x4000,0x4000,0x8000,0)==8);
 assert(get(0x100)==0x2a0003f3 && get(0x110)==1 && get(0x8000)==0x3f800000);
 // ADRP x10 points eight pages forward; BL points exactly to the auxiliary code page.
 assert(get(0x200)==(0x9000000a|(2u<<5)));
 assert(get(0x208)==(0x94000000u|((0x4000-0x208)/4)));
 assert(get(0x4008)==(0x14000000u|(((0x300-0x4008)/4)&0x3ffffff)));
 seed();assert(fpw_plan_apply(&p,image,0x4000,0,0,1)==2);
 assert(get(0x100)==0x2a0003f3 && get(0x110)==2 && get(0x200)==0xd503201f); // owned restores only
 seed();put(0x208,0);assert(fpw_plan_apply(&p,image,0x4000,0x4000,0x8000,0)==-2);
 assert(get(0x100)==0x52800033 && get(0x200)==0xd503201f && !get(0x8000)); // no partial writes
 assert(fpw_plan_parse(s,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",&p)==0 && !p.clock);
 assert(fpw_plan_parse("bad,m0x200,1f2003d5,p10:0x4",bid,&p)==-1);
 assert(fpw_plan_parse("8c08b9719e085f91847b5e0f935d9488,m0x200,1f2003d5,p32:0x4",bid,&p)==-1);
 assert(fpw_plan_parse("8c08b9719e085f91847b5e0f935d9488,c0x4000,-,1f2003d5",bid,&p)==-1);
 assert(fpw_plan_parse("8c08b9719e085f91847b5e0f935d9488,m0x200,1f2003d5,p10:0x4000",bid,&p)==1);
 seed();assert(fpw_plan_apply(&p,image,0x4000,0x4000,0x8000,0)==-3 && get(0x200)==0xd503201f);
 return 0;
}
'''
        with tempfile.TemporaryDirectory() as td:
            src=pathlib.Path(td)/'plan.c';exe=pathlib.Path(td)/'plan';src.write_text(c)
            subprocess.run(['cc','-std=gnu11','-Wall','-Wextra','-Werror','-fsanitize=undefined','-I',str(ROOT/'dm/host'),str(src),'-o',str(exe)],check=True)
            subprocess.run([str(exe)],check=True)
if __name__=='__main__':unittest.main()
