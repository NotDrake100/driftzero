# ADR 008: Unique-gap reseed only when current GNSS spacing is sparse

- Status: accepted
- Date: 2026-09-03
- Path: prototype / research-only replay flag. Live phone defaults stay off.

## Context

v6/v7 unique-gap reseed fires when the last accepted unique-fix gap is at least T seconds. On IO-VNBD that helped sparse (about 9 s) truth, and it snapped 1 Hz trips that had one historical 9 s hop. v7 recorded S-Vta2:d50 moving from 12.46 m to 34.25 m on that hop even though the hop at mask start was 1 s. S-S1:mid reseeds on a last unique hop of 9 s and leaves about 163 m versus v5 55 m.

The live phone is about 1 Hz GNSS. A tunnel-length gap can still be a real coast, but a single old hop on an otherwise 1 Hz stream must not teleport the puck.

## Decision

Add `InsConfig.gnssReseedRequireSparseSpacing` (default false). When true, a unique-gap reseed also requires the median of the last `gnssReseedMinSparseHops` unique intervals, including the candidate hop, to be at least `gnssReseedAfterS`. Fewer hops fail closed.

Replay exposes `--gnss-reseed-require-sparse`. Official v8 rows that use reseed pass T=8 s and min hops=3. Live `InsConfig` keeps `gnssReseedAfterS=0`. Hidden GNSS inside a mask is never an input. TimesFM stays off the phone. Road heading is not applied on IO-VNBD (no APK graph for those roads).

## Consequences

- v6/v7 hashes stay when the flag is off.
- A 1 Hz stream with one 9 s hop does not reseed.
- A proven 9 s unique stream can still reseed after three hops.
- IO-VNBD may still lack a single official row that beats persist on both p50s and keeps the 1 Hz and named-interval gates. That is a dataset limit, not a license to blend v5_1hz and v6_sparse.
