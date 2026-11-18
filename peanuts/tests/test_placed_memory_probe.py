"""Run the guest-buffer probe against drivers that advertise broken mapping behavior."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

HARNESS = r'''
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>
#define FNS(X) X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory) X(vkBindBufferMemory) X(vkMapMemory) X(vkUnmapMemory) X(vkMapMemory2KHR) X(vkUnmapMemory2KHR)
#define DECL(f) PFN_##f f;
typedef struct { FNS(DECL) } vka_fns;
USAGE
#include "dm/peanuts/peanuts_memory_probe.h"
static int mode, fd=-1, buffers, memories, maps, unmaps, normal_maps, unmap_tries;
static void *live; static size_t size=65536;
static VkResult create(VkDevice d,const VkBufferCreateInfo *i,const VkAllocationCallbacks *a,VkBuffer *o){buffers++;*o=(VkBuffer)1;return 0;}
static void destroy(VkDevice d,VkBuffer b,const VkAllocationCallbacks *a){buffers--;}
static void req(VkDevice d,VkBuffer b,VkMemoryRequirements *r){*r=(VkMemoryRequirements){size,4096,1};}
static VkResult alloc(VkDevice d,const VkMemoryAllocateInfo *i,const VkAllocationCallbacks *a,VkDeviceMemory *o){fd=memfd_create("probe-test",0);assert(fd>=0);assert(!ftruncate(fd,size));memories++;*o=(VkDeviceMemory)1;return 0;}
static void release(VkDevice d,VkDeviceMemory m,const VkAllocationCallbacks *a){assert(!live);close(fd);fd=-1;memories--;}
static VkResult bind(VkDevice d,VkBuffer b,VkDeviceMemory m,VkDeviceSize o){return 0;}
static VkResult map(VkDevice d,VkDeviceMemory m,VkDeviceSize o,VkDeviceSize n,VkMemoryMapFlags flags,void **p){
 normal_maps++;if(mode==5){*p=NULL;return VK_ERROR_MEMORY_MAP_FAILED;}
 assert(!live);live=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);assert(live!=MAP_FAILED);maps++;*p=live;
 if(mode==4 && normal_maps==2)memset(live,0,size);return 0;
}
static void unmap(VkDevice d,VkDeviceMemory m){assert(live);assert(!munmap(live,size));live=NULL;unmaps++;}
static VkResult map2(VkDevice d,const VkMemoryMapInfoKHR *i,void **p){
 if(mode==1){*p=NULL;return VK_ERROR_MEMORY_MAP_FAILED;}
 assert(!live);const VkMemoryMapPlacedInfoEXT *pi=i->pNext;
 live=mmap(mode==2?NULL:pi->pPlacedAddress,size,PROT_READ|PROT_WRITE,MAP_SHARED|(mode==2?0:MAP_FIXED),fd,0);
 assert(live!=MAP_FAILED);maps++;*p=live;if(mode==3)memset(live,0,size);return 0;
}
static VkResult unmap2(VkDevice d,const VkMemoryUnmapInfoKHR *i){
 if(mode==6 && unmap_tries++==0)return VK_ERROR_MEMORY_MAP_FAILED;
 unmap(d,i->memory);return 0;
}
int main(void){
 vka_fns f={.vkCreateBuffer=create,.vkDestroyBuffer=destroy,.vkGetBufferMemoryRequirements=req,.vkAllocateMemory=alloc,.vkFreeMemory=release,.vkBindBufferMemory=bind,.vkMapMemory=map,.vkUnmapMemory=unmap,.vkMapMemory2KHR=map2,.vkUnmapMemory2KHR=unmap2};
 char why[160];
 for(mode=0;mode<=6;mode++){
  normal_maps=unmap_tries=0;
  int ok=peanuts_placed_probe(&f,(VkDevice)1,0,1,65536,why,sizeof why);
  assert(ok==(mode==0));assert(!buffers && !memories && !live && maps==unmaps);
  if(mode==1)assert(strstr(why,"placed remap, -5"));
 }
 f.vkMapMemory2KHR=NULL;assert(!peanuts_placed_probe(&f,(VkDevice)1,0,1,4096,why,sizeof why));
 puts("placed probe: valid remap and seven failure/cleanup cases passed");return 0;
}
'''

class PlacedMemoryProbeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_advertised_but_broken_maps(self):
        source = (ROOT / 'dm/gfx/vk_android/vka_fd_alias.h').read_text()
        usage = source[source.index('static VkBufferUsageFlags vka_guest_usage'):source.index('typedef struct')]
        with tempfile.TemporaryDirectory(prefix='peanuts-placed-') as td:
            p = Path(td)
            (p / 'test.c').write_text(HARNESS.replace('USAGE', usage))
            subprocess.run(['cc', '-std=gnu11', '-g', '-fsanitize=address,undefined', '-I'+str(ROOT),
                            '-I'+str(ROOT / 'dm/third_party/vulkan-headers/include'), str(p / 'test.c'), '-o', str(p / 'test')], check=True)
            subprocess.run([str(p / 'test')], check=True)

if __name__ == '__main__':
    unittest.main()
