# Data workspace

No third-party or participant raw data is committed here.

Create versioned fetch and integrity manifests, then place local files under ignored `data/raw/`. Processed outputs go under `data/interim/` and `data/processed/`. See `docs/04_DATASETS_AND_DATA_GOVERNANCE.md`.

## Screening manifest

`data/manifests/io_vnbd_screening_v1.yaml` exists. It should contain:

- canonical source URL and retrieval time;
- repository/data revision where possible;
- exact relative files and SHA-256 hashes;
- observed columns, units, timestamp rate, and coordinate frame;
- chosen complete trips and split assignments;
- fixed blackout interval IDs;
- known data-quality exclusions with reasons.

The screening plot is invalid without this manifest. Fetch commands: `scripts/fetch_datasets.md`. License table: `docs/refs/DATASETS.md`.

Local inventory (no raw CSVs opened in an editor): `results/io_vnbd_screening_v1/data_inventory.md`. `GPS SPEED (Kmh)` is metres per second on this checkout. Session-grouped splits live in `ml/src/driftzero_ml/io_vnbd/splits.py`.

## Sensitive data

No India pilot dataset exists yet. Planned Pune drives (docs/07 P2) can expose home, work, or operational locations. Store them encrypted outside Git, restrict access, keep consent metadata, and export only with deliberate redaction.

