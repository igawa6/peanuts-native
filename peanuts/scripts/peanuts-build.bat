@echo off
rem Peanuts Native's builder on Windows: .nsp / .xci (or a .nup, or a dump folder) -> .nup -> pack -> (with --push) the phone.
rem Runs peanuts_build.py with the packer's Python (scripts\bananative.bat python: set up on first use, see that file).
rem Usage: peanuts\scripts\peanuts-build.bat <input>... [options] [-- pack_android.py options]   (see peanuts\docs\BUILDER.md)
call "%~dp0..\..\scripts\bananative.bat" python "%~dp0peanuts_build.py" %*
exit /b %errorlevel%
