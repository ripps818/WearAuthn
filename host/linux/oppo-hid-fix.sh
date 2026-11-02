#!/bin/sh
# WearAuthn host fix for OnePlus/OPPO watches on Linux (BlueZ).
#
# These watches advertise a second Bluetooth HID record, "OPPO HIDDCR" (a media remote), next to
# WearAuthn's security key record. BlueZ uses only one HID record per device and picks the OPPO one,
# so the browser never sees a security key. This installs a small systemd path unit that removes the
# OPPO record from BlueZ's cache whenever BlueZ writes it, but only for devices that also advertise
# WearAuthn's record, then restarts bluetoothd so the change takes effect.
#
# Everything is installed under /etc, so it also works on immutable distributions.
#
# Usage: sudo sh oppo-hid-fix.sh [install|uninstall|run]

set -eu

NAME=wearauthn-oppo-hid-fix
LIBDIR=/etc/wearauthn
SCRIPT=$LIBDIR/$NAME
UNITDIR=/etc/systemd/system
BTDIR=${BTDIR:-/var/lib/bluetooth}

# Hex patterns as stored in BlueZ's cache (case-insensitive):
FIDO_DESC=06D0F10901                 # HID descriptor: Usage Page (FIDO), Usage (U2F HID)
OPPO_DESC=050C0901                   # HID descriptor: Usage Page (Consumer), Usage (Consumer Control)
OPPO_NAME=4F50504F20484944444352     # Service name "OPPO HIDDCR"

# Remove the OPPO HID record from every cache file that also has WearAuthn's record. Restarts
# bluetoothd if anything changed.
run() {
    changed=0
    for f in "$BTDIR"/??:??:??:??:??:??/cache/??:??:??:??:??:??; do
        [ -f "$f" ] || continue
        grep -qi "$FIDO_DESC" "$f" || continue
        grep -i "$OPPO_DESC" "$f" | grep -qi "$OPPO_NAME" || continue
        tmp=$(mktemp)
        awk -v d="$OPPO_DESC" -v n="$OPPO_NAME" \
            '{ u = toupper($0) } !(index(u, d) && index(u, n))' "$f" > "$tmp"
        cat "$tmp" > "$f"
        rm -f "$tmp"
        echo "$NAME: removed OPPO HID record from $f"
        changed=1
    done
    if [ "$changed" = 1 ]; then
        systemctl restart bluetooth.service
    fi
}

install() {
    [ "$(id -u)" = 0 ] || { echo "Run as root (sudo)." >&2; exit 1; }
    adapters=
    for a in "$BTDIR"/??:??:??:??:??:??/; do
        [ -d "$a" ] && adapters="$adapters $a"
    done
    [ -n "$adapters" ] || { echo "No Bluetooth adapters found in $BTDIR." >&2; exit 1; }

    mkdir -p "$LIBDIR"
    cp "$0" "$SCRIPT"
    chmod 755 "$SCRIPT"

    {
        echo "[Unit]"
        echo "Description=Remove OPPO media-remote HID record for WearAuthn watches"
        echo
        echo "[Path]"
        for a in $adapters; do
            mkdir -p "${a}cache"
            echo "PathChanged=${a}cache"
        done
        echo
        echo "[Install]"
        echo "WantedBy=multi-user.target"
    } > "$UNITDIR/$NAME.path"

    cat > "$UNITDIR/$NAME.service" <<EOF
[Unit]
Description=Remove OPPO media-remote HID record for WearAuthn watches
After=bluetooth.service

[Service]
Type=oneshot
# Give BlueZ time to finish pairing and write its files before touching the cache.
ExecStartPre=/bin/sleep 3
ExecStart=$SCRIPT run
EOF

    systemctl daemon-reload
    systemctl enable --now "$NAME.path"
    "$SCRIPT" run
    echo "Installed. Pair the watch from WearAuthn (Discoverable); the fix applies automatically."
    echo "Uninstall with: sudo $SCRIPT uninstall"
}

uninstall() {
    [ "$(id -u)" = 0 ] || { echo "Run as root (sudo)." >&2; exit 1; }
    systemctl disable --now "$NAME.path" 2>/dev/null || true
    rm -f "$UNITDIR/$NAME.path" "$UNITDIR/$NAME.service" "$SCRIPT"
    rmdir "$LIBDIR" 2>/dev/null || true
    systemctl daemon-reload
    echo "Uninstalled. Re-pair the watch to restore the OPPO record."
}

case "${1:-install}" in
    install) install ;;
    uninstall) uninstall ;;
    run) run ;;
    *) echo "Usage: $0 [install|uninstall|run]" >&2; exit 2 ;;
esac
