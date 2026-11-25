"""PEANUTS(upstream-candidate): the offline shaders shader_split.raw_shaders takes from a main when the code builds no address to
their code area. FINAL FANTASY XII THE ZODIAC AGE's main (0100EB100AB42000) has 7 control sections whose
code area lies right before them, with no relocation slot pointing at either and no adrp + add building an
address near them, so the built-address rule that picks between the two neighbours has nothing to choose
by and they were left out (the game asked the host for exactly those programs and drew black screens).
The module image is synthetic: one control section (its own length at +0x20, the code length at +0x704) and
its neighbours, with no relocations and no adrp + add, so rel_targets and adrp_targets find nothing.
The unaligned cases take the control sections the aligned scan leaves out: one at an odd offset is found through
the same neighbour rule, one whose neighbours all fail the checks is left out without a [RED] line, a match inside
a program already taken is not taken a second time, and the aligned [RED] line is unchanged by them.
No game files needed."""
import contextlib
import io
import os
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '0100620012D6E000'   # shader_split loads a profile at import (any existing one: the module image is synthetic); other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

NCTL, NCODE, CTL = 0x850, 0x400, 0x1400   # control section length, code area length, the control section's offset (4-aligned)
UCTL, NRAW = 0x2405, 0x200                # an unaligned control section's offset (odd: & 3 leaves a 1), the code length its +0x704 gives (as FFXII's 0xfca50d)
SPH_VS, SPH_NONE = 0x400, 0x7c00         # SPH word 0: shader type 1 (bits 10..13) = vertex; type 15 is no stage


def adrp(rd, pc, target):
    """The ADRP instruction at module offset pc whose page is the 4 KB page of target (adrp_targets: page =
    the instruction's own page + the signed page offset << 12, and the add on the same register within
    the next 8 instructions supplies the low 12 bits). The test's pages lie above their instruction, so
    the page offset is a small positive number, the case adrp_targets' sign extension covers"""
    imm = ((target & ~0xfff) - (pc & ~0xfff)) >> 12
    return 0x90000000 | ((imm >> 2 & 0x7ffff) << 5) | ((imm & 3) << 29) | rd


def add(rd, imm12):
    return 0x91000000 | (imm12 << 10) | (rd << 5) | rd


def image(code_at, code_words=None):
    """A synthetic module image: the control section at CTL, its own length at CTL+0x20 and the code area's
    length (NCODE) at CTL+0x704, and at each offset in code_at a code area of NCODE bytes that starts with
    the graphics header (its SPH word 0 from code_words, by default a vertex shader). Every other word is
    zero, so nothing relocates and no adrp is decoded there."""
    d = bytearray(0x3000)
    d[CTL:CTL + 4] = ss.CTL_MAGIC
    struct.pack_into('<I', d, CTL + 0x20, NCTL)
    struct.pack_into('<I', d, CTL + 0x704, NCODE)
    for i, k in enumerate(code_at):
        d[k:k + 4] = ss.GFX_MAGIC
        struct.pack_into('<I', d, k + 0x30, SPH_VS)
        struct.pack_into('<I', d, k + 0x34, 0x11111111 + i)   # tells one code area from the other
        if code_words: struct.pack_into('<I', d, k + 0x30, code_words[i])
    return bytes(d)


def unaligned_image(at=UCTL, n=NRAW, sph=SPH_VS):
    """A synthetic module image with one control section at the unaligned offset at (odd, so the 4-aligned scan
    never looks at it): its own length NCTL at +0x20, the code length n at +0x704, and at at - n the code area
    that length describes, its header naming the stage sph. Every other word is zero."""
    d = bytearray(0x3000)
    d[at:at + 4] = ss.CTL_MAGIC
    struct.pack_into('<I', d, at + 0x20, NCTL)
    struct.pack_into('<I', d, at + 0x704, n)
    d[at - n:at - n + 4] = ss.GFX_MAGIC
    struct.pack_into('<I', d, at - n + 0x30, sph)
    struct.pack_into('<I', d, at - n + 0x34, 0x22222222)   # tells this code area from the ones image() lays out
    return d


