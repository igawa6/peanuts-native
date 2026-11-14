# libnximport — Peanuts Native's importer (nximport.h), plain C11. Two ways in:
#   - the app's native build adds this folder (android/app/src/main/cpp/CMakeLists.txt, one PEANUTS hook line, see HOOKS.md): target `nximport`,
#     a shared library libnximport.so with the JNI (dev.peanuts.importer.Native), 16 KB page aligned like the host's libraries;
#   - standalone on a PC for the tests: cmake -S dm/peanuts/import -B build && cmake --build build → nximport_cli (and libnximport.so).
#   - standalone on Windows with MinGW-w64 GCC (MSYS2 UCRT64 or WinLibs): cmake -G "MinGW Makefiles" -S dm/peanuts/import -B build; MSVC is not supported.
#     No libnximport there (its hidden-visibility export has no use on Windows): nximport_cli and comp_test, linked -static so the .exe runs alone.
cmake_minimum_required(VERSION 3.22)
project(nximport C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(NXIMPORT_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_icon.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nximport.c)
if(ANDROID)
  add_library(nximport SHARED ${NXIMPORT_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/nximport_jni.c)
  target_link_options(nximport PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
  target_link_libraries(nximport PRIVATE log)
else()
  find_package(Threads REQUIRED)   # the writer's pipeline (pthreads; bionic has them in libc)
  if(NOT WIN32)
    add_library(nximport SHARED ${NXIMPORT_SOURCES})
    target_link_libraries(nximport PRIVATE Threads::Threads)
  endif()
  add_executable(nximport_cli ${CMAKE_CURRENT_SOURCE_DIR}/nximport_cli.c ${NXIMPORT_SOURCES})   # the sources themselves: its ncainfo command uses the internals
  target_include_directories(nximport_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(nximport_cli PRIVATE Threads::Threads)
  target_compile_options(nximport_cli PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  add_executable(comp_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/comp_test.c ${NXIMPORT_SOURCES})   # the compression layer against the reference LZ4 (tests/comp_check.py)
  target_include_directories(comp_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(comp_test PRIVATE Threads::Threads)
  target_compile_options(comp_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  if(WIN32)
    # MinGW's DLLs (libgcc, libstdc++, winpthread) are linked in, so the .exe files run from any folder
    target_link_options(nximport_cli PRIVATE -static)
    target_link_options(comp_test PRIVATE -static)
  endif()
endif()
if(TARGET nximport)
  target_include_directories(nximport PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(nximport PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation -fvisibility=hidden)
endif()
# exported: the nximport.h API (NXIMPORT_API) and the JNI (JNIEXPORT); the nx_* internals stay hidden
