#!/usr/bin/env bash
set -euo pipefail

: "${DIPX_PROJECT:?DIPX_PROJECT was not set by the build workflow}"
mkdir -p gui-smoke
LOG="gui-smoke/runClient.log"
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=4.5
export ALSOFT_DRIVERS=null
unset WAYLAND_DISPLAY

echo "Launcher DISPLAY=${DISPLAY:-unset}" | tee -a gui-smoke/smoke-status.txt
timeout 8s xdpyinfo -display "${DISPLAY:?DISPLAY is required}" > gui-smoke/launcher-display.txt 2>&1
timeout 8s glxinfo -B > gui-smoke/launcher-glxinfo.txt 2>&1 || echo "Launcher GLX probe timed out; continuing to game launch." | tee -a gui-smoke/smoke-status.txt

timeout 15s gradle --stop >/dev/null 2>&1 || echo "Gradle daemon stop timed out" | tee -a gui-smoke/smoke-status.txt
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Ddipx.smoke.gui=true"
gradle -p "$DIPX_PROJECT" runClient --no-daemon --console=plain >"$LOG" 2>&1 &
GRADLE_PID=$!
OPENBOX_PID=""

cleanup() {
  if [[ -n "$OPENBOX_PID" ]] && kill -0 "$OPENBOX_PID" 2>/dev/null; then
    kill -TERM "$OPENBOX_PID" 2>/dev/null || true
  fi
  if kill -0 "$GRADLE_PID" 2>/dev/null; then
    pkill -TERM -P "$GRADLE_PID" 2>/dev/null || true
    kill -TERM "$GRADLE_PID" 2>/dev/null || true
  fi
  cp /tmp/dipx-openbox.log gui-smoke/openbox.log 2>/dev/null || true
}
trap cleanup EXIT

# Loom may wrap runClient in its own xvfb-run. Read DISPLAY and XAUTHORITY
# from the actual Java game process, not the wrapper shell, or input/capture
# can target the outer X server or fail X11 authentication.
GAME_PID=""
GAME_DISPLAY=""
GAME_XAUTHORITY=""
for attempt in $(seq 1 75); do
  GAME_PID="$(ps -eo pid=,comm=,args= | awk '$2 == "java" && /net.fabricmc.devlaunchinjector.Main/ {print $1; exit}')"
  if [[ -n "$GAME_PID" && -r "/proc/$GAME_PID/environ" ]]; then
    GAME_DISPLAY="$(tr '\0' '\n' < "/proc/$GAME_PID/environ" | sed -n 's/^DISPLAY=//p' | head -n 1)"
    GAME_XAUTHORITY="$(tr '\0' '\n' < "/proc/$GAME_PID/environ" | sed -n 's/^XAUTHORITY=//p' | head -n 1)"
    if [[ -n "$GAME_DISPLAY" ]]; then break; fi
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    wait "$GRADLE_PID" || true
    tail -n 180 "$LOG"
    exit 1
  fi
  sleep 2
done

if [[ -z "$GAME_PID" || -z "$GAME_DISPLAY" ]]; then
  echo "Minecraft Java process or its X display was not found."
  ps -eo pid,ppid,comm,args | grep -E 'gradle|Minecraft|devlaunchinjector|Xvfb' | grep -v grep | tee gui-smoke/process-debug.txt || true
  tail -n 180 "$LOG"
  exit 1
fi

export DISPLAY="$GAME_DISPLAY"
if [[ -n "$GAME_XAUTHORITY" ]]; then export XAUTHORITY="$GAME_XAUTHORITY"; fi
echo "Minecraft PID=$GAME_PID DISPLAY=$DISPLAY XAUTHORITY=${XAUTHORITY:-unset}" | tee -a gui-smoke/smoke-status.txt
timeout 8s xdpyinfo -display "$DISPLAY" > gui-smoke/game-display.txt 2>&1
timeout 8s glxinfo -B > gui-smoke/game-glxinfo.txt 2>&1 || echo "Game GLX probe timed out; window/render smoke test remains authoritative." | tee -a gui-smoke/smoke-status.txt

openbox --sm-disable >/tmp/dipx-openbox.log 2>&1 &
OPENBOX_PID=$!
sleep 2

WINDOW=""
for attempt in $(seq 1 60); do
  WINDOW="$(xdotool search --onlyvisible --name 'Minecraft' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$WINDOW" ]] && grep -q "DIPX initialized" "$LOG"; then break; fi
  if ! kill -0 "$GAME_PID" 2>/dev/null; then
    echo "Minecraft exited before creating a visible window."
    tail -n 180 "$LOG"
    exit 1
  fi
  sleep 2
