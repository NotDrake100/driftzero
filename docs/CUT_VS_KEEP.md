# Cut vs keep: DriftZero as a product a stranger understands

Decision memo. Inspected commit `d3bad17` (`Add DriftZero SIH26168 product blueprint`) on `main`. This file is the only change from this pass. Nothing in the tree was deleted.

## What the repo actually is

This is a **specification plus a tiny evaluation harness**, not a navigator.

| Fact | Count / evidence |
|---|---|
| Markdown | ~2,100 lines across 24 `.md` files |
| Product PRD | `PRD.md` alone is 353 lines / 24 KB — the largest file |
| Runnable code | `ml/src/driftzero_ml/{metrics,blackout,timesfm_adapter}.py` plus tests |
| Android app | **None.** `apps/android/` is a README that describes a future module tree |
| Navigation engine | **None.** `packages/navigation-core/` is a README with Kotlin *interfaces* and no sources |
| Gradle / Kotlin / Compose | **None** |
| CI | **None.** No `.github/` workflows |
| MapLibre | Named in docs and ADR 003; **not wired** |
| TimesFM | Optional adapter that never imports the model; injected fake predictor in tests |
| IO-VNBD | Documented as mandatory; **no importer, no data, no plot** |

Docs outnumber engine code by about four to one. A stranger opening GitHub or the phone would meet contest IDs, phase prompts, and empty folders — not a map.

Sibling agents (in flight, do not race them): contest-ID strip, simpler Android UI, search/route. This memo tells them what not to ship. It does not rewrite the app.

## Rule

**Keep** if it ships a navigator a driver can use, or the dead-reckoning engine behind it (map, location, search, route, filter/contracts the engine will consume, evaluation that produces numbers).

**Cut** (delete or archive) if it is contest chrome, an empty Gradle shell, a duplicate of the PRD, a phase prompt that parks Android at the end, placeholder UI, or a research stub that will never run on the phone.

**Later** if it is real work but not the front door: desktop teacher, dataset importer, hidden developer replay, CI once there is an app.

Archive before `git rm` when the text still encodes constraints (no OBD-II, causal loop, score-only truth). Do not delete the tree.

## Table

