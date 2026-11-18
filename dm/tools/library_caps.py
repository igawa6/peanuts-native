#!/usr/bin/env python3
"""What a game's Android shader library needs of the GPU, for its readiness.tsv rows (dm/games/README.md "Readiness rows"): the Vulkan features and limits its modules use,
counted from the library's index.json (the SPIR-V capabilities each module declares, its shared memory, mesh programs).

    python3 dm/tools/library_caps.py <library folder v-<VK chain>>      (the folder holding index.json)
"""
import collections
import json
import sys
from pathlib import Path

FEATURE = {   # SPIR-V capability -> the VkPhysicalDeviceFeatures member (or the subgroup / other fact) a readiness row names
    'Geometry': 'feature.geometryShader',
    'Tessellation': 'feature.tessellationShader',
    'DrawParameters': 'feature.shaderDrawParameters',
    'StorageImageWriteWithoutFormat': 'feature.shaderStorageImageWriteWithoutFormat',
    'StorageImageReadWithoutFormat': 'feature.shaderStorageImageReadWithoutFormat',
    'Int64': 'feature.shaderInt64',
    'PhysicalStorageBufferAddresses': 'feature.bufferDeviceAddress',
    'Float64': 'feature.shaderFloat64',
    'GroupNonUniformBallot': 'subgroup ballot (not a feature row)',
    'GroupNonUniformShuffle': 'subgroup shuffle (not a feature row)',
    'GroupNonUniformVote': 'subgroup vote (not a feature row)',
}


def main(folder):
    d = json.loads((Path(folder) / 'index.json').read_text())
    mods = d['shaders']
    caps = collections.Counter(); stages = collections.Counter(); shared = collections.Counter(); mesh = 0
    for m in mods.values():
        stages[m.get('stage')] += 1
        for c in m.get('caps', []):
            if c in FEATURE: caps[c] += 1
        s = m.get('shared') or 0
        if s > 16384: shared[s] += 1
        mesh += bool(m.get('mesh'))
    print(f"{len(mods)} modules; stages {dict(stages)}; library {d.get('vk_chain')}, game {d.get('game')}")
    for c, n in sorted(caps.items(), key=lambda x: -x[1]): print(f'  {n:6}  {c:34} -> {FEATURE[c]}')
    print(f'  max shared memory over Vulkan\'s minimum of 16384: {dict(shared) or "none"}; mesh / task programs: {mesh}')


if __name__ == '__main__':
    if len(sys.argv) != 2: sys.exit(__doc__)
    main(sys.argv[1])
