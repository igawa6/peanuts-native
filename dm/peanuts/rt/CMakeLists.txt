# peanuts_rt: the Eden Duo DSMod companion runtime (v1.4.0, runtime 25) lifted out of the emulator (dm/peanuts/rt/mods,
# original file names) behind a set of shim headers (dm/peanuts/rt/shim) and a tiny C API
# (peanuts_rt.h). Builds as a static library for Linux hosts and arm64 Android (NDK toolchain).
#
#   host:  cmake -S dm/peanuts/rt -B build-host -DPEANUTS_RT_TEST=ON && cmake --build build-host
#   arm64: cmake -S dm/peanuts/rt -B build-arm64 -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#          -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 -DPEANUTS_RT_TEST=OFF
cmake_minimum_required(VERSION 3.22)
project(peanuts_rt LANGUAGES C CXX)

option(PEANUTS_RT_TEST "Build the Linux host test program rt_test" OFF)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

set(PNRT_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(PNRT_THIRD_PARTY ${PNRT_DIR}/../third_party)

# The lifted files include each other as "core/mods/<name>" (unchanged Eden paths). Rather than a
# symlink in the tree, a generated include root maps core/mods -> dm/peanuts/rt/mods.
set(PNRT_INC_ROOT ${CMAKE_CURRENT_BINARY_DIR}/pnrt_include)
file(MAKE_DIRECTORY ${PNRT_INC_ROOT}/core)
file(CREATE_LINK ${PNRT_DIR}/mods ${PNRT_INC_ROOT}/core/mods RESULT pnrt_link SYMBOLIC)
if(NOT pnrt_link EQUAL 0 AND CMAKE_HOST_WIN32)
  # Windows makes symbolic links only with Developer Mode or as administrator; a directory junction needs neither
  if(IS_DIRECTORY ${PNRT_INC_ROOT}/core/mods)
    set(pnrt_link 0)   # the junction of an earlier configure
  else()
    file(TO_NATIVE_PATH ${PNRT_INC_ROOT}/core/mods pnrt_link_n)
    file(TO_NATIVE_PATH ${PNRT_DIR}/mods pnrt_dir_n)
    execute_process(COMMAND cmd /c mklink /J ${pnrt_link_n} ${pnrt_dir_n} RESULT_VARIABLE pnrt_link OUTPUT_QUIET)
  endif()
endif()
if(NOT pnrt_link EQUAL 0)
  message(FATAL_ERROR "cannot link ${PNRT_INC_ROOT}/core/mods to ${PNRT_DIR}/mods: ${pnrt_link}")
endif()

set(PNRT_LIFTED
  mods/engine_ichigo.cpp
  mods/engine_il2cpp.cpp
  mods/engine_mercury.cpp
  mods/mod_actions.cpp
  mods/mod_assets.cpp
  mods/mod_clock.cpp
  mods/mod_expr.cpp
  mods/mod_font_pages.cpp
  mods/mod_host_values.cpp
  mods/mod_input.cpp
  mods/mod_launch_gate.cpp
  mods/mod_load_plan.cpp
  mods/mod_manifest.cpp
  mods/mod_map.cpp
  mods/mod_module.cpp
  mods/mod_module_host.cpp
  mods/mod_module_services.cpp
  mods/mod_msbt.cpp
  mods/mod_nav.cpp
  mods/mod_nx_assets.cpp
  mods/mod_nx_runtime.cpp
  mods/mod_pages.cpp
  mods/mod_persist.cpp
  mods/mod_redraw.cpp
  mods/mod_romfs_sources.cpp
  mods/mod_runtime.cpp
  mods/mod_settings.cpp
  mods/mod_sources.cpp
  mods/mod_state.cpp
  mods/mod_sysfont_source.cpp
  mods/mod_texture_readback.cpp
  mods/mod_ui.cpp
  mods/mod_ui_canvas.cpp
  mods/mod_ui_expand.cpp
  mods/mod_ui_image.cpp
  mods/mod_ui_map_widget.cpp
  mods/mod_ui_scroll.cpp
  mods/mod_ui_text.cpp
  mods/mod_ui_transitions.cpp
  mods/mod_ui_widget_state.cpp
  mods/mod_user_source.cpp
)

add_library(peanuts_rt STATIC
  ${PNRT_LIFTED}
  mods/pnrt_stubs.cpp
  shim/shim.cpp
  shim/shim_r5.cpp
  shim/shim_readback.cpp
  shim/shim_services.cpp
  shim/stb_impl.cpp
  ${PNRT_THIRD_PARTY}/bc_decoder/bc_decoder.cpp
  ${PNRT_THIRD_PARTY}/astc/video_core/textures/astc.cpp
  peanuts_rt.cpp
  peanuts_helper.cpp
  peanuts_patches.cpp
)
target_compile_features(peanuts_rt PUBLIC cxx_std_20)
target_include_directories(peanuts_rt
  PUBLIC ${PNRT_DIR}
  PRIVATE ${PNRT_DIR}/shim ${PNRT_INC_ROOT} ${PNRT_THIRD_PARTY} ${PNRT_THIRD_PARTY}/stb
          ${PNRT_THIRD_PARTY}/bc_decoder ${PNRT_THIRD_PARTY}/astc)
target_compile_definitions(peanuts_rt PRIVATE PNRT=1 EDEN_DSMOD_BUILD_DEV_TOOLS=0)
target_compile_options(peanuts_rt PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden
  -Wall -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable
  -Wno-missing-field-initializers)
find_package(Threads REQUIRED)
# dlopen (mod_module.cpp loads a package's native module through Common::DynamicLibrary).
target_link_libraries(peanuts_rt PUBLIC Threads::Threads ${CMAKE_DL_LIBS})
# zlib: engine_mercury.cpp inflates the gzip stream inside a Dread .bctex (InflateGzip). The NDK
# ships libz.so; FindZLIB finds it through the toolchain sysroot, and `z` is the fallback either way.
find_package(ZLIB)
if(ZLIB_FOUND)
  target_link_libraries(peanuts_rt PUBLIC ZLIB::ZLIB)
else()
  target_link_libraries(peanuts_rt PUBLIC z)
endif()
if(ANDROID)
  target_link_libraries(peanuts_rt PUBLIC log)
  # The NDK's libc++ keeps std::jthread / std::stop_token (the redraw worker) experimental; Eden's
  # own Android build sets the same flag (eden/CMakeLists.txt).
  target_compile_options(peanuts_rt PUBLIC -fexperimental-library)
  target_link_options(peanuts_rt PUBLIC -fexperimental-library)
endif()

if(PEANUTS_RT_TEST)
  # The host services against a fake host (texture readback through the lifted broker, sysfont:, aoc:, @device.*, @perf.*, manifest patches): the shim and
  # lifted headers it includes are the library's private ones
  add_executable(svc_test test/svc_test.cpp)
  target_link_libraries(svc_test PRIVATE peanuts_rt)
  target_include_directories(svc_test PRIVATE ${PNRT_DIR}/shim ${PNRT_INC_ROOT} ${PNRT_THIRD_PARTY})
  target_compile_definitions(svc_test PRIVATE PNRT=1 EDEN_DSMOD_BUILD_DEV_TOOLS=0)
  # What the host side owes the lifted runtime (the stop of the game's threads, the controller, a load plan on a synthetic main, the hidden page)
  add_executable(gaps_test test/gaps_test.cpp)
  target_link_libraries(gaps_test PRIVATE peanuts_rt)
  target_include_directories(gaps_test PRIVATE ${PNRT_DIR}/shim ${PNRT_INC_ROOT} ${PNRT_THIRD_PARTY})
  target_compile_definitions(gaps_test PRIVATE PNRT=1 EDEN_DSMOD_BUILD_DEV_TOOLS=0)
  # The replay's texture readback (dm/gfx/vk_android/vka_readback.inc) against a stub Vulkan table: plain C, nothing of the runtime
  add_executable(vka_readback_test test/vka_readback_test.c)
  target_link_libraries(vka_readback_test PRIVATE Threads::Threads)
  target_compile_options(vka_readback_test PRIVATE -std=gnu11 -Wall -Wno-unused-function -Wno-enum-compare)
  # `ctest` in the build folder (or the `test` target) runs the three
  enable_testing()
  add_test(NAME svc_test COMMAND svc_test)
  add_test(NAME gaps_test COMMAND gaps_test ${CMAKE_CURRENT_BINARY_DIR}/gaps_test.scratch)
  add_test(NAME vka_readback_test COMMAND vka_readback_test)
  add_executable(rt_test test/rt_test.cpp)
  target_link_libraries(rt_test PRIVATE peanuts_rt)
  target_include_directories(rt_test PRIVATE ${PNRT_THIRD_PARTY}/stb ${PNRT_THIRD_PARTY})
  # The UiSink hooks of shim/video_core/dsmod/aux_routing.h: a partial publish reaches the host's sink as its own box.
  add_executable(aux_sink_test test/aux_sink_test.cpp)
  target_link_libraries(aux_sink_test PRIVATE peanuts_rt)
  target_include_directories(aux_sink_test PRIVATE ${PNRT_DIR}/shim ${PNRT_INC_ROOT} ${PNRT_THIRD_PARTY})
  target_compile_definitions(aux_sink_test PRIVATE PNRT=1 EDEN_DSMOD_BUILD_DEV_TOOLS=0)
  # R6: the host's .nup RomFS provider (dm/peanuts/peanuts_nup.c over dm/host/nup_image.h) read on this PC. nup_image.h
  # includes dm/host/plat/plat.h, which refuses any target but Apple / Android arm64: the provider unit alone is compiled
  # as if for Android (declarations only; nothing of plat.h is called but plat_sha256, which nup_test defines over the
  # shim's SHA256), with the x86 asm/sigcontext.h guard predefined so plat.h's <asm/sigcontext.h> stays out.
  if(NOT ANDROID AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    add_executable(nup_test test/nup_test.cpp ${PNRT_DIR}/../peanuts_nup.c ${PNRT_DIR}/../../third_party/zstd/zstddeclib.c)
    target_link_libraries(nup_test PRIVATE peanuts_rt)
    target_include_directories(nup_test PRIVATE ${PNRT_DIR}/..)
    set_source_files_properties(${PNRT_DIR}/../peanuts_nup.c PROPERTIES
      COMPILE_DEFINITIONS "DM_NUP_ZSTD;PN_NUP_HOST_TEST;__ANDROID__=1;__aarch64__=1;_ASM_X86_SIGCONTEXT_H"
      COMPILE_OPTIONS "-Wno-unused-function;-std=gnu11")
    set_source_files_properties(${PNRT_DIR}/../../third_party/zstd/zstddeclib.c PROPERTIES COMPILE_OPTIONS "-O2;-w")
  endif()
endif()