class RawShaders(unittest.TestCase):
    def shaders(self, d):
        """raw_shaders' output, with its [RED] lines kept for the tests that need them"""
        log = io.StringIO()
        with contextlib.redirect_stdout(log): out = ss.raw_shaders(d, 'main')
        return out, log.getvalue()

    def test_code_area_before_the_control_section(self):
        # the case the built-address rule cannot reach: the code area right before, the one after no shader
        d = image([CTL - NCODE, CTL + NCTL], code_words=[SPH_VS, SPH_NONE])
        out, log = self.shaders(d)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][:2], (f'main#RAW@{CTL:#x}', 'vs'))
        self.assertEqual(out[0][2], d[CTL:CTL + NCTL])
        self.assertEqual(out[0][3], d[CTL - NCODE:CTL - NCODE + NCODE])
        self.assertEqual(struct.unpack_from('<I', out[0][3], 0x34)[0], 0x11111111)
        self.assertNotIn('[RED]', log)

    def test_both_neighbours_are_valid(self):
        # two neighbours pass the checks: which one the control section owns stays unknown, so neither is taken
        out, log = self.shaders(image([CTL - NCODE, CTL + NCTL]))
        self.assertEqual(out, [])
        self.assertIn('[RED] main control section @0x1400:', log)
        self.assertIn('left out', log)

    def test_no_neighbour_is_valid(self):
        # nothing before (only zeros), and a header after whose SPH names no stage
        out, log = self.shaders(image([CTL + NCTL], code_words=[SPH_NONE]))
        self.assertEqual(out, [])
        self.assertIn('[RED] main control section @0x1400:', log)
        self.assertIn('left out', log)

    def test_built_address_rule_still_picks_the_nearest(self):
        # both neighbours are valid and the code builds both their addresses: as before the change, the one
        # whose address is built nearest the control section's wins (its add 8 bytes from the add that
        # builds the control section's address, the other's 0x18 bytes from it)
        d = bytearray(image([CTL - NCODE, CTL + NCTL]))
        for off, w in ((0x100, adrp(6, 0x100, CTL - NCODE)), (0x104, add(6, (CTL - NCODE) & 0xfff)),
                      (0x108, adrp(5, 0x108, CTL)), (0x10c, add(5, CTL & 0xfff)),
                      (0x120, adrp(7, 0x120, CTL + NCTL)), (0x124, add(7, (CTL + NCTL) & 0xfff))):
            struct.pack_into('<I', d, off, w)
        out, log = self.shaders(bytes(d))
        self.assertEqual([(w, st) for w, st, _, _ in out], [('main#RAW@0x1400', 'vs')])
        self.assertEqual(struct.unpack_from('<I', out[0][3], 0x34)[0], 0x11111111)   # the nearer code area, the one before
        self.assertNotIn('[RED]', log)

    def test_unaligned_control_section_takes_the_area_before_it(self):
        # the case the aligned scan leaves out (FFXII's control 0xfca50d, code area at 0xfca30d): an odd offset,
        # its code area the NRAW bytes right before it, nothing after it
        d = unaligned_image()
        out, log = self.shaders(bytes(d))
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][:2], (f'main#RAW@{UCTL:#x}', 'vs'))
        self.assertEqual(out[0][2], bytes(d[UCTL:UCTL + NCTL]))
        self.assertEqual(out[0][3], bytes(d[UCTL - NRAW:UCTL]))
        self.assertEqual(struct.unpack_from('<I', out[0][3], 0x34)[0], 0x22222222)
        self.assertNotIn('[RED]', log)

    def test_unaligned_control_section_with_no_valid_neighbour(self):
        # an odd offset whose neighbours are both nothing: left out, and no [RED] line (a control magic at an
        # odd offset is very often just bytes)
        out, log = self.shaders(bytes(unaligned_image(sph=SPH_NONE)))
        self.assertEqual(out, [])
        self.assertNotIn('[RED]', log)

    def test_unaligned_match_inside_a_taken_program_is_dropped(self):
        # a second control magic above the taken program's control section, 0x1fe bytes into it, whose code area
        # (its +0x704 length 0x1b3) lies inside that control section too: the bytes of the program taken at
        # 0x2405, so it is not taken a second time, even though its own neighbour before it passes the checks
        d = unaligned_image()
        d[0x2603:0x2607] = ss.CTL_MAGIC
        struct.pack_into('<I', d, 0x2603 + 0x20, NCTL)
        struct.pack_into('<I', d, 0x2603 + 0x704, 0x1b3)
        d[0x2450:0x2454] = ss.GFX_MAGIC   # the code area of the second match, inside the program taken first
        struct.pack_into('<I', d, 0x2480, SPH_VS)
        struct.pack_into('<I', d, 0x2484, 0x33333333)
        out, log = self.shaders(bytes(d))
        self.assertEqual([(w, st) for w, st, _, _ in out], [(f'main#RAW@{UCTL:#x}', 'vs')])
        self.assertEqual(struct.unpack_from('<I', out[0][3], 0x34)[0], 0x22222222)   # the program of unaligned_image, not the second match's
        self.assertNotIn('[RED]', log)

    def test_aligned_red_line_unchanged_by_an_unaligned_match(self):
        # the aligned control section of test_no_neighbour_is_valid, with an unaligned one beside it that finds
        # no valid neighbour: the aligned [RED] line stays as it was, the unaligned one prints nothing
        d = bytearray(image([CTL + NCTL], code_words=[SPH_NONE]))
        d[UCTL:UCTL + 4] = ss.CTL_MAGIC
        struct.pack_into('<I', d, UCTL + 0x20, NCTL)
        struct.pack_into('<I', d, UCTL + 0x704, NRAW)
        d[UCTL - NRAW:UCTL - NRAW + 4] = ss.GFX_MAGIC
        struct.pack_into('<I', d, UCTL - NRAW + 0x30, SPH_NONE)
        out, log = self.shaders(bytes(d))
        self.assertEqual(out, [])
        self.assertIn('[RED] main control section @0x1400:', log)
        self.assertIn('left out', log)
        self.assertNotIn(f'@{UCTL:#x}', log)


if __name__ == '__main__':
    unittest.main()
