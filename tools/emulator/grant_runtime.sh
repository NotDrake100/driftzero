#!/usr/bin/env bash
# Grant location runtime and skip first-run on a debug install.
#
# Waits for adb + boot, enables location, pm-grants fine/coarse, sets
# location appops, writes first_run_done in driftzero_settings via run-as,
# then force-stops and starts MainActivity. Idempotent.
#
# Usage:
#   tools/emulator/grant_runtime.sh [SERIAL]
#
# SERIAL defaults to emulator-5554. Exits non-zero if in.driftzero.app
# is not installed. run-as requires a debuggable APK.

set -euo pipefail

PACKAGE="in.driftzero.app"
ACTIVITY="in.driftzero.app/.MainActivity"
PREFS_REL="shared_prefs/driftzero_settings.xml"
SERIAL="${1:-emulator-5554}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
WAIT_S="${WAIT_S:-60}"

FINE="android.permission.ACCESS_FINE_LOCATION"
COARSE="android.permission.ACCESS_COARSE_LOCATION"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB" >&2
  exit 1
fi

adb_s() {
  "$ADB" -s "$SERIAL" "$@"
}

shell_trim() {
  adb_s shell "$@" | tr -d '\r'
}

wait_for_device() {
  local i=0
  while (( i < WAIT_S )); do
    if "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$SERIAL"; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "no adb device $SERIAL" >&2
  exit 1
}

wait_for_boot() {
  local i=0
  local boot
  while (( i < WAIT_S )); do
    boot="$(shell_trim getprop sys.boot_completed || true)"
    if [[ "$boot" == "1" ]]; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "sys.boot_completed is not 1 on $SERIAL" >&2
  exit 1
}

package_installed() {
  local path
  path="$(shell_trim pm path "$PACKAGE" 2>/dev/null || true)"
  [[ -n "$path" ]]
}

runtime_granted() {
  local suffix="$1"
  shell_trim dumpsys package "$PACKAGE" | python3 -c "
import re
import sys
perm = sys.argv[1]
text = sys.stdin.read()
idx = text.find('runtime permissions:')
block = text[idx:] if idx >= 0 else text
m = re.search(r'android\\.permission\\.%s:\\s*granted=(true|false)' % re.escape(perm), block)
print(m.group(1) if m else 'unknown')
" "$suffix"
}

location_enabled() {
  local raw
  raw="$(shell_trim cmd location is-location-enabled 2>/dev/null || true)"
  if [[ "$raw" == "true" || "$raw" == "false" ]]; then
    printf '%s\n' "$raw"
    return 0
  fi
  raw="$(shell_trim settings get secure location_mode 2>/dev/null || true)"
  if [[ "$raw" == "3" || "$raw" == "1" || "$raw" == "2" ]]; then
    echo true
  elif [[ "$raw" == "0" ]]; then
    echo false
  else
    echo unknown
  fi
}

read_prefs_xml() {
  shell_trim run-as "$PACKAGE" cat "$PREFS_REL" 2>/dev/null || true
}

prefs_first_run_done() {
  read_prefs_xml | python3 -c "
import re
import sys
xml = sys.stdin.read()
m = re.search(r'<boolean\\s+name=\"first_run_done\"\\s+value=\"(true|false)\"', xml)
print(m.group(1) if m else 'missing')
"
}

merge_first_run_done() {
  python3 - "$@" <<'PY'
import re
import sys

out_path = sys.argv[1]
existing = sys.argv[2] if len(sys.argv) > 2 else ""
xml = existing.strip()
if "<map" not in xml:
    xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
if re.search(r'<boolean\s+name="first_run_done"', xml):
    xml, n = re.subn(
        r'(<boolean\s+name="first_run_done"\s+value=")[^"]*(")',
        r"\1true\2",
        xml,
        count=1,
    )
    if n != 1:
        raise SystemExit("could not set first_run_done in existing prefs")
else:
    xml, n = re.subn(
        r"</map>",
        '    <boolean name="first_run_done" value="true" />\n</map>',
        xml,
        count=1,
    )
    if n != 1:
        raise SystemExit("could not insert first_run_done into prefs")
if not xml.endswith("\n"):
    xml += "\n"
with open(out_path, "w", encoding="utf-8") as fh:
    fh.write(xml)
PY
}

write_first_run_done() {
  local existing tmp_host
  existing="$(read_prefs_xml)"
  tmp_host="$(mktemp)"
  merge_first_run_done "$tmp_host" "$existing"
  if ! adb_s push "$tmp_host" /data/local/tmp/driftzero_settings.xml >/dev/null 2>&1; then
    rm -f "$tmp_host"
    echo "failed to push prefs xml to /data/local/tmp" >&2
    exit 1
  fi
  # adb shell drops unquoted sh -c strings. Keep the remote command in one
  # quoted argv so mkdir/cp run under run-as.
  if adb_s shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/driftzero_settings.xml $PREFS_REL && rm -f ${PREFS_REL}.bak'"; then
    rm -f "$tmp_host"
    return 0
  fi
  if adb_s shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cat > $PREFS_REL && rm -f ${PREFS_REL}.bak'" < "$tmp_host"; then
    rm -f "$tmp_host"
    return 0
  fi
  rm -f "$tmp_host"
  echo "run-as could not write $PREFS_REL (need a debuggable install)" >&2
  exit 1
}

echo "serial=$SERIAL package=$PACKAGE" >&2
wait_for_device
wait_for_boot

if ! package_installed; then
  echo "package $PACKAGE is not installed on $SERIAL" >&2
  exit 1
fi

adb_s shell cmd location set-location-enabled true
adb_s shell pm grant "$PACKAGE" "$FINE"
adb_s shell pm grant "$PACKAGE" "$COARSE"
adb_s shell appops set "$PACKAGE" android:fine_location allow
adb_s shell appops set "$PACKAGE" android:coarse_location allow
adb_s shell appops set "$PACKAGE" android:gps allow
adb_s shell appops set "$PACKAGE" android:monitor_location allow

# Flush in-memory prefs before writing the file.
adb_s shell am force-stop "$PACKAGE"
write_first_run_done
adb_s shell am start -n "$ACTIVITY" >/dev/null

fine_v="$(runtime_granted ACCESS_FINE_LOCATION)"
coarse_v="$(runtime_granted ACCESS_COARSE_LOCATION)"
loc_v="$(location_enabled)"
pref_v="$(prefs_first_run_done)"

printf '%-28s %s\n' "check" "value"
printf '%-28s %s\n' "ACCESS_FINE_LOCATION" "granted=$fine_v"
printf '%-28s %s\n' "ACCESS_COARSE_LOCATION" "granted=$coarse_v"
printf '%-28s %s\n' "location_enabled" "$loc_v"
printf '%-28s %s\n' "first_run_done" "$pref_v"

if [[ "$fine_v" != "true" || "$coarse_v" != "true" || "$loc_v" != "true" || "$pref_v" != "true" ]]; then
  echo "grant_runtime: verification failed" >&2
  exit 1
fi