| Path or feature | Cut / Keep / Later | Why | Risk if cut |
|---|---|---|---|
| **Product spine: map + location + search + route + 10 Hz engine** | **Keep** | This is the product a stranger understands: open the phone, see a map, search, navigate, keep a puck when GNSS dies. | Without it there is no DriftZero, only a paper. |
| `README.md` | **Keep** (rewrite as product; strip contest) | Front door. Today it leads with a problem-statement ID and “start here: PRD → traceability → TimesFM → Cursor phases.” | Naive delete removes the only clone instructions (`PYTHONPATH=ml/src python -m unittest …`). |
| `PRD.md` (353-line contest PRD) | **Cut volume / Keep constraints** | Keep phone-only, offline, 10 Hz, confidence, no OBD, causal algorithms. Cut SIH judge persona, TimesFM as a required milestone, “do evidence before UI,” and the restated FR dump. | Deleting the whole file loses non-negotiable scope. Slim it; do not vaporize it. |
| `AGENTS.md` | **Keep** (strip contest sentence) | The ten engineering rules are the engine’s constitution. The opening “product for [contest ID]” is chrome. | Cutting the rules invites leakage, TimesFM-on-phone, and silent zeros. |
| `NOTICE.md` | **Keep** | Honest “not certified, not safety-of-life.” Product, not contest. | Users might think this is an autopilot. |
| `docs/` volume (nine numbered essays + SOURCES) | **Cut most as the front door; Keep 02/05/06/09 + ADRs 001/003** | ~1,800 lines restating the PRD. A stranger cannot tell the product from the pitch. | Blind-delete of all `docs/` loses map/eval/privacy contracts the engine needs. |
| `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md` | **Cut** (archive) | Contest ID matrix. Sibling branding cleanup owns the strings. Not a navigator. | Loses a mapping to the original problem prose. Archive; do not keep on the README path. |
| `docs/02_ARCHITECTURE.md` | **Keep** (drop §5 TimesFM from the default story) | Describes the live loop: sensors → calibration → filter → outage machine → HMM → 10 Hz state. | Engine implementers lose thread/ownership boundaries. |
| `docs/03_TIMESFM3_STRATEGY.md` | **Later** (archive out of product docs) | Desktop research plan. The 0.3B checkpoint will never be the phone loop (ADR 002). | Cutting the file is fine; cutting the *rule* “not on the phone” is not. Keep that one sentence in `AGENTS.md`. |
| `docs/04_DATASETS_AND_DATA_GOVERNANCE.md` | **Later** | Needed when evaluation produces numbers. Not the app. IO-VNBD-as-mandatory is contest framing. | Losing split/leakage rules would corrupt future metrics. Keep those rules beside `ml/` when this file is archived. |
| `docs/05_MAPS_AND_MAP_MATCHING.md` | **Keep** | Offline PMTiles + separate road graph + no public OSM tile scraping. This is the map product. | Without it, agents will hit `tile.openstreetmap.org` or snap aggressively. |
| `docs/06_EVALUATION_PROTOCOL.md` | **Keep** | The only path that **produces numbers**: blackout drift ratio, baselines, leakage checklist. | Cutting it invites invented metrics (forbidden) or no metrics at all. |
| `docs/07_DEMO_VIDEO_SCRIPT.md` | **Cut** | Pitch screenplay. Requires on-screen contest naming, TimesFM desktop shot, IO-VNBD judge UI. | None for the product. Keep a short “how to record a real trip” later if needed. |
| `docs/08_ROADMAP_AND_BACKLOG.md` | **Cut** | Explicitly: screening proof before “decorative UI”; Android is **P5**; TimesFM is P6. This is the document that parks the phone at the end. | Lose a checklist. Replace with: app → engine → numbers. |
| `docs/09_SECURITY_PRIVACY_SAFETY.md` | **Keep** | Privacy and fail-visible behavior are product features. | Default-on logging or fake anti-spoofing claims. |
| `docs/SOURCES.md` | **Later** (archive) | Citation dump for a pitch. Not needed to open a map. | Loses URLs for MapLibre/PMTiles/IO-VNBD. Put those next to the code that uses them. |
| `docs/adr/001-hybrid-filter.md` | **Keep** | Learned pseudo-measurement + ESKF/InEKF + filter-only fallback. The engine. | People will put a black-box net in the live loop. |
| `docs/adr/002-timesfm-teacher.md` | **Later** | Correct decision, wrong prominence. Teacher stays off-device. | Forgetting the decision could put TimesFM on the phone. One sentence in `AGENTS.md` is enough if this ADR is archived. |
| `docs/adr/003-offline-maps.md` | **Keep** | MapLibre + local PMTiles + separate graph. The map. | Network maps, ToS violations, or no offline mode. |
| `contracts/sensor_frame.schema.json` | **Keep** | Units, axes, clock domain, quality. The engine will ingest this. | Silent unit mixups; “unavailable” becoming 0. |
| `contracts/navigation_state.schema.json` | **Keep** | 10 Hz output: position, speed, heading, confidence, mode, map-match. UI and filter share this. | Search/route/map siblings will invent incompatible state. |
| `configs/base.yaml` | **Keep** | Output 10 Hz, ESKF, NHC, outage timeouts, map beam, confidence gates. Engine config. | Magic numbers copied into Activities. |
| `configs/blackout_protocol.yaml` | **Keep** (change `seed: 26168`) | Evaluation that produces numbers. The seed is contest chrome. | No locked blackout suite → unreproducible claims. |
| `configs/experiment_timesfm3.yaml` | **Later** | Research-only; `production.timesfm_dependency_allowed: false` is the useful line. | None for the phone. Move under `ml/` if kept. |
| `ml/src/driftzero_ml/metrics.py` + `ml/tests/test_metrics.py` | **Keep** | Real geodesic drift-ratio math; refuses ratio on tiny/stationary paths. This produces numbers. | Would have to re-derive evaluation; easy to fake. |
| `ml/src/driftzero_ml/blackout.py` + `ml/tests/test_blackout.py` | **Keep** | Causal GNSS mask; leakage assertion. Engine evaluation, not UI. | Label leakage into the filter during “blackouts.” |
| `ml/src/driftzero_ml/timesfm_adapter.py` + its test | **Later** | Never imports TimesFM; tests a fake injected predictor. Will not run on phone. Harmless if parked under research; misleading if it looks like a product module. | None if removed from the default test story. Keep the “unavailable → explicit error” pattern if the adapter stays. |
| `ml/pyproject.toml` optional `research` extra (`timesfm[torch]`) | **Later** | Must not become a runtime/Android extra. | Accidental phone/desktop coupling. |
| Planned `ml/.../io_vnbd/` (does not exist) | **Later** | Needed to score public traces. Do **not** add an empty package. Not a blocker for the map. | Skipping forever means no public numbers. Skipping *now* is correct. |
| Planned `ml/.../{splits,features,baselines,navigation,export}` | **Later** | README wishlist. Empty stubs would be fake product. | None today. Implement when the filter exists. |
| `data/README.md` | **Keep** the “no raw data in git” rule; **Cut** “first required artifact is IO-VNBD screening.” | Workspace for manifests. Contest ordering is the problem. | Committing PII routes or huge datasets. |
| `models/README.md` | **Later** | ONNX student packaging. There is no model. | None. |
| `results/.gitkeep` | **Keep** | Output slot for evaluation numbers. | Weak. Harmless. |
| `apps/android/` (intended product) | **Keep** | The navigator. Sibling “simple Android” should put a **real** map/search/route UI here. | No consumer product. |
| `apps/android/README.md` as 90-line spec (modules, judge overlay, IRNSS) | **Cut** as user-facing spec | Describes `EngineeringOverlay`, `feature-replay` judge mode, eight Gradle modules that do not exist. | Losing permission/offline notes: copy the few product rules into `AGENTS.md` / android cursor rule. |
| Proposed empty Gradle shells (`app`, `feature-map`, `feature-calibrate`, `feature-replay`, `sensor-android`, `runtime-onnx`, `area-packages`, `trip-log`) | **Cut** (do not create empty) | Phase 0 prompt asks for a monorepo scaffold before algorithms. Empty modules are contest-shaped complexity. One app module until there is code to split. | Slightly harder later modularization. Far cheaper than eight green-but-empty projects. |
| MapLibre Native + local PMTiles | **Keep** | The map. ADR 003. Sibling search/route needs a surface. | No offline map; temptation to use Google Maps / public OSM tiles. |
| Placeholder / firmware UI (`--.- km/h`, `MAP OFFLINE`, device bezel, judge HUD) | **Cut** | See “never show” list. Not in tree today; do not introduce. | None. Showing them makes the app look like a broken instrument cluster. |
| Default-on judge / engineering overlay | **Cut** | PRD FR-11 and Android README make this a first-class screen. Drivers do not need blackout toggles. | Lose a developer tool. **Later:** hidden debug build. |
| Search and route | **Keep** | Sibling “product navigation features.” A stranger’s navigator. | A DR puck on a blank map is not navigation. |
| `packages/navigation-core/` README-only interfaces (`SensorSource`, `MotionModel`, `RoadMatcher`, `NavigationEngine`) | **Keep the contract; Cut the empty package pretence** | The *shapes* belong in `contracts/` (already JSON) and later Kotlin. A folder with only a README is a facade. | Deleting the *ideas* would fork the engine. Deleting the empty folder is safe; merge text into `docs/02` or `contracts/`. |
| Navigation-core implementation (does not exist) | **Later** → then **Keep** | This *is* the dead-reckoning engine. Build it; do not leave interfaces forever. | Never implementing it means the UI is a toy. |
| `demo/README.md`, `demo/shot-list.md` | **Cut** | Shot list includes IO-VNBD judge screen, TimesFM desktop, “SIH naming exactly as official.” | None. |
| `tasks/PHASES.md` | **Cut** | Phase 1 = IO-VNBD; Phase 7 = Android. The prompt that parks the phone at the end. | Agents might feel lost. Point them at: map UI, then filter, then numbers. |
| `tasks/CURSOR_MASTER_BUILD_PROMPT.md` | **Cut** | “Do not skip directly to UI.” Agent dump, not a product. | None. |
| `.cursor/rules/{core,android,ml}.mdc` | **Keep** (for agents, not users) | Stops TimesFM-on-phone, leakage, public OSM tiles. | Quality collapse in later coding passes. |
| `Makefile` `test` / `validate`, `pytest.ini` | **Keep** | The only existing validation: schema JSON + metric/blackout tests. | Silent breakage of the one working harness. |
| `Makefile` `lint` (`ruff`) | **Later** | Ruff is optional extra; may fail on a clean clone. | Low. |
| CI (`.github` — missing) | **Later** real; **Cut** fake | Phase 0 asks for Python + Kotlin + Android lint “where the toolchain permits.” Lint on empty Gradle is a fake green. | No CI today. Add when `ml` tests and an app exist. First job: `make validate`. |
| TimesFM 3 desktop teacher | **Later** | Optional, off-device, gated by held-out baselines. Harmless in `ml/`. Poisonous on README, demo, and home screen. | Losing the *option* is fine. Putting it in the APK is the failure mode. |
| IO-VNBD dataset / importer | **Later** | Useful so evaluation can produce public numbers. Not a navigator. Must not block Android. | No shared-dataset plot. India/phone logs can still score internally. |
| External 200 Hz IMU adapter | **Later** | Engine boundary, not consumer. No impl. | None for the phone product. |
| ONNX Runtime Mobile / student checkpoint | **Later** | No model. Filter-only fallback is the ship path (`AGENTS.md` rule 4). | App that crashes without a `.onnx`. |
| NavIC / IRNSS constellation UI | **Later** | Log in diagnostics if the phone exposes it. Do not put satellite counts on the driver map. | None. |
| Calibration flow as a blocking first screen | **Later** | Needed for good DR. A stranger should see the map first; calibrate when starting a drive. | Worse heading until then — show confidence, do not brick the UI. |

