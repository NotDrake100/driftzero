# ADR 013: Development-only coast configuration search

Date: 2026-09-06. Status: predeclared research experiment.

After correcting the selected-column export sign, compare seven existing Kotlin
coast configurations: baseline, GNSS speed latch, persist pseudo-speed, sparse
GNSS reseed, latch plus sparse reseed, stop/restart, and weak-course hold plus
latch. No new default is shipped on the basis of this search.

Selection uses only session groups absent from the complete locked 35-interval
suite, including every letter-suffixed sibling. Twelve groups are selected by a
fixed SHA-256 ordering with seed 26168, before reading outcomes. Truth gates and
blackout construction are reused unchanged. Truth-only exclusions are recorded
before running any candidate; algorithm failures cannot remove intervals.

A candidate must score every development interval, reduce median drift by at
least 10% relative, and worsen neither p95 nor the number of intervals failing
10% drift. Eligible candidates rank by failing count, p95, then median. Save the
selection before running the winner once on all locked intervals. If none is
eligible, retain the baseline and do not rescore it unnecessarily.

The existing locked suite has been viewed in earlier work, so it is a reused
confirmation set, not a pristine unseen field test. Same-driver and unknown-route
relationships remain limitations of IO-VNBD metadata. There is no neural training
in this bounded experiment. A relative 10% improvement is not the absolute 10%
drift objective. ADR 009's baseline remains unchanged; alternative configurations
are named research ablations, not replacements for its reference row.
