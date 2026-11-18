# Peanuts Native: sourced by the staged build-appimage.sh (peanuts/linux/build-appimage.sh adds the line) once the hosts are in $APPDIR/usr/bin.
# With the generic host (linux/build.sh generic → nxhost_generic: Peanuts' NX_GENERIC_HOST, no title compiled in) every Switch 1 title of dm/games gets
#   usr/share/peanutsnative/profiles/<ID>.profile.bin and <ID>.seed   (dm/peanuts/profile/gen_runtime_profile.py, as android/app/build.gradle's nxGenericProfiles)
#   usr/bin/nxhost_<ID>                                               a two-line script: DM_PROFILE_PATH / DM_PROFILE_SEED for that title, then nxhost_generic
# so Banana Native's AppRun and game window, which start usr/bin/nxhost_<ID> for a game, run every title through the one host unchanged.
# PN_TITLES: the title IDs (default: every dm/games/01* profile the generator accepts; Switch 2 titles, 04*, are not this AppImage's).
if [ -x "$APPDIR/usr/bin/nxhost_generic" ]; then
  PRO="$APPDIR/usr/share/peanutsnative/profiles"; mkdir -p "$PRO"
  GEN="$ROOT/dm/peanuts/profile/gen_runtime_profile.py"
  python3 "$GEN" check-all >/dev/null || { echo "[RED] gen_runtime_profile.py check-all failed: a profile key the generic host does not know"; exit 1; }
  n=0; skip=
  for id in ${PN_TITLES:-$(cd "$ROOT/dm/games" && ls -d 01* | grep -E '^[0-9A-F]{16}$')}; do
    if python3 "$GEN" profile "$id" "$PRO/$id.profile.bin" >/dev/null 2>&1 && python3 "$GEN" seed "$id" "$PRO/$id.seed" >/dev/null 2>&1; then
      printf '#!/bin/sh\n# %s through the generic host (peanuts/linux/pn_appdir.sh)\nD=$(dirname "$0"); export DM_PROFILE_PATH="${DM_PROFILE_PATH:-$D/../share/peanutsnative/profiles/%s.profile.bin}" DM_PROFILE_SEED="${DM_PROFILE_SEED-$D/../share/peanutsnative/profiles/%s.seed}"\nexec "$D/nxhost_generic" "$@"\n' "$id" "$id" "$id" > "$APPDIR/usr/bin/nxhost_$id"
      chmod +x "$APPDIR/usr/bin/nxhost_$id"; n=$((n + 1))
    else rm -f "$PRO/$id.profile.bin" "$PRO/$id.seed"; skip="$skip $id"; fi
  done
  echo "generic host: $n titles${skip:+; no profile made for:$skip}"
fi