## Ten things a first-time user should never see

These are **product UX bans**. Several are not in the tree yet (there is no UI). Do not add them. If a sibling UI PR shows them, strip them before merge.

1. **Contest IDs** — problem-statement codes, `SIH-01`…`SIH-26`, sponsor-pitch chrome, “mandatory dataset” on the map or in About.
2. **`--.- km/h`** — instrument-cluster placeholder speed. If speed is unknown, omit it or say “Speed unavailable,” never a broken seven-segment fake.
3. **`MAP OFFLINE`** — all-caps diagnostic bezel. Offline is the default. If an area failed to load, say “Map not downloaded” with an action, not a firmware banner.
4. **Firmware / device bezel** — fake phone chrome, HUD ticks, scanlines, “EDGE ENGINE v0.1” frames, demo-video letterboxing inside the app.
5. **TimesFM / teacher / 0.3B / distillation** copy on any consumer screen.
6. **IO-VNBD trip picker, blackout sliders, or “score-only truth” overlays** as the first screen (developer build only, later).
7. **Raw mode enums** — `GNSS_FUSED`, `DEAD_RECKONING`, `LOW_CONFIDENCE`, `UNMATCHED` as driver-facing chips. Use plain words (“GPS,” “Estimated,” “Uncertain”).
8. **Engineering HUD** — satellite counts, config hashes, 10 Hz gap, covariance, ONNX opset, `feature-replay`.
9. **Empty-state lies** — lorem routes, a puck at Null Island, `N/A`, frozen `--` chips, “lane-level” when the matcher is unmatched.
10. **Agent/phase residue** — “P5 Android,” Cursor prompts, Gradle module names, screening checklists.

