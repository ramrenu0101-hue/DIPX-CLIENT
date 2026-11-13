#!/usr/bin/env bash
set -euo pipefail

: "${DIPX_PROJECT:?DIPX_PROJECT was not set by the build workflow}"
mkdir -p gui-smoke
LOG="gui-smoke/runClient.log"
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=4.5
export ALSOFT_DRIVERS=null

gradle -p "$DIPX_PROJECT" runClient --no-daemon >"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  if kill -0 "$GRADLE_PID" 2>/dev/null; then
    pkill -TERM -P "$GRADLE_PID" 2>/dev/null || true
    kill -TERM "$GRADLE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

WINDOW=""
for attempt in $(seq 1 90); do
  WINDOW="$(xdotool search --onlyvisible --name 'Minecraft' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$WINDOW" ]] && grep -q "DIPX initialized" "$LOG"; then
    break
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    wait "$GRADLE_PID" || true
    tail -n 180 "$LOG"
    exit 1
  fi
  sleep 2
done

if [[ -z "$WINDOW" ]] || ! grep -q "DIPX initialized" "$LOG"; then
  echo "Minecraft client did not reach the DIPX initialized state."
  tail -n 180 "$LOG"
  exit 1
fi

for attempt in $(seq 1 60); do
  if grep -q "minecraft:textures/atlas/gui.png-atlas" "$LOG"; then
    break
  fi
  sleep 1
done
sleep 2
xdotool windowactivate --sync "$WINDOW" 2>/dev/null || true
xdotool windowraise "$WINDOW" 2>/dev/null || true
xdotool windowfocus --sync "$WINDOW" 2>/dev/null || true
xdotool key --window "$WINDOW" --clearmodifiers Shift_R
sleep 4
if ! grep -q "DIPX GUI opened" "$LOG"; then
  # Retry once after the title screen has completed its first render.
  xdotool key --window "$WINDOW" --clearmodifiers Shift_R
  sleep 3
}
if ! grep -q "DIPX GUI opened" "$LOG"; then
  echo "Right Shift did not open the DIPX screen."
  tail -n 180 "$LOG"
  exit 1
fi

import -window "$WINDOW" gui-smoke/DIPX-GUI.png
COLOR_COUNT="$(identify -format '%k' gui-smoke/DIPX-GUI.png)"
if [[ "$COLOR_COUNT" -lt 100 ]]; then
  echo "GUI screenshot appears blank or unrendered (unique colors: $COLOR_COUNT)."
  exit 1
fi
if grep -q "DIPX GUI render failed" "$LOG"; then
  echo "DIPX reported a GUI rendering exception."
  exit 1
fi

echo "Captured genuine running DIPX GUI screenshot: gui-smoke/DIPX-GUI.png"
echo "Screenshot unique colors: $COLOR_COUNT"
