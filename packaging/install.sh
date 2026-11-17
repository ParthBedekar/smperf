#!/usr/bin/env bash
# SMPerf installer for Linux. Graphical wizard (zenity), terminal fallback.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_SRC="$HERE/smperf"
LICENSE_FILE="$HERE/LICENSE.txt"
TITLE="SMPerf Setup"
MARKER=".smperf-install"

[ -d "$APP_SRC/bin" ] || { echo "Installer files are incomplete (smperf/ folder missing)." >&2; exit 1; }

GUI=0
if command -v zenity >/dev/null 2>&1 && { [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; }; then
    GUI=1
fi

cancel() { echo "Setup cancelled. Nothing was changed."; exit 1; }

say() {
    if [ $GUI = 1 ]; then zenity --info --title="$TITLE" --width=420 --text="$1" || true
    else printf '%b\n' "$1"; fi
}
fail() {
    if [ $GUI = 1 ]; then zenity --error --title="$TITLE" --width=420 --text="$1" || true
    else printf 'ERROR: %b\n' "$1" >&2; fi
    exit 1
}
yesno() {
    if [ $GUI = 1 ]; then
        zenity --question --title="$TITLE" --width=420 --ok-label="Yes" --cancel-label="No" --text="$1"
    else
        local a; printf '%b [y/N] ' "$1"; read -r a; [[ "$a" =~ ^[Yy] ]]
    fi
}

# ---- 1. welcome
if [ $GUI = 1 ]; then
    zenity --question --title="$TITLE" --width=440 --ok-label="Next" --cancel-label="Cancel" \
        --text="<b>Welcome to SMPerf Setup</b>\n\nSMPerf runs your program at different thread counts and shows how well it scales on this computer.\n\nClick Next to continue." || cancel
else
    echo "== Welcome to SMPerf Setup =="
fi

# ---- 2. license with tick box
if [ -f "$LICENSE_FILE" ]; then
    if [ $GUI = 1 ]; then
        zenity --text-info --title="SMPerf License Agreement" --filename="$LICENSE_FILE" \
            --checkbox="I have read and agree to the terms above" \
            --ok-label="Next" --cancel-label="Cancel" --width=640 --height=480 || cancel
    else
        cat "$LICENSE_FILE"; echo
        read -r -p "Type 'yes' to accept the license: " ans
        [ "$ans" = "yes" ] || cancel
    fi
fi

# ---- 3. choose folder
if [ $GUI = 1 ]; then
    base="$(zenity --file-selection --directory --title="Choose the folder to install SMPerf into" \
            --filename="$HOME/")" || cancel
else
    read -r -p "Install into which folder? [$HOME/Apps] " base
    base="${base:-$HOME/Apps}"
    base="${base/#\~/$HOME}"
fi
base="${base%/}"
DEST="$base/SMPerf"

yesno "SMPerf will be installed to:\n\n$DEST\n\nContinue?" || cancel

if [ -e "$DEST" ]; then
    if [ -f "$DEST/$MARKER" ]; then
        yesno "SMPerf is already installed there.\nReplace it?" || cancel
        rm -rf -- "$DEST"
    elif [ -n "$(ls -A "$DEST" 2>/dev/null)" ]; then
        fail "$DEST already exists and is not empty.\nChoose another folder."
    fi
fi
mkdir -p "$DEST" 2>/dev/null || fail "Cannot create $DEST\nCheck that you have permission to write there."

# ---- 4. copy files
copy_files() { cp -a "$APP_SRC/." "$DEST/" && touch "$DEST/$MARKER"; }
if [ $GUI = 1 ]; then
    { echo "# Copying files..."; copy_files; } | zenity --progress --pulsate --auto-close --no-cancel \
        --title="$TITLE" --width=420 || true
else
    echo "Copying files..."; copy_files || true
fi
[ -f "$DEST/$MARKER" ] || fail "Copying failed (disk full or no permission?)."
chmod +x "$DEST/bin/smperf" "$DEST/bin/mperf"

# ---- 5. menu entry
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
DESKTOP_FILE="$APPS/smperf.desktop"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=SMPerf
Comment=CPU Performance & Scaling Analyzer
Exec="$DEST/bin/smperf"
Icon=$DEST/icon.png
Terminal=false
Categories=Development;Utility;System;
EOF
chmod 644 "$DESKTOP_FILE"
update-desktop-database "$APPS" >/dev/null 2>&1 || true

# ---- 6. optional desktop shortcut
if yesno "Create a desktop shortcut?"; then
    DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
    if [ -d "$DESK" ]; then
        cp "$DESKTOP_FILE" "$DESK/SMPerf.desktop"
        chmod +x "$DESK/SMPerf.desktop"
        gio set "$DESK/SMPerf.desktop" metadata::trusted true >/dev/null 2>&1 || true
    fi
fi

# ---- 7. uninstaller
cat > "$DEST/uninstall.sh" <<'UNINSTALL_EOF'
#!/usr/bin/env bash
main() {
    DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    TITLE="SMPerf Uninstall"
    if [ ! -f "$DEST/.smperf-install" ] || [ "$DEST" = "/" ] || [ "$DEST" = "$HOME" ]; then
        echo "Refusing to remove $DEST (not an SMPerf install folder)." >&2; return 1
    fi
    if command -v zenity >/dev/null 2>&1 && { [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; }; then
        zenity --question --title="$TITLE" --width=420 --ok-label="Uninstall" --cancel-label="Cancel" \
            --text="Remove SMPerf from:\n\n$DEST\n\nYour saved result files and settings are not touched." || return 1
    else
        printf 'Remove SMPerf from %s ? [y/N] ' "$DEST"; read -r a; [[ "$a" =~ ^[Yy] ]] || return 1
    fi
    rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/applications/smperf.desktop"
    DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
    if [ -f "$DESK/SMPerf.desktop" ] && grep -q "$DEST" "$DESK/SMPerf.desktop"; then
        rm -f "$DESK/SMPerf.desktop"
    fi
    update-desktop-database "${XDG_DATA_HOME:-$HOME/.local/share}/applications" >/dev/null 2>&1 || true
    rm -rf -- "$DEST"
    echo "SMPerf was removed."
    command -v zenity >/dev/null 2>&1 && zenity --info --title="$TITLE" --text="SMPerf was removed." 2>/dev/null || true
}
main "$@"; exit $?
UNINSTALL_EOF
chmod +x "$DEST/uninstall.sh"

say "<b>SMPerf was installed.</b>\n\nLocation:\n$DEST\n\nOpen it from the application menu (search for SMPerf).\nTo remove it later, run:\n$DEST/uninstall.sh"