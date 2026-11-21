#!/bin/zsh
# Apply the Sparking! ZERO allocator fix, run its tests, then build a separate Turbo 3 APK.
set -euo pipefail
HERE=${0:A:h}
REPO=${1:-$PWD}
REPO=${REPO:A}
PATCH_FILE="$HERE/SparkingZERO_FMalloc_MultiCall_Fix.patch"
WORK=${NX_WORK:-"$REPO/.turbo3-build"}

[[ -d "$REPO/android/app" && -f "$REPO/dm/tools/patch_nso.py" ]] || {
  print -u2 "[RED] Pass the extracted peanuts-native-0.1.6-1 source folder as the first argument."
  exit 2
}
[[ -f "$PATCH_FILE" ]] || { print -u2 "[RED] Patch file is missing beside this script."; exit 2; }

# Apply once; allow rerunning after it has already been applied.
if patch -p1 --dry-run -d "$REPO" < "$PATCH_FILE" >/dev/null 2>&1; then
  patch -p1 -d "$REPO" < "$PATCH_FILE"
  print "[OK] Source patch applied."
elif grep -q 'UE_CALLS, UE_VT, UE_SLOT' "$REPO/dm/tools/patch_nso.py" && \
     grep -Fq 'calls = sorted(bl.get(c, []))' "$REPO/dm/tools/ue_malloc.py"; then
  print "[OK] Source patch already present."
else
  print -u2 "[RED] Source does not match the expected 0.1.6 baseline; refusing a partial patch."
  exit 2
fi

python3 -m py_compile \
  "$REPO/dm/tools/ue_malloc.py" \
  "$REPO/dm/tools/patch_nso.py" \
  "$REPO/dm/tools/tests/test_ue_malloc.py"
(cd "$REPO" && python3 -m unittest dm.tools.tests.test_ue_malloc -v)

export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-"$ANDROID_SDK_ROOT/ndk/28.2.13676358"}
[[ -d "$ANDROID_SDK_ROOT" ]] || { print -u2 "[RED] Set ANDROID_SDK_ROOT to an installed Android SDK (compileSdk 36)."; exit 2; }
[[ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]] || { print -u2 "[RED] Install NDK 28.2.13676358 and set ANDROID_NDK_HOME."; exit 2; }
command -v java >/dev/null || { print -u2 "[RED] Java is required by Gradle."; exit 2; }
command -v zsh >/dev/null || { print -u2 "[RED] zsh is required by the native build scripts."; exit 2; }

# Produces Android-side packer/native libraries required by the APK.
zsh "$REPO/android/ondevice/build.sh" "$WORK" all

# Build a separate package so the existing Peanuts installation and its data are not overwritten.
(
  cd "$REPO/android/app"
  ./gradlew --no-daemon apk \
    -PnxApp=peanuts \
    -PnxAppId=turbo3 \
    -PnxLabel="Peanuts Turbo 3" \
    -PnxGames=010035F022078000 \
    -PnxGenericHost=true \
    -PnxPackStage="$WORK/stage" \
    -PnxLsfg=false
)

mkdir -p "$REPO/out"
cp "$REPO/android/app/out/nxnative-android-peanuts.apk" "$REPO/out/Peanuts-Turbo3-SparkingZERO-dev.apk"
print "[SUCCESS] APK: $REPO/out/Peanuts-Turbo3-SparkingZERO-dev.apk"
