# PEANUTS: nxnative b32d0bf3 allocator adaptation; build/function gated.
# Xenoblade Chronicles X DE: changes to the game's own module, by NSO build ID.
# Nintendo SDK changes belong to dm/tools/sdk_patches.py.
# Keep only the atomic matcher: upstream's fixed CUTS belong to 1.0.1,
# which is not registered here. On local 1.0.2 the two full functions match,
# and all six original bytes are checked before applying any change.
# This platform-limit workaround retains nxnative's 4 GB outer reservation
# and 512 MB + 128 KB first arena; later-game capacity is unmeasured.
# Both complete functions and their callees match in base 1.0.0 and 1.0.1.
# The base is static enrollment evidence, not another supported build row.
# Upstream checked a forged 1.0.1 build ID against its fixed-cut output.
MATCH_CUTS = {'main': [
    {'name': 'Monolith malloc region and first arena', 'functions': [
        {'size': 696, 'fingerprints': ['b00403a9284d46d15659fc765ce9c0d0474170eb6b9fe2369a4fb69f5458bba2'], 'cuts': [
            (0x10, 'e98340b2', 'e9ff9fd2', 'first arena bump size, low part: mov x9,#0xffff'),
            (0x20, '4900a0f2', '4900a4f2', 'first arena bump size: 512 MB + 128 KB + 64 KB - 1'),
            (0xa4, 'ea7b5fb2', 'ea8361b2', 'first arena subtraction, sign extension'),
            (0xac, 'caffbff2', 'caffbbf2', 'first arena subtraction: -(512 MB + 128 KB)'),
            (0xbc, '2800c0f2', '4800a4f2', 'first arena stored capacity: 512 MB + 128 KB'),
        ]},
        {'size': 16, 'fingerprints': ['df6920c64d84efc7c01f51f9b17e9bd252f9058ed5903bfe6fd5a7ff1a7f4ca3'], 'cuts': [
            (0, 'e001c0d2', '2000c0d2', 'malloc address region: 60 GB to 4 GB'),
        ]},
    ]},
]}

BINDING = [
    {'what': 'malloc region 60 GB → 4 GB and its first arena', 'now': 'game', 'goal': 'game', 'purpose': 'address space',
     'why': "Monolith's allocator calls nn::os directly; its sizes span two functions matched atomically. The 512 MB first arena is sized for this game; an engine rule needs shared-layout proof and dynamic sizing",
     'checked': 'both complete functions and their callees in base 1.0.0 and update 1.0.1; forged-build rewrite byte-identical; 1.0.1 opening-field gameplay with the iPhone alias size, first arena 95 MB committed'},
]
