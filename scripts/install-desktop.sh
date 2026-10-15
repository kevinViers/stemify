#!/usr/bin/env bash
# Add StemRoller and StemRoller Player to the app grid (and "Open with" for audio files).
# Re-run after moving the project; `--uninstall` removes all of it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
DESKTOP="$APPS/stemroller.desktop"
ICON="$ICONS/stemroller.svg"
PLAYER_DESKTOP="$APPS/stemroller-player.desktop"
PLAYER_ICON="$ICONS/stemroller-player.svg"
# stem-on / stem-off from older installs; library separation now runs on the server (server/stemd.py).
OLD_BIN="$HOME/.local/bin"

if [[ "${1:-}" == "--uninstall" ]]; then
    rm -f "$DESKTOP" "$ICON" "$PLAYER_DESKTOP" "$PLAYER_ICON" "$OLD_BIN/stem-on" "$OLD_BIN/stem-off"
    echo "removed $DESKTOP, $PLAYER_DESKTOP and their icons"
    exit 0
fi

for bin in stemroller stemroller-player; do
    if [[ ! -x "$ROOT/.venv/bin/$bin" ]]; then
        echo "missing $ROOT/.venv/bin/$bin; run 'uv sync' in $ROOT first" >&2
        exit 1
    fi
done

mkdir -p "$APPS" "$ICONS"
install -m 644 "$ROOT/stemroller/assets/stemroller.svg" "$ICON"
install -m 644 "$ROOT/stemroller/player/assets/stemroller-player.svg" "$PLAYER_ICON"

# StartupWMClass / file name match QGuiApplication.setDesktopFileName("stemroller"),
# so Wayland groups the window under this entry and shows its icon.
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=StemRoller
GenericName=Stem Separator
Comment=Split songs into vocals, drums, bass and more on the GPU
Exec="$ROOT/.venv/bin/stemroller" %F
Path=$ROOT
Icon=stemroller
Terminal=false
Categories=AudioVideo;Audio;
MimeType=audio/mpeg;audio/flac;audio/x-flac;audio/wav;audio/x-wav;audio/ogg;audio/opus;audio/mp4;audio/aac;audio/x-aiff;audio/x-m4a;
Keywords=stems;demucs;roformer;vocals;karaoke;separate;
StartupWMClass=stemroller
StartupNotify=true
EOF
chmod 644 "$DESKTOP"

# StartupWMClass matches QGuiApplication.setDesktopFileName("stemroller-player"); the
# MPRIS DesktopEntry points here too, so media controls show this icon.
cat > "$PLAYER_DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=StemRoller Player
GenericName=Music Player
Comment=Your Plex library and its stems, in a spreadsheet
Exec="$ROOT/.venv/bin/stemroller-player"
Path=$ROOT
Icon=stemroller-player
Terminal=false
Categories=AudioVideo;Audio;Player;
Keywords=music;plex;stems;karaoke;player;excel;
StartupWMClass=stemroller-player
StartupNotify=true
EOF
chmod 644 "$PLAYER_DESKTOP"

command -v update-desktop-database >/dev/null && update-desktop-database -q "$APPS" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "${ICONS%/scalable/apps}" || true
command -v desktop-file-validate >/dev/null && desktop-file-validate "$DESKTOP" "$PLAYER_DESKTOP" || true
rm -f "$OLD_BIN/stem-on" "$OLD_BIN/stem-off"

echo "installed $DESKTOP and $PLAYER_DESKTOP"