## Recommended delete order

Do not flatten the repo. Coordinate with the branding and Android siblings so you do not delete files they are rewriting. Archive first (`docs/archive/` or a `contest/` folder on a follow-up PR).

**Wave 0 — stop making it worse (now, policy)**

1. Do not add empty Gradle modules, empty `io_vnbd/` packages, or fake CI.
2. Do not put contest IDs, `--.- km/h`, `MAP OFFLINE`, or a firmware bezel in new UI.
3. Do not follow `tasks/PHASES.md` order (dataset before Android).

**Wave 1 — hide from the front door (lowest engine risk)**

4. `tasks/CURSOR_MASTER_BUILD_PROMPT.md`
5. `tasks/PHASES.md`
6. `demo/shot-list.md`
7. `docs/07_DEMO_VIDEO_SCRIPT.md`
8. `demo/README.md` (folder can go when empty)
9. Contest strings in `README.md` (sibling branding); point “start here” at the app + `make validate`, not the PRD chain.

**Wave 2 — archive contest essays (keep engine docs)**

10. `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`
11. `docs/08_ROADMAP_AND_BACKLOG.md`
12. `docs/03_TIMESFM3_STRATEGY.md` (keep “not on the phone” in `AGENTS.md`)
13. `docs/SOURCES.md`
14. Bulk of `PRD.md` (leave a short product PRD: who, what, non-goals, constraints).
15. `docs/04_DATASETS_AND_DATA_GOVERNANCE.md` after copying split/leakage rules next to `ml/`.
16. `apps/android/README.md` module-tree spec once a real app exists.
17. `packages/navigation-core/README.md` once contracts + a real core exist (or fold into `contracts/`).

