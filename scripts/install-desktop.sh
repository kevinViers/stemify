#!/usr/bin/env bash
# Add Stemify, Stemify Player and Stemify Mobile to the app grid (and "Open with" for audio files).
# Re-run after moving the project; `--uninstall` removes all of it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
DESKTOP="$APPS/stemify.desktop"
ICON="$ICONS/stemify.svg"
PLAYER_DESKTOP="$APPS/stemify-player.desktop"
PLAYER_ICON="$ICONS/stemify-player.svg"
MOBILE_DESKTOP="$APPS/stemify-mobile.desktop"
MOBILE_ICON="$ICONS/stemify-mobile.svg"
# stem-on / stem-off from older installs; library separation now runs on the server (server/stemd.py).
OLD_BIN="$HOME/.local/bin"

if [[ "${1:-}" == "--uninstall" ]]; then
    rm -f "$DESKTOP" "$ICON" "$PLAYER_DESKTOP" "$PLAYER_ICON" "$MOBILE_DESKTOP" "$MOBILE_ICON" "$OLD_BIN/stem-on" "$OLD_BIN/stem-off"
    echo "removed $DESKTOP, $PLAYER_DESKTOP, $MOBILE_DESKTOP and their icons"
    exit 0
fi

for bin in stemify stemify-player stemify-mobile; do
    if [[ ! -x "$ROOT/.venv/bin/$bin" ]]; then
        echo "missing $ROOT/.venv/bin/$bin; run 'uv sync' in $ROOT first" >&2
        exit 1
    fi
done

mkdir -p "$APPS" "$ICONS"
install -m 644 "$ROOT/stemify/assets/stemify.svg" "$ICON"
install -m 644 "$ROOT/stemify/player/assets/stemify-player.svg" "$PLAYER_ICON"
install -m 644 "$ROOT/stemify/mobile/assets/stemify-mobile.svg" "$MOBILE_ICON"

# StartupWMClass / file name match QGuiApplication.setDesktopFileName("stemify"),
# so Wayland groups the window under this entry and shows its icon.
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Stemify
GenericName=Stem Separator
Comment=Split songs into vocals, drums, bass and more on the GPU
Exec="$ROOT/.venv/bin/stemify" %F
Path=$ROOT
Icon=stemify
Terminal=false
Categories=AudioVideo;Audio;
MimeType=audio/mpeg;audio/flac;audio/x-flac;audio/wav;audio/x-wav;audio/ogg;audio/opus;audio/mp4;audio/aac;audio/x-aiff;audio/x-m4a;
Keywords=stems;demucs;roformer;vocals;karaoke;separate;
StartupWMClass=stemify
StartupNotify=true
EOF
chmod 644 "$DESKTOP"

# StartupWMClass matches QGuiApplication.setDesktopFileName("stemify-player"); the
# MPRIS DesktopEntry points here too, so media controls show this icon.
cat > "$PLAYER_DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Stemify Player
GenericName=Music Player
Comment=Your Plex library and its stems, in a spreadsheet
Exec="$ROOT/.venv/bin/stemify-player"
Path=$ROOT
Icon=stemify-player
Terminal=false
Categories=AudioVideo;Audio;Player;
Keywords=music;plex;stems;karaoke;player;excel;
StartupWMClass=stemify-player
StartupNotify=true
EOF
chmod 644 "$PLAYER_DESKTOP"

# The phone app, on the desktop (setDesktopFileName("stemify-mobile"), MPRIS DesktopEntry too).
cat > "$MOBILE_DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Stemify Mobile
GenericName=Music Player
Comment=The Stemify phone app, on your desktop: Plex, stems, mixer and EQ
Exec="$ROOT/.venv/bin/stemify-mobile"
Path=$ROOT
Icon=stemify-mobile
Terminal=false
Categories=AudioVideo;Audio;Player;
Keywords=music;plex;stems;karaoke;player;equalizer;
StartupWMClass=stemify-mobile
StartupNotify=true
EOF
chmod 644 "$MOBILE_DESKTOP"

command -v update-desktop-database >/dev/null && update-desktop-database -q "$APPS" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "${ICONS%/scalable/apps}" || true
command -v desktop-file-validate >/dev/null && desktop-file-validate "$DESKTOP" "$PLAYER_DESKTOP" "$MOBILE_DESKTOP" || true
rm -f "$OLD_BIN/stem-on" "$OLD_BIN/stem-off"

echo "installed $DESKTOP, $PLAYER_DESKTOP and $MOBILE_DESKTOP"
