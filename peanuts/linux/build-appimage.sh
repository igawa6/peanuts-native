#!/bin/sh
# Peanuts Native's aarch64 Linux AppImage (peanuts/docs/LINUX.md):
#   peanuts/linux/build-appimage.sh [title ID ...]     default: the generic host (every Switch 1 profile); title IDs: hosts with that title compiled in
# The host is linux/ (Banana Native 0.3.43's, its marked Peanuts lines). The launcher is Banana Native's AppImage launcher (linux/appimage, unchanged).
# A renamed copy of it is staged in linux/build/peanuts-appimage/ (Banana Native → Peanuts Native, its own data and
# settings folders ~/.local/share/peanutsnative and ~/.config/peanutsnative, packs in ~/Games/PeanutsNative/packs, Steam shortcuts "(PeanutsNative)") so it
# sits beside the Banana Native AppImage without sharing packs: Peanuts' shader chain is its own (PEANUTS.md). Then the staged build-appimage.sh runs over this tree.
# What else differs from Banana Native's:
#   - Prepare downloads its tree from NX_SOURCE_URL (default: the Peanuts repository on GitHub, <url>/archive/<commit>.tar.gz) and packs with that tree's
#     linux/tools/linux-pack.sh and the Peanuts packer settings PackService sets on the phone (DM_SPLIT_DIRECT=1, DMSH_VK_ZSTD_LEVEL=6)
#   - .nsp / .xci games with the player's own prod.keys, Switch 1 titles and Peanuts packs only (peanuts/linux/pn_launcher.py)
#   - the generic host: every Switch 1 profile through one host (peanuts/linux/pn_appdir.sh)
#   - the icon is the peanut (android/app/src/peanuts/res/mipmap-xxxhdpi)
#   - the CPU governor helper keeps Banana Native's name (/usr/local/sbin/banananative-governor): one helper serves both AppImages
# Every edit of the staged copy is checked: when Banana Native's file changed so that one does not apply, this stops with a [RED] line.
# Output: linux/build/appimage/PeanutsNative-<version>-aarch64.AppImage
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ST="$ROOT/linux/build/peanuts-appimage"
URL=${NX_SOURCE_URL:-https://github.com/igawa6/peanuts-native}
VER=$(git -C "$ROOT" describe --tags --match 'v[0-9]*' --always 2>/dev/null | sed 's/^v//'); VER=${VER:-dev}
red() { echo "[RED] $1 changed upstream: a Peanuts edit did not apply (peanuts/linux/build-appimage.sh)"; exit 1; }

IDS="$*"
[ -n "$IDS" ] || IDS=generic

rm -rf "$ST"; mkdir -p "$ST"
cp -r "$ROOT/linux/appimage" "$ST/"
mv "$ST/appimage/banananative.desktop" "$ST/appimage/peanutsnative.desktop"
# the rename: every name but the governor helper's
for f in "$ST"/appimage/*; do
  sed -i -e 's/banananative-governor/@GOV@/g' \
         -e 's/banananative/peanutsnative/g' -e 's/BananaNative/PeanutsNative/g' -e 's/Banana Native/Peanuts Native/g' \
         -e 's/@GOV@/banananative-governor/g' "$f"
done
sed -i 's/^Name=PeanutsNative$/Name=Peanuts Native/' "$ST/appimage/peanutsnative.desktop"

B="$ST/appimage/build-appimage.sh"
# build-appimage.sh: its root is this tree, its own folder the staged one; the version and the source line are Peanuts'; the peanut icon; the importer,
# Peanuts' launcher additions and the generic host's profiles go in
sed -i -e "s|ROOT=\$(cd \"\$(dirname \"\$0\")/../..\" \&\& pwd); HERE=\"\$ROOT/linux/appimage\"|ROOT=\"$ROOT\"; HERE=\"$ST/appimage\"|" \
       -e "s|^VER=.*|VER='$VER'|" \
       -e "s|\${NX_SOURCE_URL:-https://git.eden-emu.dev/moceure/PeanutsNative}|$URL|" \
       -e "s|rsvg-convert -w 256 -h 256 \"\$ROOT/android/images/logo.svg\" -o \"\$APPDIR/peanutsnative.png\"|cp \"\$ROOT/android/app/src/peanuts/res/mipmap-xxxhdpi/ic_launcher.png\" \"\$APPDIR/peanutsnative.png\"|" \
       -e "s|cp \"\$HERE/launcher.py\" \"\$HERE/steam_shortcut.py\"|cp \"\$HERE/pn_launcher.py\" \"\$HERE/launcher.py\" \"\$HERE/steam_shortcut.py\"|" \
       -e "s|^cp -L \"\$ROOT/linux/build/sdl3/out/lib/libSDL3.so.0\" \"\$APPDIR/usr/lib/sdl3/\"$|&\ncp \"\$ROOT/linux/build/nximport/nximport_cli\" \"\$APPDIR/usr/bin/\"   # Peanuts: the importer (.nsp/.xci)|" \
       -e 's|^cp "$HERE/AppRun" "$APPDIR/AppRun"; chmod +x "$APPDIR/AppRun"$|&\n. "$HERE/pn_appdir.sh"   # Peanuts: the generic host|' "$B"
grep -q "ROOT=\"$ROOT\"; HERE=\"$ST/appimage\"" "$B" && grep -q "^VER='" "$B" && grep -q "$URL" "$B" && grep -q 'mipmap-xxxhdpi/ic_launcher.png' "$B" &&
  grep -q 'pn_launcher.py" "$HERE/launcher.py"' "$B" &&
  grep -q 'nximport/nximport_cli' "$B" && grep -q '^. "$HERE/pn_appdir.sh"' "$B" || red build-appimage.sh
[ -f "$ROOT/android/app/src/peanuts/res/mipmap-xxxhdpi/ic_launcher.png" ] || { echo "[RED] no peanut icon at android/app/src/peanuts/res/mipmap-xxxhdpi/ic_launcher.png"; exit 1; }

# AppRun and launcher.py: Prepare with the Peanuts packer settings
A="$ST/appimage/AppRun"; L="$ST/appimage/launcher.py"
sed -i -e "s|setsid sh -c \"NX_PACK_ENV=|setsid sh -c \"DM_SPLIT_DIRECT=1 DMSH_VK_ZSTD_LEVEL=6 NX_PACK_ENV=|" "$A"
sed -i -e "s|script = (f'NX_PACK_ENV=\"{PE}\" exec \"{self.src}/linux/tools/linux-pack.sh\" \"\$0\"|script = (f'DM_SPLIT_DIRECT=1 DMSH_VK_ZSTD_LEVEL=6 NX_PACK_ENV=\"{PE}\" exec \"{self.src}/linux/tools/linux-pack.sh\" \"\$0\"|" "$L"
grep -q 'DM_SPLIT_DIRECT=1 DMSH_VK_ZSTD_LEVEL=6 NX_PACK_ENV=' "$A" || red AppRun
grep -q "DM_SPLIT_DIRECT=1 DMSH_VK_ZSTD_LEVEL=6 NX_PACK_ENV" "$L" || red launcher.py
# launcher.py: Peanuts' additions (peanuts/linux/pn_launcher.py), loaded by one line before its main block; the generic host's files (peanuts/linux/pn_appdir.sh)
cp "$ROOT/peanuts/linux/pn_launcher.py" "$ROOT/peanuts/linux/pn_appdir.sh" "$ST/appimage/"
sed -i "s|^if __name__ == '__main__':$|import pn_launcher; pn_launcher.install(globals())   # Peanuts Native (peanuts/linux/build-appimage.sh)\n\nif __name__ == '__main__':|" "$L"
grep -q '^import pn_launcher' "$L" || red launcher.py

# the importer for aarch64 (dm/peanuts/import), in the cross image of linux/build.sh (made here when missing, as linux/build.sh does: a fresh machine has none)
IMG=${NX_CROSS_IMAGE:-nx-linux-cross:24.04}
docker image inspect "$IMG" >/dev/null 2>&1 || docker build -q -t "$IMG" "$ROOT/linux" >/dev/null
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT":/src -w /src "$IMG" sh -c \
  "cmake -S dm/peanuts/import -B linux/build/nximport -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=/src/linux/host/aarch64-clang.cmake >/dev/null && ninja -C linux/build/nximport nximport_cli >/dev/null"

[ "${PN_STAGE_ONLY:-0}" = 1 ] && { echo "staged: $ST"; exit 0; }
sh "$B" $IDS
