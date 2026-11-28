"""Execute Hyrule Warriors: Age of Calamity's emitted frame-step stubs on ARM64, without game files.

Optional dependency: unicorn. On macOS its JIT needs execution outside the sandbox.
"""
# PEANUTS: the measured update steps of Age of Calamity's frame step (dm/games/01002B00111A2000/patches.py)
import importlib.util
from pathlib import Path
import struct
import sys
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    import unicorn as uc
    from unicorn import arm64_const as arm
except ImportError:
    uc = arm = None

spec = importlib.util.spec_from_file_location('aoc_patches', Path(__file__).resolve().parents[2] / 'games/01002B00111A2000/patches.py')
patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)

FREQ = 19200000          # the Switch's system tick
TASK_SITE, TASK_END = 0x166dab4, 0x166dab8
CLOCK_SITE, CLOCK_END = 0x166dc60, 0x166dc6c
DEVICE_PTR, DEVICE_RATE = 0x2bac798, 0x32d0
BLOCK = 0x269e150
NTSC = 0x1ef5f3c         # 59.94
FRAME_ADD, FRAME_NEXT = 0x857d7c, 0x857d80
LEFT_SUB, LEFT_NEXT = 0x857da0, 0x857da4
UI_COUNT, UI_COUNT_NEXT, UI_CARRY = 0xf87894, 0xf87898, 0x40   # the interface frame counter's add (add w8,w8,#2), the instruction after it, the state's carry   # the timeline's frames-left count (sub w10,w10,#1) and the instruction after it   # the scene timeline's frame advance (add w10,w10,#1) and the instruction after it
STEP_FLAG, LAST_TICK = 0x14, 0x15   # state page: scripts step this update, the tick they last stepped in
TICKS, TICK_FRAC = 0x18, 0x1c   # state page: 1/30 s ticks of game time, and the fraction of one carried


def f32(x): return struct.unpack('<f', struct.pack('<f', x))[0]