done

if [[ -z "$WINDOW" ]] || ! grep -q "DIPX initialized" "$LOG"; then
  echo "Minecraft did not expose a visible window on DISPLAY=$DISPLAY."
  wmctrl -l 2>&1 | tee gui-smoke/window-debug.txt || true
  xwininfo -root -tree 2>&1 | tee -a gui-smoke/window-debug.txt || true
  ps -eo pid,ppid,comm,args | grep -E 'gradle|Minecraft|devlaunchinjector|Xvfb|openbox' | grep -v grep | tee -a gui-smoke/window-debug.txt || true
  tail -n 180 "$LOG"
  exit 1
fi

echo "Minecraft window: $(xdotool getwindowname "$WINDOW" 2>/dev/null || echo unknown)" | tee -a gui-smoke/smoke-status.txt
xprop -id "$WINDOW" > gui-smoke/window-properties.txt 2>&1 || true
import -window "$WINDOW" gui-smoke/Minecraft-before-DIPX.png
echo "Captured pre-GUI Minecraft window for diagnostics." | tee -a gui-smoke/smoke-status.txt
for attempt in $(seq 1 30); do
  if grep -q "minecraft:textures/atlas/gui.png-atlas" "$LOG"; then break; fi
  sleep 1
done
sleep 2
wmctrl -ia "$WINDOW" 2>/dev/null || true
xdotool windowraise "$WINDOW" 2>/dev/null || true
xdotool windowactivate --sync "$WINDOW" 2>/dev/null || true
xdotool windowfocus --sync "$WINDOW"
echo "Focused X11 window=$WINDOW active=$(xdotool getwindowfocus 2>/dev/null || echo unknown)" | tee -a gui-smoke/smoke-status.txt
# Use XTest (global key events), not XSendEvent's --window path. GLFW
# ignores synthetic XSendEvent keyboard messages on this Linux runner.
xdotool keydown --clearmodifiers Shift_R
sleep 0.2
xdotool keyup Shift_R
for attempt in $(seq 1 12); do
  if grep -q "DIPX Right Shift received" "$LOG" && grep -q "DIPX GUI opened" "$LOG"; then break; fi
  sleep 1
done
if ! grep -q "DIPX Right Shift received" "$LOG"; then
  echo "DIPX did not receive the real Right Shift key event."
  tail -n 180 "$LOG"
  exit 1
fi
if ! grep -q "DIPX GUI opened" "$LOG"; then
  echo "Right Shift reached DIPX but did not open the GUI."
  tail -n 180 "$LOG"
  exit 1
fi
echo "Right Shift keybind opened the real DIPX screen." | tee -a gui-smoke/smoke-status.txt
if grep -q "DIPX GUI render failed" "$LOG"; then
  echo "DIPX reported a GUI rendering exception."
  tail -n 100 "$LOG"
  exit 1
fi

import -window "$WINDOW" gui-smoke/DIPX-GUI.png
COLOR_COUNT="$(identify -format '%k' gui-smoke/DIPX-GUI.png)"
DIMENSIONS="$(identify -format '%wx%h' gui-smoke/DIPX-GUI.png)"
if [[ "$COLOR_COUNT" -lt 100 ]]; then
  echo "GUI screenshot appears blank or unrendered (unique colors: $COLOR_COUNT)."
  exit 1
fi
echo "Captured genuine running DIPX GUI screenshot: gui-smoke/DIPX-GUI.png ($DIMENSIONS, $COLOR_COUNT colors)" | tee -a gui-smoke/smoke-status.txt

sleep 2
if grep -q "DIPX GUI closed" "$LOG"; then
  echo "DIPX GUI closed unexpectedly while idle."
  exit 1
fi
xdotool keydown --clearmodifiers Shift_R
sleep 0.2
xdotool keyup Shift_R
for attempt in $(seq 1 12); do
  if grep -q "DIPX GUI closed" "$LOG"; then break; fi
  sleep 1
done
if ! grep -q "DIPX GUI closed" "$LOG"; then
  echo "DIPX GUI did not close through keybind or smoke lifecycle."
  tail -n 100 "$LOG"
  exit 1
fi
echo "GUI open/idle/close smoke test passed." | tee -a gui-smoke/smoke-status.txt
