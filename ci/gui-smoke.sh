#!/usr/bin/env bash
set -euo pipefail

: "${DIPX_PROJECT:?DIPX_PROJECT was not set by the build workflow}"
mkdir -p gui-smoke
LOG="gui-smoke/runClient.log"
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=4.5
export ALSOFT_DRIVERS=null
unset WAYLAND_DISPLAY

echo "DISPLAY=$DISPLAY" | tee -a gui-smoke/smoke-status.txt
timeout 8s xdpyinfo -display "$DISPLAY" > gui-smoke/xdpyinfo.txt 2>&1 || echo "xdpyinfo timed out or failed" | tee -a gui-smoke/smoke-status.txt
timeout 8s glxinfo -B > gui-smoke/glxinfo.txt 2>&1 || echo "glxinfo timed out or failed" | tee -a gui-smoke/smoke-status.txt

openbox --sm-disable >/tmp/dipx-openbox.log 2>&1 &
OPENBOX_PID=$!
sleep 2
timeout 15s gradle --stop >/dev/null 2>&1 || echo "Gradle daemon stop timed out" | tee -a gui-smoke/smoke-status.txt

gradle -p "$DIPX_PROJECT" runClient --no-daemon --console=plain >"$LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
  pkill -TERM -f 'net.fabricmc.devlaunchinjector.Main' 2>/dev/null || true
  if kill -0 "$GRADLE_PID" 2>/dev/null; then
    pkill -TERM -P "$GRADLE_PID" 2>/dev/null || true
    kill -TERM "$GRADLE_PID" 2>/dev/null || true
  fi
  if [[ -n "$OPENBOX_PID" ]] && kill -0 "$OPENBOX_PID" 2>/dev/null; then
    kill -TERM "$OPENBOX_PID" 2>/dev/null || true
  fi
  cp /tmp/dipx-openbox.log gui-smoke/openbox.log 2>/dev/null || true
}
trap cleanup EXIT

WINDOW=""
for attempt in $(seq 1 45); do
  WINDOW="$(xdotool search --name 'Minecraft' 2>/dev/null | head -n 1 || true)"
  if [[ -z "$WINDOW" ]]; then
    WINDOW="$(wmctrl -l 2>/dev/null | awk 'NR==1 {print $1}' || true)"
  fi
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
  wmctrl -l 2>&1 | tee gui-smoke/window-debug.txt || true
  xwininfo -root -tree 2>&1 | tee -a gui-smoke/window-debug.txt || true
  cat /tmp/dipx-openbox.log 2>/dev/null | tee -a gui-smoke/window-debug.txt || true
  ps -eo pid,ppid,args | grep -E 'gradle|Minecraft|devlaunchinjector|openbox' | grep -v grep | tee -a gui-smoke/window-debug.txt || true
  GAME_PID="$(pgrep -f 'net.fabricmc.devlaunchinjector.Main' | head -n 1 || true)"
  if [[ -n "$GAME_PID" && -r "/proc/$GAME_PID/environ" ]]; then
    tr '\0' '\n' < "/proc/$GAME_PID/environ" | grep -E '^(DISPLAY|WAYLAND_DISPLAY|XDG_RUNTIME_DIR)=' | tee -a gui-smoke/window-debug.txt || true
  fi
  tail -n 180 "$LOG"
  exit 1
fi
echo "Minecraft window: $(xdotool getwindowname "$WINDOW" 2>/dev/null || echo unknown)" | tee -a gui-smoke/smoke-status.txt

for attempt in $(seq 1 30); do
  if grep -q "minecraft:textures/atlas/gui.png-atlas" "$LOG"; then
    break
  fi
  sleep 1
done
sleep 2
wmctrl -ia "$WINDOW" 2>/dev/null || true
xdotool windowraise "$WINDOW" 2>/dev/null || true
xdotool windowfocus --sync "$WINDOW" 2>/dev/null || true
xdotool key --window "$WINDOW" --clearmodifiers Shift_R
sleep 4
if ! grep -q "DIPX GUI opened" "$LOG"; then
  # Retry once after the title screen has completed its first render.
  xdotool key --window "$WINDOW" --clearmodifiers Shift_R
  sleep 3
fi
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

echo "Captured genuine running DIPX GUI screenshot: gui-smoke/DIPX-GUI.png" | tee -a gui-smoke/smoke-status.txt
echo "Screenshot unique colors: $COLOR_COUNT" | tee -a gui-smoke/smoke-status.txt
