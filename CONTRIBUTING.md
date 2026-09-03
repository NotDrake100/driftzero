# Contributing

DriftZero is an Android-first navigator plus a Kotlin filter and a Python research harness. Read [AGENTS.md](AGENTS.md) and [PRD.md](PRD.md) before changing a boundary.

## Three test surfaces

JDK 17 for JVM and Android. Python 3.10+ for `ml/`. From the repository root:

```bash
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
make validate
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:test --no-daemon
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --no-daemon
```

`make validate` checks `contracts/` JSON and reruns the Python tests. It does not invoke Gradle. `make links` checks local markdown paths. `make lint` needs `./ml[dev]` (Ruff). Unit tests in `ml/tests` use the standard library only. Optional extras (`numpy`, `torch`, `timesfm`) stay behind `./ml[research]`.

`pytest.ini` uses the same `ml/src` path and `ml/tests` tree. Either runner is fine.

## Change protocol

From [AGENTS.md](AGENTS.md):

- Read the relevant ADR and contract before modifying a boundary.
- Add tests before or with algorithm changes.
- Record experiment configuration and seed.
- Add a short ADR for a new architectural decision.
- Update [docs/01_SIH_REQUIREMENTS_TRACEABILITY.md](docs/01_SIH_REQUIREMENTS_TRACEABILITY.md) when behavior changes.
- For performance claims, attach the evaluation manifest and raw per-trip table under `results/`. Do not invent numbers.

Python research stays in `ml/`. Kotlin algorithms stay in `packages/navigation-core`. The live path is causal. Hidden GNSS during a blackout is score-only.

## Commit messages

Recent history uses one sentence that states why the change exists, then a period:

- Drop planning memos so the tree is a product plus equation refs.
- Snapshot travel map, ESKF pose, and student weights for landing on main.

Keep that style. Do not put secrets, machine paths, or raw dataset dumps in a commit.

## Data that must not enter Git

- `data/raw/` IO-VNBD or other CSVs. Fetch locally. See [scripts/fetch_datasets.md](scripts/fetch_datasets.md).
- TimesFM checkpoints, ONNX/PyTorch binaries, `*.pmtiles`, `local.properties`, APKs.
- The only IO-VNBD CSV allowed in Git is the 3-row fixture `ml/tests/fixtures/io_vnbd_s_vta9_head.csv`.

## Definition of done

Tests pass on the surfaces you touched. After a Ready area pack is installed, rendering can stay local. Until then, hosted tiles, Photon, Nominatim, and OSRM use the network. Edge cases and fallbacks have tests. Docs state whether the change is prototype, research-only, or production-path.
