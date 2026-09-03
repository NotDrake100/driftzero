# Security

DriftZero processes IMU and GNSS on the phone. There is no product telemetry backend and no cloud inference in the navigation loop. Default logs stay on the device. Exports require an explicit user action. See [docs/09_SECURITY_PRIVACY_SAFETY.md](docs/09_SECURITY_PRIVACY_SAFETY.md).

Until a Ready area pack is installed, the travel map still calls hosted OpenFreeMap tiles, Photon, Nominatim, and public OSRM. Those are third-party network services, not DriftZero servers. After a pack with `tiles.pmtiles` is sideloaded, rendering can stay local. Search and routing remain networked unless you replace those endpoints.

Do not commit `local.properties`, `secrets.properties`, raw trip CSVs, or private routes.

## Reporting

Open a private GitHub security advisory on [NotDrake100/driftzero](https://github.com/NotDrake100/driftzero) if the issue can expose location history, allow unsigned model or map loading, or execute untrusted replay input. For contest-week questions that are not vulnerabilities, use a normal issue.

This repository is a research prototype. It is not certified for safety-of-life navigation.
