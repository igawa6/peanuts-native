#!/bin/sh
# Installs the governor helper (run once with sudo): sudo sh install-governor-helper.sh [user]
# Puts banananative-governor in /usr/local/sbin and lets that one user run its "on [schedutil|performance]" and "off" without a password.
# Remove: sudo rm /usr/local/sbin/banananative-governor /etc/sudoers.d/zz-banananative-governor
set -e
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
U=${1:-${SUDO_USER:?give the user name}}; H=$(cd "$(dirname "$0")" && pwd)
install -o root -g root -m 0755 "$H/banananative-governor" /usr/local/sbin/banananative-governor
T=$(mktemp); G=/usr/local/sbin/banananative-governor; printf '%s ALL=(root) NOPASSWD: %s on, %s on schedutil, %s on performance, %s off\n' "$U" $G $G $G $G > "$T"
visudo -cf "$T" >/dev/null && install -o root -g root -m 0440 "$T" /etc/sudoers.d/zz-banananative-governor; rm -f "$T"
echo "installed for $U: the BananaNative launcher now switches the governors while a game runs (NX_GOVERNOR=0 in settings.env turns that off)"
