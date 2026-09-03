#!/usr/bin/env python3
"""Drive the Android emulator GNSS along a public OSRM route at 1 Hz.

Developer / emulator tool, not the production navigation path. Resolves optional
place names through Photon, pulls one driving geometry from the public OSRM
demo, resamples it at --speed-kmh, and sends `adb emu geo fix` (longitude then
latitude) once per second. An optional GNSS gap omits fixes so the app sees a
real outage, then resumes at the position the vehicle would have reached.

Photon and OSRM are public demo services. This script makes one HTTP request
per named endpoint and one OSRM request. It does not poll.

User-Agent matches apps/android StreetMapConfig.USER_AGENT.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "DriftZero/0.1 (Android travel map)"
PHOTON_API = "https://photon.komoot.io/api/"
OSRM_ROUTE = "https://router.project-osrm.org/route/v1/driving/"
EARTH_RADIUS_M = 6371000.0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--from",
        dest="from_coord",
        default=None,
        help='start as "lat,lon" in degrees (WGS84)',
    )
    parser.add_argument(
        "--to",
        dest="to_coord",
        default=None,
        help='end as "lat,lon" in degrees (WGS84)',
    )
    parser.add_argument(
        "--from-name",
        default=None,
        help="start place name, resolved once through Photon",
    )
    parser.add_argument(
        "--to-name",
        default=None,
        help="end place name, resolved once through Photon",
    )
    parser.add_argument(
        "--speed-kmh",
        type=float,
        default=30.0,
        help="constant speed for 1 Hz resampling, km/h (default 30)",
    )
    parser.add_argument(
        "--serial",
        default="emulator-5554",
        help="adb serial (default emulator-5554)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print adb commands instead of executing them",
    )
    parser.add_argument(
        "--gap-start-s",
        type=int,
        default=None,
        help="second index when the GNSS gap begins (off unless --gap-s is set)",
    )
    parser.add_argument(
        "--gap-s",
        type=int,
        default=None,
        help="how many seconds to omit geo fixes (default off)",
    )
    parser.add_argument(
        "--loop",
        action="store_true",
        help="repeat the route until Ctrl-C",
    )
    parser.add_argument(
        "--gpx",
        default=None,
        metavar="PATH",
        help="write emitted fixes (not gap seconds) to this GPX file",
    )
    return parser.parse_args(argv)


def parse_lat_lon(text: str, flag: str) -> tuple[float, float]:
    parts = [p.strip() for p in text.split(",")]
    if len(parts) != 2:
        raise SystemExit(f"{flag} must be lat,lon")
    try:
        lat = float(parts[0])
        lon = float(parts[1])
    except ValueError as exc:
        raise SystemExit(f"{flag} must be lat,lon") from exc
    if not -90.0 <= lat <= 90.0:
        raise SystemExit(f"{flag} latitude must be in [-90, 90]")
    if not -180.0 <= lon <= 180.0:
        raise SystemExit(f"{flag} longitude must be in [-180, 180]")
    return lat, lon


def http_json(url: str) -> object:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"HTTP {exc.code} from {url}") from exc
    except urllib.error.URLError as exc:
        raise SystemExit(f"network error for {url}: {exc.reason}") from exc
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid JSON from {url}") from exc


def photon_label(props: dict) -> str:
    parts: list[str] = []
    for key in ("name", "street", "city", "state", "country"):
        value = props.get(key)
        if value and value not in parts:
            parts.append(str(value))
    return ", ".join(parts[:3])


def resolve_photon(name: str) -> tuple[float, float, str]:
    query = urllib.parse.urlencode({"q": name, "limit": 1})
    payload = http_json(f"{PHOTON_API}?{query}")
    if not isinstance(payload, dict):
        raise SystemExit(f"Photon returned no feature for {name!r}")
    features = payload.get("features")
    if not isinstance(features, list) or not features:
        raise SystemExit(f"Photon returned no feature for {name!r}")
    feature = features[0]
    if not isinstance(feature, dict):
        raise SystemExit(f"Photon returned no feature for {name!r}")
    geometry = feature.get("geometry") or {}
    coords = geometry.get("coordinates") if isinstance(geometry, dict) else None
    if not isinstance(coords, list) or len(coords) < 2:
        raise SystemExit(f"Photon feature for {name!r} has no coordinates")
    try:
        lon = float(coords[0])
        lat = float(coords[1])
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"Photon feature for {name!r} has no coordinates") from exc
    props = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}
    label = photon_label(props) or name
    return lat, lon, label


def resolve_endpoint(coord: str | None, name: str | None, which: str) -> tuple[float, float, str | None]:
    if (coord is None) == (name is None):
        raise SystemExit(f"set exactly one of --{which} or --{which}-name")
    if coord is not None:
        lat, lon = parse_lat_lon(coord, f"--{which}")
        return lat, lon, None
    lat, lon, label = resolve_photon(name)
    return lat, lon, label


def fetch_osrm(from_lat: float, from_lon: float, to_lat: float, to_lon: float) -> list[tuple[float, float]]:
    path = f"{from_lon},{from_lat};{to_lon},{to_lat}"
    url = f"{OSRM_ROUTE}{path}?overview=full&geometries=geojson"
    payload = http_json(url)
    if not isinstance(payload, dict) or payload.get("code") != "Ok":
        code = payload.get("code") if isinstance(payload, dict) else "invalid"
        raise SystemExit(f"OSRM route failed: {code}")
    routes = payload.get("routes")
    if not isinstance(routes, list) or not routes:
        raise SystemExit("OSRM returned no routes")
    geometry = routes[0].get("geometry") if isinstance(routes[0], dict) else None
    coords = geometry.get("coordinates") if isinstance(geometry, dict) else None
    if not isinstance(coords, list) or len(coords) < 1:
        raise SystemExit("OSRM route has no geometry")
    out: list[tuple[float, float]] = []
    for pair in coords:
        if not isinstance(pair, list) or len(pair) < 2:
            raise SystemExit("OSRM geometry is not a lon,lat line")
        try:
            lon = float(pair[0])
            lat = float(pair[1])
        except (TypeError, ValueError) as exc:
            raise SystemExit("OSRM geometry is not a lon,lat line") from exc
        if out and out[-1] == (lon, lat):
            continue
        out.append((lon, lat))
    if not out:
        raise SystemExit("OSRM route has no geometry")
    return out


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2.0) ** 2
    a = min(1.0, max(0.0, a))
    return 2.0 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def cumulative_m(coords: list[tuple[float, float]]) -> list[float]:
    out = [0.0]
    for i in range(1, len(coords)):
        lon0, lat0 = coords[i - 1]
        lon1, lat1 = coords[i]
        out.append(out[-1] + haversine_m(lat0, lon0, lat1, lon1))
    return out


def point_at_distance(
    coords: list[tuple[float, float]],
    cum: list[float],
    dist_m: float,
) -> tuple[float, float]:
    if dist_m <= 0.0 or len(coords) == 1:
        return coords[0]
    total = cum[-1]
    if dist_m >= total:
        return coords[-1]
    lo = 0
    hi = len(cum) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if cum[mid] <= dist_m:
            lo = mid
        else:
            hi = mid - 1
    i = lo
    if i >= len(coords) - 1:
        return coords[-1]
    span = cum[i + 1] - cum[i]
    frac = 0.0 if span <= 0.0 else (dist_m - cum[i]) / span
    lon0, lat0 = coords[i]
    lon1, lat1 = coords[i + 1]
    return (lon0 + frac * (lon1 - lon0), lat0 + frac * (lat1 - lat0))


def resample_1hz(
    coords: list[tuple[float, float]],
    speed_kmh: float,
) -> tuple[list[tuple[float, float]], float, float]:
    if speed_kmh <= 0.0:
        raise SystemExit("--speed-kmh must be positive")
    cum = cumulative_m(coords)
    total_m = cum[-1]
    speed_mps = speed_kmh * 1000.0 / 3600.0
    if total_m <= 0.0:
        return [coords[0]], 0.0, speed_mps
    steps = int(math.ceil(total_m / speed_mps))
    points = []
    for t in range(steps + 1):
        points.append(point_at_distance(coords, cum, min(t * speed_mps, total_m)))
    return points, total_m, speed_mps


def format_duration(seconds: float) -> str:
    whole = int(round(seconds))
    hours, rem = divmod(whole, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours} h {minutes} min {secs} s"
    if minutes:
        return f"{minutes} min {secs} s"
    return f"{secs} s"


def format_distance(meters: float) -> str:
    if meters >= 1000.0:
        return f"{meters / 1000.0:.3f} km"
    return f"{meters:.1f} m"


def xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def write_gpx(path: str, track_name: str, points: list[tuple[float, float, float]]) -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="DriftZero drive_route" xmlns="http://www.topografix.com/GPX/1/1">',
        "  <trk>",
        f"    <name>{xml_escape(track_name)}</name>",
        "    <trkseg>",
    ]
    for lon, lat, epoch in points:
        stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))
        lines.append(f'      <trkpt lat="{lat:.8f}" lon="{lon:.8f}">')
        lines.append(f"        <time>{stamp}</time>")
        lines.append("      </trkpt>")
    lines.extend(["    </trkseg>", "  </trk>", "</gpx>", ""])
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def gap_window(gap_s: int | None, gap_start_s: int | None) -> tuple[int, int] | None:
    if gap_s is None or gap_s <= 0:
        return None
    start = 0 if gap_start_s is None else gap_start_s
    if start < 0:
        raise SystemExit("--gap-start-s must be >= 0")
    return start, gap_s


def print_plan(
    from_lat: float,
    from_lon: float,
    to_lat: float,
    to_lon: float,
    from_label: str | None,
    to_label: str | None,
    total_m: float,
    speed_kmh: float,
    n_points: int,
    gap: tuple[int, int] | None,
    speed_mps: float,
) -> None:
    from_extra = f" ({from_label})" if from_label else ""
    to_extra = f" ({to_label})" if to_label else ""
    print(
        f"from {from_lat:.8f},{from_lon:.8f}{from_extra} "
        f"to {to_lat:.8f},{to_lon:.8f}{to_extra}",
        flush=True,
    )
    eta_s = 0.0 if speed_mps <= 0.0 else total_m / speed_mps
    print(
        f"route {format_distance(total_m)}, ETA {format_duration(eta_s)} "
        f"at {speed_kmh:g} km/h ({n_points} points at 1 Hz)",
        flush=True,
    )
    if gap is None:
        print("gap off", flush=True)
        return
    start, length = gap
    last_before = start - 1
    resume_at = start + length
    if last_before < 0:
        where = f"from the start through t={resume_at - 1} s"
        last_d = 0.0
    elif resume_at >= n_points:
        where = f"from t={start} s through the end of the route"
        last_d = min(last_before * speed_mps, total_m)
    else:
        where = f"from t={start} s through t={resume_at - 1} s"
        last_d = min(last_before * speed_mps, total_m)
    resume_d = total_m if resume_at >= n_points else min(resume_at * speed_mps, total_m)
    skip_m = max(0.0, resume_d - last_d)
    print(
        f"gap {length} s {where}, then resume {format_distance(skip_m)} further along the route",
        flush=True,
    )


def emit_fix(
    serial: str,
    lon: float,
    lat: float,
    dry_run: bool,
) -> None:
    cmd = [
        "adb",
        "-s",
        serial,
        "emu",
        "geo",
        "fix",
        f"{lon:.8f}",
        f"{lat:.8f}",
    ]
    print(" ".join(cmd), flush=True)
    if dry_run:
        return
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(f"adb exited {result.returncode}")


def drive(
    points: list[tuple[float, float]],
    serial: str,
    dry_run: bool,
    gap: tuple[int, int] | None,
    loop: bool,
    gpx_path: str | None,
    track_name: str,
) -> None:
    gpx_points: list[tuple[float, float, float]] = []
    epoch0 = time.time()
    elapsed = 0
    try:
        while True:
            t = 0
            while t < len(points):
                if gap is not None:
                    start, length = gap
                    if start <= t < start + length:
                        remain = (start + length) - t
                        print(
                            f"# gap: no fix for {remain} s (t={t} s to t={start + length - 1} s)",
                            flush=True,
                        )
                        if not dry_run:
                            time.sleep(remain)
                        elapsed += remain
                        t = start + length
                        continue
                if t >= len(points):
                    break
                lon, lat = points[t]
                emit_fix(serial, lon, lat, dry_run)
                gpx_points.append((lon, lat, epoch0 + elapsed))
                elapsed += 1
                t += 1
                if not dry_run and (loop or t < len(points)):
                    time.sleep(1)
            if not loop:
                break
    except KeyboardInterrupt:
        print("stopped", flush=True)
    except BrokenPipeError:
        pass
    finally:
        if gpx_path is not None:
            write_gpx(gpx_path, track_name, gpx_points)
            try:
                print(f"wrote {gpx_path} ({len(gpx_points)} fixes)", flush=True)
            except BrokenPipeError:
                pass


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    from_lat, from_lon, from_label = resolve_endpoint(args.from_coord, args.from_name, "from")
    to_lat, to_lon, to_label = resolve_endpoint(args.to_coord, args.to_name, "to")
    coords = fetch_osrm(from_lat, from_lon, to_lat, to_lon)
    points, total_m, speed_mps = resample_1hz(coords, args.speed_kmh)
    gap = gap_window(args.gap_s, args.gap_start_s)
    print_plan(
        from_lat,
        from_lon,
        to_lat,
        to_lon,
        from_label,
        to_label,
        total_m,
        args.speed_kmh,
        len(points),
        gap,
        speed_mps,
    )
    labels = [part for part in (from_label, to_label) if part]
    track_name = " to ".join(labels) if labels else "drive_route"
    try:
        drive(points, args.serial, args.dry_run, gap, args.loop, args.gpx, track_name)
    except BrokenPipeError:
        return 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        sys.exit(0)