@unittest.skipIf(uc is None, 'optional ARM64 execution dependency: unicorn')
class FrameStep(unittest.TestCase):
    base = 0x4000000
    image = 0x2c00000
    area, state = base + 0x2c00000, base + 0x2c20000
    clock, rate, task, device, stack = 0x8000000, 0x8001000, 0x8002000, 0x8004000, 0x8100000

    def setUp(self):
        self.u = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
        self.u.mem_map(self.base, self.image + 0x24000)
        for a in (self.clock, self.rate, self.task): self.u.mem_map(a, 0x1000)
        self.u.mem_map(self.device, 0x4000)   # the KTGL device: its frame rate at +0x32d0
        self.u.mem_map(self.stack, 0x10000)
        self.u.reg_write(arm.UC_ARM64_REG_CPACR_EL1, 3 << 20)   # floating point on
        self.cursor = self.area - self.base
        self.puts = set()
        ctx = types.SimpleNamespace(here=lambda: self.cursor, emit=self.emit, site=lambda off, orig: None,
                                    put=self.put, state=self.state - self.base)
        patch.frame_step(ctx)
        self.u.mem_write(self.base + DEVICE_PTR, struct.pack('<Q', self.device))
        self.u.mem_write(self.base + NTSC, struct.pack('<f', 59.94))
        self.u.reg_write(arm.UC_ARM64_REG_SP, self.stack + 0x8000)
        # The game's frame-rate object: step 1/30 s (timescale 1) at +0x34 and +0x44, fps 30 at +0x48
        self.u.mem_write(self.rate + 0x34, struct.pack('<f', 1 / 30))
        self.u.mem_write(self.rate + 0x44, struct.pack('<f', 1 / 30))
        self.u.mem_write(self.rate + 0x48, struct.pack('<I', 30))
        self.u.mem_write(self.clock + 0x30, struct.pack('<I', 30))
        self.target(60)
        self.tick = 0

    def emit(self, w):
        self.u.mem_write(self.base + self.cursor, struct.pack('<I', w)); self.cursor += 4

    def put(self, off, words, why):
        self.puts.add(off)
        self.u.mem_write(self.base + off, struct.pack('<' + 'I' * len(words), *words))

    def target(self, fps): self.u.mem_write(self.state, struct.pack('<f', fps))
    def f(self, a): return struct.unpack('<f', self.u.mem_read(a, 4))[0]
    def u32(self, a): return struct.unpack('<I', self.u.mem_read(a, 4))[0]
    def u64(self, a): return struct.unpack('<Q', self.u.mem_read(a, 8))[0]

    def execute(self, start, end, *others):
        ends = (end,) + others
        stops = [self.u.hook_add(uc.UC_HOOK_CODE, lambda u, a, n, d: u.emu_stop(), begin=e, end=e) for e in ends]
        try: self.u.emu_start(start, end, count=200)
        finally:
            for h in stops: self.u.hook_del(h)
        pc = self.u.reg_read(arm.UC_ARM64_REG_PC)
        self.assertIn(pc, ends)
        return pc

    def s0(self): return struct.unpack('<f', struct.pack('<I', self.u.reg_read(arm.UC_ARM64_REG_S0) & 0xffffffff))[0]
    def flag(self): return self.u.mem_read(self.state + STEP_FLAG, 1)[0]

    def ticks(self, n=None):
        if n is not None: self.u.mem_write(self.state + TICKS, struct.pack('<I', n))
        return self.u32(self.state + TICKS)

    def run_task(self):
        """The update's start (main+0x166dab4): x19 the clock, x27 the task"""
        self.u.reg_write(arm.UC_ARM64_REG_X19, self.clock)
        self.u.reg_write(arm.UC_ARM64_REG_X27, self.task)
        self.execute(self.base + TASK_SITE, self.base + TASK_END)

    def run_clock(self, seconds):
        """The update's end after its wait (main+0x166dc60): x0 now, x21 the last update's tick, d0 the tick frequency"""
        prev = self.tick; self.tick += round(seconds * FREQ)
        self.u.reg_write(arm.UC_ARM64_REG_X0, self.tick)
        self.u.reg_write(arm.UC_ARM64_REG_X21, prev)
        self.u.reg_write(arm.UC_ARM64_REG_D0, struct.unpack('<Q', struct.pack('<d', float(FREQ)))[0])
        self.u.reg_write(arm.UC_ARM64_REG_X19, self.clock)
        self.u.reg_write(arm.UC_ARM64_REG_X23, self.rate)
        self.execute(self.base + CLOCK_SITE, self.base + CLOCK_END)
        return self.f(self.clock + 0x18), struct.unpack('<f', struct.pack('<I', self.u.reg_read(arm.UC_ARM64_REG_S0) & 0xffffffff))[0]

    def update(self, seconds):
        self.run_task()
        return self.run_clock(seconds)

    def warm(self, seconds=1 / 60):
        self.tick = FREQ * 10   # a last update tick already set, as after the first update
        self.update(seconds)

    # The original bug: at the 60 cap an update that took longer than 1/60 s still stepped 1/60 s, so the game fell behind real time
    def test_game_time_follows_real_time_below_the_cap(self):
        for ups in (60, 56, 50, 45, 40, 31):
            with self.subTest(updates_per_second=ups):
                self.warm(1 / ups)
                game = 0.0
                for _ in range(ups):
                    step, second = self.update(1 / ups)
                    self.assertAlmostEqual(step, second, places=6)
                    game += step
                self.assertAlmostEqual(game, 1.0, places=4)

    def test_a_long_update_steps_at_most_the_switchs_thirtieth(self):
        self.warm()
        for seconds in (1 / 30, 1 / 20, 0.5, 3.0):
            with self.subTest(seconds=seconds):
                self.assertAlmostEqual(self.update(seconds)[0], 1 / 30, places=6)

    def test_first_update_with_no_last_tick_steps_a_thirtieth(self):
        self.tick = FREQ * 1000
        self.u.reg_write(arm.UC_ARM64_REG_X21, 0)
        self.run_task()
        self.u.reg_write(arm.UC_ARM64_REG_X0, self.tick)
        self.u.reg_write(arm.UC_ARM64_REG_X21, 0)
        self.u.reg_write(arm.UC_ARM64_REG_D0, struct.unpack('<Q', struct.pack('<d', float(FREQ)))[0])
        self.u.reg_write(arm.UC_ARM64_REG_X19, self.clock); self.u.reg_write(arm.UC_ARM64_REG_X23, self.rate)
        self.execute(self.base + CLOCK_SITE, self.base + CLOCK_END)
        self.assertAlmostEqual(self.f(self.clock + 0x18), 1 / 30, places=6)

    def test_thirty_cap_keeps_the_switch_steps(self):
        self.target(30)
        self.warm(1 / 30)
        for seconds in (1 / 30, 1 / 30, 1 / 25):
            self.assertAlmostEqual(self.update(seconds)[0], 1 / 30, places=6)
        self.run_task()
        self.assertEqual(self.u32(self.clock + 0x30), 30)
        self.assertAlmostEqual(self.f(self.base + BLOCK + 0xc), 30.0, places=3)
        self.assertAlmostEqual(self.f(self.device + DEVICE_RATE), 29.97, places=3)

    def test_next_update_steps_the_battle_and_events_by_the_last_update(self):
        self.warm()
        self.update(1 / 45)
        self.run_task()
        self.assertEqual(struct.unpack('<4I', self.u.mem_read(self.base + BLOCK, 16))[:3], (60, 1, 60))
        self.assertAlmostEqual(self.f(self.base + BLOCK + 0xc), 45.0, places=2)                 # the battle steps 1 / it
        self.assertAlmostEqual(self.f(self.device + DEVICE_RATE), 59.94 * 45 / 60, places=2)  # the event scripts step by it
        self.assertEqual(self.u32(self.clock + 0x30), 60)                                     # the task still waits for the cap

    def test_before_any_measured_update_the_block_is_the_cap(self):
        self.run_task()
        self.assertAlmostEqual(self.f(self.base + BLOCK + 0xc), 60.0, places=3)
        self.assertAlmostEqual(self.f(self.device + DEVICE_RATE), 59.94, places=3)

    def test_vsyncs_counted_for_the_hosts_speed_line(self):
        self.warm()
        v0 = self.u64(self.state + 0x30)
        for _ in range(45): self.update(1 / 45)
        self.assertEqual(self.u64(self.state + 0x30) - v0, 60)   # 1.000 of real time in "frame step: game time"
        v0 = self.u64(self.state + 0x30)
        for _ in range(60): self.update(1 / 60)
        self.assertEqual(self.u64(self.state + 0x30) - v0, 60)

    def test_target_zero_runs_the_game_original(self):
        self.target(0)
        self.run_task()
        self.assertEqual(self.u32(self.state + 0x28), 0)
        self.assertAlmostEqual(self.f(self.device + DEVICE_RATE), 29.97, places=3)
        self.assertEqual(self.u.mem_read(self.base + BLOCK, 16), bytes(16))
        step, second = self.run_clock(1 / 45)
        self.assertAlmostEqual(step, 1 / 30, places=6)
        self.assertAlmostEqual(second, 1 / 30, places=6)

    # Scripts that wait by yielding resume once an update: 30 a second on the Switch, 60 at the 60 cap (cutscenes and menus at twice their speed)
    def test_ticks_count_thirtieths_of_game_time(self):
        for target, ups in ((60, 60), (60, 45), (30, 30)):
            with self.subTest(target=target, updates_per_second=ups):
                self.target(target); self.warm(1 / ups)
                t0 = self.ticks()
                for _ in range(ups): self.update(1 / ups)
                self.assertEqual(self.ticks() - t0, 30)

    def test_scripts_step_once_a_thirtieth_of_game_time(self):
        for target, ups in ((60, 60), (60, 45), (30, 30)):
            with self.subTest(target=target, updates_per_second=ups):
                self.target(target); self.warm(1 / ups)
                steps = 0
                for _ in range(ups):
                    self.run_task(); steps += self.flag(); self.run_clock(1 / ups)
                self.assertIn(steps, (29, 30, 31))

    # The scripts and the rest of the scene update run every update: holding the scene update, its script call or the KIDS timeline list task on the other
    # updates left grey frames or characters missing every other frame on the Thor
    def test_scene_update_and_scripts_are_not_held(self):
        self.assertEqual(self.puts, {0x166dab4, 0x166dc60, 0x8d94b0, FRAME_ADD, LEFT_SUB, UI_COUNT})

    def frame_advance(self, frame):
        self.u.reg_write(arm.UC_ARM64_REG_X10, frame)
        self.execute(self.base + FRAME_ADD, self.base + FRAME_NEXT)
        return self.u.reg_read(arm.UC_ARM64_REG_X10) & 0xffffffff

    # The scene timeline's frame advances natively once a call (main+0x857d7c): at 60 updates real-time cutscenes ran at twice their speed
    def test_scene_frame_advances_only_on_a_new_tick(self):
        self.run_task()
        self.u.mem_write(self.state + STEP_FLAG, b'\1'); self.assertEqual(self.frame_advance(5), 6)
        self.u.mem_write(self.state + STEP_FLAG, b'\0'); self.assertEqual(self.frame_advance(5), 5)
        self.target(0); self.run_task(); self.assertEqual(self.frame_advance(5), 6)

    def test_scene_frames_advance_thirty_a_second(self):
        for target, ups in ((60, 60), (60, 50), (30, 30)):
            with self.subTest(target=target, updates_per_second=ups):
                self.target(target); self.warm(1 / ups)
                frame = 0
                for _ in range(ups):
                    self.run_task(); frame = self.frame_advance(frame); self.run_clock(1 / ups)
                self.assertIn(frame, (29, 30, 31))


    def frames_left(self, left):
        self.u.reg_write(arm.UC_ARM64_REG_X10, left)
        self.execute(self.base + LEFT_SUB, self.base + LEFT_NEXT)
        return self.u.reg_read(arm.UC_ARM64_REG_X10) & 0xffffffff

    # Beside the frame, the timeline counts the frames it has left (+4, main+0x857da0) once a call; below 2 it stops. Counted once an update with the frame held,
    # it ran out halfway and the cutscene froze on a frame until skipped (Thor, 2026-11-26)
    def test_frames_left_count_down_only_on_a_new_tick(self):
        self.run_task()
        self.u.mem_write(self.state + STEP_FLAG, b'\1'); self.assertEqual(self.frames_left(10), 9)
        self.u.mem_write(self.state + STEP_FLAG, b'\0'); self.assertEqual(self.frames_left(10), 10)
        self.target(0); self.run_task(); self.assertEqual(self.frames_left(10), 9)

    def test_frame_and_frames_left_stay_together(self):
        for target, ups in ((60, 60), (60, 50), (30, 30)):
            with self.subTest(target=target, updates_per_second=ups):
                self.target(target); self.warm(1 / ups)
                frame, left = 0, 100
                for _ in range(ups):
                    self.run_task(); frame = self.frame_advance(frame); left = self.frames_left(left); self.run_clock(1 / ups)
                self.assertEqual(frame + left, 100)


    def ui_counter(self, count, seconds):
        """The interface frame counter's add (main+0xf87894): w8 the counter, s8 the update's time; the counter after it"""
        self.u.reg_write(arm.UC_ARM64_REG_X8, count)
        self.u.reg_write(arm.UC_ARM64_REG_S8, struct.unpack('<I', struct.pack('<f', seconds))[0])
        self.execute(self.base + UI_COUNT, self.base + UI_COUNT_NEXT)
        return self.u.reg_read(arm.UC_ARM64_REG_X8) & 0xffffffff

    # The map's and the HUD's blinking icons take their frames from an interface counter that added 2 an update (the Switch's 1/30 s): twice their speed at 60
    def test_ui_counter_adds_the_updates_frames(self):
        self.assertEqual(self.ui_counter(20, 1 / 30), 22)   # as on the Switch
        self.u.mem_write(self.state + UI_CARRY, bytes(4))
        self.assertEqual(self.ui_counter(20, 1 / 60), 21)

    def test_ui_counter_counts_sixty_a_second(self):
        for ups in (60, 45, 50, 30):
            with self.subTest(updates_per_second=ups):
                self.u.mem_write(self.state + UI_CARRY, bytes(4))
                count = 0
                for _ in range(ups): count = self.ui_counter(count, 1 / ups)
                self.assertIn(count, (59, 60))

if __name__ == '__main__':
    unittest.main()
