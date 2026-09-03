#!/usr/bin/env bash
# Drive the Android emulator mock GNSS through a straight-line outage.
#
# adb emu geo fix takes LONGITUDE then LATITUDE, in degrees.
# Example: adb emu geo fix 73.8560378 18.5204757
# That is lon lat. Not lat lon.
#
# Usage:
#   tools/emulator/tunnel_demo.sh START_LAT START_LON BEARING_DEG [SERIAL] [SPEED_MPS]
#
# No city is hardcoded. START_LAT / START_LON / BEARING_DEG are required.
# SERIAL defaults to emulator-5554. SPEED_MPS defaults to 10.
# Timeline: 20 s of 1 Hz fixes, 30 s with no fixes, then 20 s of 1 Hz fixes
# further along the same geodesic (same speed through the gap).

set -euo pipefail

usage() {
  echo "usage: $0 START_LAT START_LON BEARING_DEG [SERIAL] [SPEED_MPS]" >&2
  exit 2
}

if [[ $# -lt 3 ]]; then
  usage
fi

START_LAT="$1"
START_LON="$2"
BEARING_DEG="$3"
SERIAL="${4:-emulator-5554}"
SPEED_MPS="${5:-10}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB" >&2
  exit 1
fi

point_at() {
  local dist_m="$1"
  python3 - "$START_LAT" "$START_LON" "$BEARING_DEG" "$dist_m" <<'PY'
import math
import sys

lat, lon, bearing, dist = (float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]))
rad = math.radians(bearing)
radius_m = 6371000.0
north = dist * math.cos(rad)
east = dist * math.sin(rad)
dlat = (north / radius_m) * (180.0 / math.pi)
cos_lat = math.cos(math.radians(lat))
if abs(cos_lat) < 1e-12:
    cos_lat = 1e-12
dlon = (east / (radius_m * cos_lat)) * (180.0 / math.pi)
nlat = max(-90.0, min(90.0, lat + dlat))
nlon = ((lon + dlon + 180.0) % 360.0) - 180.0
print(f"{nlat:.8f} {nlon:.8f}")
PY
}

send_fix() {
  local lat="$1"
  local lon="$2"
  # lon then lat. See the header comment.
  "$ADB" -s "$SERIAL" emu geo fix "$lon" "$lat"
}

echo "serial=$SERIAL start=$START_LAT,$START_LON bearing=$BEARING_DEG deg speed=$SPEED_MPS m/s" >&2

for t in $(seq 0 19); do
  dist="$(python3 -c "print(float('$SPEED_MPS') * float('$t'))")"
  read -r plat plon <<<"$(point_at "$dist")"
  send_fix "$plat" "$plon"
  sleep 1
done

echo "holding 30 s with no geo fix" >&2
sleep 30

for t in $(seq 50 69); do
  dist="$(python3 -c "print(float('$SPEED_MPS') * float('$t'))")"
  read -r plat plon <<<"$(point_at "$dist")"
  send_fix "$plat" "$plon"
  sleep 1
done
