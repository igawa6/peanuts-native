#!/bin/sh
# Builds BananaNative-<version>-aarch64.AppImage on an x86_64 or arm64 machine with Docker (the cross image of linux/build.sh):
#   linux/appimage/build-appimage.sh [title ID ...]      default: every host already built in linux/build/, else Mario Kart World
# One host binary per game goes in (the game profile is compiled into the host, as on Android). Output: linux/build/appimage/
# Downloads appimagetool (x86_64) and the aarch64 AppImage runtime from the AppImage project's GitHub releases on first use.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd); HERE="$ROOT/linux/appimage"; OUT="$ROOT/linux/build/appimage"; APPDIR="$OUT/AppDir"; TOOLS="$OUT/tools"
IDS="$*"
[ -n "$IDS" ] || IDS=$(ls "$ROOT/linux/build" 2>/dev/null | grep -E '^[0-9A-F]{16}$' | tr '\n' ' ')
[ -n "$IDS" ] || IDS=0400C3F00006E000
VER=$(git -C "$ROOT" describe --tags --always 2>/dev/null | sed 's/^v//'); VER=${VER:-dev}

rm -rf "$APPDIR"; mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/lib/sdl3" "$TOOLS"
for id in $IDS; do
  echo "== host $id"; "$ROOT/linux/build.sh" "$id" | tail -1
  cp "$ROOT/linux/build/$id/nxhost_$id" "$APPDIR/usr/bin/"
done
[ -f "$ROOT/linux/build/sdl3/out/lib/libSDL3.so.0" ] || "$ROOT/linux/build-sdl3.sh"   # (the fallback SDL3 the AppImage carries, used only where the system has none)
cp -L "$ROOT/linux/build/sdl3/out/lib/libSDL3.so.0" "$APPDIR/usr/lib/sdl3/"
cp "$HERE/AppRun" "$APPDIR/AppRun"; chmod +x "$APPDIR/AppRun"
cp "$HERE/banananative.desktop" "$APPDIR/"
rsvg-convert -w 256 -h 256 "$ROOT/android/images/logo.svg" -o "$APPDIR/banananative.png"
cp "$ROOT/LICENSE" "$APPDIR/" 2>/dev/null || true
cp "$ROOT/linux/README.md" "$APPDIR/README.md"
mkdir -p "$APPDIR/usr/share/banananative"; cp "$ROOT/linux/tools/banananative-governor" "$ROOT/linux/tools/install-governor-helper.sh" "$APPDIR/usr/share/banananative/"
# the source it was built from, for the launcher's "Prepare a game" (it downloads that exact tree for the packer): NX_SOURCE_URL is a repository on
# git.eden-emu.dev (Forgejo: <url>/archive/<commit>.tar.gz) that has the commit
printf 'commit %s\nurl %s\nversion %s\nchain %s\n' "$(git -C "$ROOT" rev-parse HEAD)" "${NX_SOURCE_URL:-https://git.eden-emu.dev/moceure/BananaNative}" "$VER" \
  "$(sed -n 's/^#define DMSH_VK_CHAIN "\([0-9a-f]*\)".*/\1/p' "$ROOT/dm/gfx/nvn/dmsh_chain_vk.h")" > "$APPDIR/usr/share/banananative/source"
cp "$HERE/launcher.py" "$HERE/steam_shortcut.py" "$APPDIR/usr/share/banananative/"
# The shared-font stand-ins, as the APK carries them (GameFonts.java, build.gradle nxExtFont): Noto Sans as standard.ttf and the button icons' nintendo_ext.ttf
# (dm/tools/make_ext_font.py, letters from the same Noto Sans; fontTools in a Python environment of its own under tools/). AppRun sets DM_FONT_DIR to them
F="$APPDIR/usr/share/banananative/fonts"; mkdir -p "$F"
cp "$ROOT/dm/third_party/fonts/NotoSans-Regular-subset.ttf" "$F/standard.ttf"; cp "$ROOT/dm/third_party/fonts/OFL.txt" "$F/OFL.txt"
[ -x "$TOOLS/pyenv/bin/python" ] || { python3 -m venv "$TOOLS/pyenv" && "$TOOLS/pyenv/bin/python" -m pip -q install fonttools; } || true
"$TOOLS/pyenv/bin/python" "$ROOT/dm/tools/make_ext_font.py" "$F/nintendo_ext.ttf" "$ROOT/dm/third_party/fonts/NotoSans-Regular-subset.ttf" >/dev/null ||
  { rm -f "$F/nintendo_ext.ttf"; echo "[RED] nintendo_ext.ttf not made (fontTools): button icons in game text will be drawn as '?'"; }

# appimagetool runs here (x86_64); the runtime put in front of the image is the aarch64 one
[ -x "$TOOLS/appimagetool" ] || { curl -fsSL -o "$TOOLS/appimagetool" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage; chmod +x "$TOOLS/appimagetool"; }
[ -f "$TOOLS/runtime-aarch64" ] || curl -fsSL -o "$TOOLS/runtime-aarch64" https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-aarch64
IMG="$OUT/BananaNative-$VER-aarch64.AppImage"
ARCH=aarch64 "$TOOLS/appimagetool" --appimage-extract-and-run --no-appstream --runtime-file "$TOOLS/runtime-aarch64" "$APPDIR" "$IMG" 2>&1 | tail -3
ls -la "$IMG"
