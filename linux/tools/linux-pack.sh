#!/bin/sh
# Prepares a game pack on aarch64 (or x86_64) Linux with the same packer the Android app and the Mac use (scripts/pack_android.py):
# the code rewrite (Python, Capstone 5, lz4, zbic) and the Vulkan shader library (the translator's portable .NET dlls under the .NET 10
# runtime, SPIRV-Tools' spirv-val). Everything it installs stays in one folder, no root needed.
#   linux/tools/linux-pack.sh setup                     once: <env>/venv (Python packages), <env>/dotnet (.NET 10 runtime), the translator wrapper
#   linux/tools/linux-pack.sh <game.nup or dump folder> [pack_android.py options]
#                                                       the pack goes to <title ID>-work/android-pack beside the game file (the launcher's
#                                                       picker finds it under ~/Games); shaders take minutes (Mario Kart World: 11,603)
# NX_PACK_ENV: the environment folder (default ~/.local/share/banananative/packer). Needs python3 (3.12+; 3.14 builds the shader library without
# backports.zstd), curl, spirv-val on PATH (Ubuntu/Debian: spirv-tools) and a checkout of this repository (this script's own).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENV=${NX_PACK_ENV:-${XDG_DATA_HOME:-$HOME/.local/share}/banananative/packer}
XBIN="$ROOT/dm/gfx/tools/dmsh_xlate_bin"
WRAP="$XBIN/dmsh_xlate-linux"   # (beside the dlls: vk_android_build.py checks the dlls next to the translator it is given)

setup() {
  mkdir -p "$ENV"
  if [ ! -x "$ENV/venv/bin/python" ]; then
    python3 -m venv "$ENV/venv" 2>/dev/null || {   # (no ensurepip, as on Ubuntu without python3-venv: pip from its bootstrap script)
      python3 -m venv --without-pip "$ENV/venv"; curl -fsSL -o "$ENV/get-pip.py" https://bootstrap.pypa.io/get-pip.py; "$ENV/venv/bin/python" "$ENV/get-pip.py" -q; }
  fi
  "$ENV/venv/bin/python" -m pip -q install "capstone>=5,<6" lz4 zbic==1.0.1.post1
  if [ ! -x "$ENV/dotnet/dotnet" ] || ! "$ENV/dotnet/dotnet" --list-runtimes | grep -q '^Microsoft.NETCore.App 10\.'; then
    curl -fsSL -o "$ENV/dotnet-install.sh" https://dot.net/v1/dotnet-install.sh
    bash "$ENV/dotnet-install.sh" --runtime dotnet --channel 10.0 --install-dir "$ENV/dotnet"
  fi
  printf '#!/bin/sh\n# The shader translator on Linux (linux/tools/linux-pack.sh): the portable dlls beside this script under the .NET 10 runtime\nexec "${DOTNET_ROOT:-%s}/dotnet" "$(dirname "$0")/dmsh_xlate.dll" "$@"\n' "$ENV/dotnet" > "$WRAP"
  chmod +x "$WRAP"
  command -v spirv-val >/dev/null || echo "[RED] spirv-val is not on PATH: install SPIRV-Tools (Ubuntu/Debian: sudo apt install spirv-tools) before packing shaders"
  echo "translator: $("$WRAP" --source-sha) (the tree expects $(cd "$ROOT/dm/gfx/tools" && "$ENV/venv/bin/python" -c 'from dmsh_chain import xlate_source_sha as s; print(s() if callable(s) else s)'))"
  echo "ready: $ENV"
}

case "$1" in
  setup) setup ;;
  ""|-h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) [ -x "$ENV/venv/bin/python" ] && [ -x "$WRAP" ] || setup
     DOTNET_ROOT="$ENV/dotnet" XLATE="$WRAP" exec "$ENV/venv/bin/python" "$ROOT/scripts/pack_android.py" "$@" ;;
esac
