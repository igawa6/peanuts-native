# libnximport — Peanuts Native's importer (nximport.h), plain C11. Two ways in:
#   - the app's native build adds this folder (android/app/src/main/cpp/CMakeLists.txt, one PEANUTS hook line, see HOOKS.md): target `nximport`,
#     a shared library libnximport.so with the JNI (dev.peanuts.importer.Native), 16 KB page aligned like the host's libraries;
#   - standalone on a PC for the tests: cmake -S dm/peanuts/import -B build && cmake --build build → nximport_cli (and libnximport.so).
#   - standalone on Windows with MinGW-w64 GCC (MSYS2 UCRT64 or WinLibs): cmake -G "MinGW Makefiles" -S dm/peanuts/import -B build; MSVC is not supported.
#     No libnximport there (its hidden-visibility export has no use on Windows): nximport_cli, comp_test, zip_test, crypto_test and lz4_test, linked -static so the .exe runs alone.
#     Needs zlib with its static library there too (MSYS2: mingw-w64-ucrt-x86_64-zlib).
cmake_minimum_required(VERSION 3.22)
project(nximport C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(NXIMPORT_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_icon.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_zip.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nximport.c)
if(ANDROID)
  add_library(nximport SHARED ${NXIMPORT_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/nximport_jni.c)
  target_link_options(nximport PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
  target_link_libraries(nximport PRIVATE log z)   # z: zlib, for a deflated member of a firmware .zip (nx_zip.c / nximport.c font_try_zip_deflate); the NDK ships it
else()
  find_package(Threads REQUIRED)   # the writer's pipeline (pthreads; bionic has them in libc)
  find_package(ZLIB REQUIRED)      # a deflated member of a firmware .zip (nximport.c font_try_zip_deflate)
  if(NOT WIN32)
    add_library(nximport SHARED ${NXIMPORT_SOURCES})
    target_link_libraries(nximport PRIVATE Threads::Threads ZLIB::ZLIB)
  endif()
  add_executable(nximport_cli ${CMAKE_CURRENT_SOURCE_DIR}/nximport_cli.c ${NXIMPORT_SOURCES})   # the sources themselves: its ncainfo command uses the internals
  target_include_directories(nximport_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(nximport_cli PRIVATE Threads::Threads ZLIB::ZLIB)
  target_compile_options(nximport_cli PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  add_executable(comp_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/comp_test.c ${NXIMPORT_SOURCES})   # the compression layer against the reference LZ4 (tests/comp_check.py)
  target_include_directories(comp_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(comp_test PRIVATE Threads::Threads ZLIB::ZLIB)
  target_compile_options(comp_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  add_executable(zip_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/zip_test.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_zip.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c)   # nx_zip.c against a synthetic fixture (tests/make_zip_fixture.py); the rest only for nx_err/nx_pread_all (nx_nca.c references nx_lz4_decompress even unused here), no keys needed
  target_include_directories(zip_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(zip_test PRIVATE Threads::Threads ZLIB::ZLIB)   # Threads: nx_nca.c's shared cache of decoded entries has locks
  target_compile_options(zip_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  # the AES and LZ4 routines alone: known answers, the hardware cipher against the portable one, the LZ4 decoder against the plain one it replaced
  # (tests/nx_selftest.h); no keys and no game needed: ctest, or run them by hand (once under -DCMAKE_C_FLAGS=-fsanitize=address,undefined)
  add_executable(crypto_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/crypto_test.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c)
  add_executable(lz4_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/lz4_test.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c)
  foreach(t crypto_test lz4_test)
    target_include_directories(${t} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(${t} PRIVATE NX_TEST_HOOKS)   # nx_aes_test_portable (and, below, the shared cache's size and counters): in the test programs only
    target_compile_options(${t} PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
    if(WIN32)
      target_link_options(${t} PRIVATE -static)
    endif()
  endforeach()
  enable_testing()
  add_test(NAME crypto_test COMMAND crypto_test)
  add_test(NAME lz4_test COMMAND lz4_test)
  if(NOT WIN32)
    add_executable(font_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/font_test.c ${NXIMPORT_SOURCES})
    target_include_directories(font_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(font_test PRIVATE Threads::Threads ZLIB::ZLIB)
    target_compile_options(font_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
    add_test(NAME font_test COMMAND font_test)
  endif()
  # The RomFS read straight from dumps at run time (dm/peanuts/peanuts_nsp.c; peanuts/docs/NSP-DIRECT.md), on x86-64 Linux:
  #   nsp_test     no game data: dumps written by the test with made-up keys (tests/nsp_fixture.h), read through the importer, through the host's file system
  #                (dm/host/hle_fs.c, the file as it is) beside the .nup the importer makes of them, and through the companion runtime's provider
  #   nsp_compare  a real game both ways, every byte compared; skipped unless NSP_CMP_KEYS, NSP_CMP_BASE and NSP_CMP_NUP name the files (tests/nsp_compare.c)
  # The host's headers refuse any target but Apple and Android arm64 (dm/host/plat/plat.h): the units that include them are compiled as if for Android arm64
  # (declarations only, as dm/peanuts/rt's nup_test does), and the host functions hle_fs.c calls are stood in by the test. dm.h wants a game profile header:
  # any profile does (the test sets its own title), so one is generated for the first game of dm/games' list.
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    get_filename_component(NSP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
    set(NSP_GAME 0100000000010000)
    set(NSP_GEN ${CMAKE_CURRENT_BINARY_DIR}/nsp_test_gen)
    add_custom_command(OUTPUT ${NSP_GEN}/game_profile.h
      COMMAND ${CMAKE_COMMAND} -E make_directory ${NSP_GEN}
      COMMAND ${Python3_EXECUTABLE} ${NSP_ROOT}/dm/games/game.py header ${NSP_GAME} ${NSP_GEN}/game_profile.h
      DEPENDS ${NSP_ROOT}/dm/games/${NSP_GAME}/game.tsv ${NSP_ROOT}/dm/games/game.py
      COMMENT "game profile ${NSP_GAME} for nsp_test")
    set(NSP_AS_HOST "DM_NUP_ZSTD;DM_NSP_DIRECT;_GNU_SOURCE;__ANDROID__=1;__aarch64__=1;_ASM_X86_SIGCONTEXT_H")
    set(NSP_HOST_UNITS ${CMAKE_CURRENT_SOURCE_DIR}/tests/nsp_test.c ${CMAKE_CURRENT_SOURCE_DIR}/tests/nsp_compare.c ${NSP_ROOT}/dm/host/save_sync.c)
    set_source_files_properties(${NSP_HOST_UNITS} PROPERTIES COMPILE_DEFINITIONS "${NSP_AS_HOST}" COMPILE_OPTIONS "-Wno-unused-function;-Wno-format")
    set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/../peanuts_nup.c PROPERTIES COMPILE_DEFINITIONS "${NSP_AS_HOST};PN_NUP_HOST_TEST" COMPILE_OPTIONS "-Wno-unused-function")
    set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/../peanuts_nsp.c PROPERTIES COMPILE_DEFINITIONS "DM_NSP_DIRECT" COMPILE_OPTIONS "-Wall;-Wextra;-Wno-unused-function")
    set_source_files_properties(${NSP_ROOT}/dm/third_party/zstd/zstddeclib.c PROPERTIES COMPILE_OPTIONS "-w")
    add_executable(nsp_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/nsp_test.c ${NSP_GEN}/game_profile.h ${CMAKE_CURRENT_SOURCE_DIR}/../peanuts_nsp.c ${CMAKE_CURRENT_SOURCE_DIR}/../peanuts_nup.c
                   ${NSP_ROOT}/dm/host/save_sync.c ${NSP_ROOT}/dm/third_party/zstd/zstddeclib.c ${NXIMPORT_SOURCES})
    target_include_directories(nsp_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${NSP_ROOT}/dm/host ${NSP_GEN} ${NSP_ROOT}/dm/games/${NSP_GAME})
    add_executable(nsp_compare ${CMAKE_CURRENT_SOURCE_DIR}/tests/nsp_compare.c ${CMAKE_CURRENT_SOURCE_DIR}/../peanuts_nsp.c ${NSP_ROOT}/dm/third_party/zstd/zstddeclib.c ${NXIMPORT_SOURCES})
    target_include_directories(nsp_compare PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${NSP_ROOT}/dm/host)
    foreach(t nsp_test nsp_compare)
      target_link_libraries(${t} PRIVATE Threads::Threads ZLIB::ZLIB)
      target_compile_definitions(${t} PRIVATE NX_TEST_HOOKS)
      target_compile_options(${t} PRIVATE -O2 -g)
    endforeach()
    add_test(NAME nsp_test COMMAND nsp_test)
    add_test(NAME nsp_compare COMMAND nsp_compare)
  endif()
  if(WIN32)
    # MinGW's DLLs (libgcc, winpthread) and zlib are linked in, so the .exe files run from any folder (zlib's static library must be installed: MSYS2's zlib package has it)
    target_link_options(nximport_cli PRIVATE -static)
    target_link_options(comp_test PRIVATE -static)
    target_link_options(zip_test PRIVATE -static)
  endif()
endif()
if(TARGET nximport)
  target_include_directories(nximport PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(nximport PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation -fvisibility=hidden)
endif()
# exported: the nximport.h API (NXIMPORT_API) and the JNI (JNIEXPORT); the nx_* internals stay hidden