**Wave 3 — demote research that will not run on the phone**

18. Move `configs/experiment_timesfm3.yaml` and `timesfm_adapter.py` under an explicit research path; drop them from the default README test story.
19. Change `configs/blackout_protocol.yaml` seed away from the contest ID; keep the protocol.
20. Do not implement TimesFM or IO-VNBD until the map and filter run.

**Wave 4 — never delete (engine and navigator)**

- `contracts/*.schema.json`
- `configs/base.yaml`
- `ml/src/driftzero_ml/{metrics,blackout}.py` and their tests
- `docs/02_ARCHITECTURE.md`, `docs/05_MAPS_AND_MAP_MATCHING.md`, `docs/06_EVALUATION_PROTOCOL.md`, `docs/09_SECURITY_PRIVACY_SAFETY.md`
- `docs/adr/001-hybrid-filter.md`, `docs/adr/003-offline-maps.md`
- `NOTICE.md`, `AGENTS.md` rules, `.cursor/rules/`
- MapLibre / PMTiles plan; future `apps/android` map, search, route, location
- Future navigation-core **implementation** (not the empty README)

**Wave 5 — add, don’t delete**

- One Android app module: map, puck, search, route, human mode/confidence.
- Filter behind `NavigationState`.
- CI that runs `make validate` (and app tests once they exist).
- Evaluation numbers only from the locked harness — never invented.

## Target shape a stranger should meet

1. **README:** DriftZero is phone navigation that keeps going when GPS drops. Offline map. No extra hardware. How to build the app. How to run tests.
2. **App:** Map. Search. Route. Blue puck. Real speed or hidden speed. Quiet “Estimated” when GNSS is gone. Growing uncertainty, not a fake snap.
3. **Engine:** `SensorFrame` in, `NavigationState` out, filter + fallback without a learned checkpoint.
4. **Lab (optional):** blackout metrics, later IO-VNBD, later TimesFM teacher — never the home screen.

Inspected as read-only except this file. No app rewrite. No tree delete.
