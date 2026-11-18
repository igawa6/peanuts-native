# PEANUTS: execute the production driver-call wrappers with FPCR and Vulkan entry points stubbed.
"""Tracing forwards all compile arguments/results and preserves both host and guest FPCR modes."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class CompileTrace(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_driver_boundary_and_fpcr(self):
        source = (ROOT / 'dm/gfx/vk_android/vka_dev.c').read_text()
        start = source.index('static int g_compile_trace;')
        wrappers = source[start:source.index('// PEANUTS end', start)]
        harness = r'''
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <assert.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static int guest, fpcr, calls, logs, clocks;
static char last[512];
static uint64_t now_ns(void) { return (uint64_t)++clocks * 100; }
static uint64_t vka_fp_host(const char *s) { (void)s; int old=fpcr; if(!guest)fpcr=0; return (uint64_t)old; }
static void vka_fp_back(uint64_t f) { fpcr=(int)f; }
static int vka_fp_guest(void) { return guest; }
static void log_line(const char *fmt, ...) { va_list a; va_start(a,fmt); vsnprintf(last,sizeof last,fmt,a); va_end(a); logs++; assert(fpcr==7); }
#define VLOG(...) log_line(__VA_ARGS__)
static VkShaderModuleCreateInfo module={.codeSize=1234};
static VkGraphicsPipelineCreateInfo graphics[2];
static VkComputePipelineCreateInfo compute[3];
static VkAllocationCallbacks allocator;
static VkShaderModule sm;
static VkPipeline pipes[3];
static void called(void) { assert(fpcr==(guest?7:0)); calls++; }
static VkResult VKAPI_CALL sm_stub(VkDevice d,const VkShaderModuleCreateInfo *ci,const VkAllocationCallbacks *a,VkShaderModule *m) {
 called(); assert(d==(VkDevice)1 && ci==&module && a==&allocator && m==&sm); *m=(VkShaderModule)2; return VK_ERROR_UNKNOWN;
}
static VkResult VKAPI_CALL gp_stub(VkDevice d,VkPipelineCache c,uint32_t n,const VkGraphicsPipelineCreateInfo *ci,const VkAllocationCallbacks *a,VkPipeline *p) {
 called(); assert(d==(VkDevice)1 && c==(VkPipelineCache)3 && n==2 && ci==graphics && a==&allocator && p==pipes); p[0]=(VkPipeline)4; return VK_PIPELINE_COMPILE_REQUIRED;
}
static VkResult VKAPI_CALL cp_stub(VkDevice d,VkPipelineCache c,uint32_t n,const VkComputePipelineCreateInfo *ci,const VkAllocationCallbacks *a,VkPipeline *p) {
 called(); assert(d==(VkDevice)1 && c==(VkPipelineCache)3 && n==3 && ci==compute && a==&allocator && p==pipes); p[0]=(VkPipeline)5; return VK_SUCCESS;
}
static struct {PFN_vkCreateShaderModule vkCreateShaderModule; PFN_vkCreateGraphicsPipelines vkCreateGraphicsPipelines; PFN_vkCreateComputePipelines vkCreateComputePipelines;} vk;
WRAPPERS
int main(void) {
 for(guest=0;guest<=1;guest++)for(int trace=0;trace<=1;trace++) {
  setenv("DM_VK_COMPILE_TRACE",trace?"1":"0",1); fpcr=7; calls=logs=clocks=0;
  vk.vkCreateShaderModule=sm_stub; vk.vkCreateGraphicsPipelines=gp_stub; vk.vkCreateComputePipelines=cp_stub;
  fp_wrap_compiles();
  assert((vk.vkCreateShaderModule==fp_csm)==(!guest||trace));
  assert(vk.vkCreateShaderModule((VkDevice)1,&module,&allocator,&sm)==VK_ERROR_UNKNOWN && sm==(VkShaderModule)2 && fpcr==7);
  if(trace)assert(strstr(last,"kind=shader-module start=100 end=200 duration=100") && strstr(last,"count=1 bytes=1234"));
  assert(vk.vkCreateGraphicsPipelines((VkDevice)1,(VkPipelineCache)3,2,graphics,&allocator,pipes)==VK_PIPELINE_COMPILE_REQUIRED && pipes[0]==(VkPipeline)4 && fpcr==7);
  if(trace)assert(strstr(last,"kind=graphics-pipeline") && strstr(last,"count=2 bytes=0"));
  assert(vk.vkCreateComputePipelines((VkDevice)1,(VkPipelineCache)3,3,compute,&allocator,pipes)==VK_SUCCESS && pipes[0]==(VkPipeline)5 && fpcr==7);
  if(trace)assert(strstr(last,"kind=compute-pipeline") && strstr(last,"count=3 bytes=0"));
  assert(calls==3 && logs==(trace?3:0) && clocks==(trace?6:0));
 }
 return 0;
}
'''.replace('WRAPPERS', wrappers)
        with tempfile.TemporaryDirectory(prefix='vk-compile-trace-') as td:
            src, exe = Path(td) / 'trace.c', Path(td) / 'trace'
            src.write_text(harness)
            compiled = subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror',
                                       '-I', str(ROOT / 'dm/third_party/vulkan-headers/include'),
                                       str(src), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            ran = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stderr)


if __name__ == '__main__':
    unittest.main()
