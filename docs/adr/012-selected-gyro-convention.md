# ADR 012: Preserve selected gyro's physical sign

Date: 2026-09-06. Status: correction, not a tuned parameter.

ADR 011's export change incorrectly assumed both gyro helpers returned compass
course rate. `heading_gyro_radps` already returns minus course rate, whereas
`vertical_gyro_radps` returns course rate. Negating the selected helper a second
time reverses predicted turns. Keep the selected helper's sign and negate only
the gravity fallback. The file convention becomes `right_handed_up_v3`.

A new regression constructs left and right circular GNSS trajectories, fits only
on the prefix, and checks that the exported blackout gyro integrates to the
analytical course change. It tests the helper/export/filter convention boundary,
which the earlier isolated sign assertions failed to cover.

The previous 58.52% report is retained as measured evidence of that revision. It
must not be treated as evidence for this corrected exporter. Re-export all input
frames before scoring; no thresholds, intervals or truth gates change.
