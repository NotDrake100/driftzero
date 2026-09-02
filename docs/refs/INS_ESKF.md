# INS and ESKF equation map

Local-tangent strapdown plus the 15-state error-state Kalman filter. Physics is WGS84 anywhere. The n-frame here is ENU, the same local-level frame Groves writes as NED with axes `(E, N, U) = (NED_E, NED_N, −NED_D)`.

## Sources

1. Paul D. Groves, *Principles of GNSS, Inertial, and Multisensor Integrated Navigation Systems*, 2nd ed., Artech House, 2013. Equation numbers match Groves’s companion MATLAB (`Gravity_NED.m`, `Nav_equations_NED.m`, `Radii_of_curvature.m`).
2. D. H. Titterton and J. L. Weston, *Strapdown Inertial Navigation Technology*, 2nd ed., IET, 2004. Cited by section. Local geographic mechanization is §3.5.3. Quaternion rate and discrete update are §3.6.4 and §11.2.5.
3. Joan Solà, *Quaternion kinematics for the error-state Kalman filter*, arXiv:1711.02508. Equation numbers are from that PDF.
4. NIMA TR8350.2, *Department of Defense World Geodetic System 1984*, eq. (4-1) Somigliana constants.

## Mechanization (nominal INS)

| Code | Equation | What it does |
| --- | --- | --- |
| `Wgs84.meridianRadiusM` / `primeVerticalRadiusM` | Groves (2.105) | Meridian and prime-vertical radii |
| `Wgs84.gravityMps2(lat)` | Groves (2.134), TR8350.2 (4-1) | Somigliana surface gravity |
| `Wgs84.gravityMps2(lat, h)` | Groves (2.139) | Height term on (2.134) |
| `NFrameMechanization.gravityEnu` | Groves (2.134)+(2.139)+(2.140) | `g^n` in ENU. North leak is (2.140). East is 0 |
| `Wgs84.earthRateEnuRadps` | Groves (2.123) | `ω_ie^n` in ENU. **Not applied** in `step` |
| `Wgs84.coriolisAccelEnu` | Groves (5.53) piece `(2 ω_ie) × v` | Magnitude check only |
| `NFrameMechanization.navAccelFromSpecificForce` | Groves (5.54) with `ω=0`; Solà (233)/(237b) | `a^n = f^n + g^n` |
| `NFrameMechanization.averageBodyToNav` | Groves (5.84), Earth-rate term off; Titterton §11.3.1 | Interval-average `C_b^n` |
| `NFrameMechanization.gravityGradientUpPerMetre` | Groves linearized (2.139) | `∂g_U/∂h ≈ 2γ/a` |
| `NFrameMechanization.integrateAttitude` | Solà (144), (157), (260c); Titterton §11.2.5 | `q ← q ⊗ exp(ω Δt / 2)` |
| `NFrameMechanization.advance` velocity | Groves (5.54), Earth-rate off | `v ← v + a Δt` |
| `NFrameMechanization.advance` position | Groves (5.56) on a fixed tangent | `p ← p + ½ (v + v⁺) Δt` |
| `fillStrapdownPhi` | Solà (238a)/(247) plus (2.139) | Φ including `Φ_{v_U,p_U}` |
| `addImuProcessNoise` | Solà 5.4.2 / (260) | `Q_p, Q_v, Q_pv` from accel noise |
| `DeadReckoningFilter.strapdown` | calls `NFrameMechanization.advance` | Live IMU interval |

Toy check used in tests: constant `a = 1 m/s²` east, 2 s, start at rest. Groves (5.54)+(5.56) collapse to `v = a t = 2 m/s` and `p = ½ a t² = 2 m`.

## Earth rate omitted

Groves (5.53)/(5.54) also subtract `(ω_en^n + 2 ω_ie^n) × v^n`. Titterton §3.5.3 keeps the same terms.

`ω_ie = 7.292115×10⁻⁵ rad/s`. At 20 m/s, `|2 ω_ie × v| ≲ 0.003 m/s²`. Transport rate is `v/R ≈ 3×10⁻⁶ rad/s`. Configured phone accel noise is 0.20 m/s². Earth rotation on attitude is 15 deg/h, about 0.13 deg in 30 s, below consumer gyro bias. The terms are implemented as `earthRateEnuRadps` / `coriolisAccelEnu` for the bound test, then left out of `step`.

Solà notes the same skip under (232): including `ω_E` is “unjustifiably complicated” unless the IMU can see `7.3×10⁻⁵ rad/s`.

## ESKF (sibling filter, not replaced)

| Code | Equation | What it does |
| --- | --- | --- |
| `Quat.rotate` | Solà (86) | `v' = q ⊗ v ⊗ q*` |
| `DeadReckoningFilter.predictCovariance` `F_{vθ}` | Solà (247)/(238b) | Velocity error through `f^n`, not `g^n` |
| `DeadReckoningFilter.inject` | Solà (283)/(319) | Sibling polarity: left-multiply `q{−δθ}` |
| `josephUpdate` | Solà (280); Groves Ch. 14 | `P ← (I−KH)P(I−KH)ᵀ + KRKᵀ` |
| `ingestDisplacementPseudo` | TLIO §V (Liu et al.) | HACF Δp vs cloned pose. R from log σ. χ² 11.345. Overlap R×10. Clone treated as known (no extra 15 states). |
| Error state `δp, δv, δθ, b_a, b_g` | Solà Table 3, (236) | 15 states. Gravity vector is not estimated. `g^n` is Groves WGS84 |

## Files

- `packages/navigation-core/src/main/kotlin/in/driftzero/core/NFrameMechanization.kt`
- `packages/navigation-core/src/main/kotlin/in/driftzero/core/Wgs84.kt`
- `packages/navigation-core/src/main/kotlin/in/driftzero/core/DeadReckoningFilter.kt`
- `packages/navigation-core/src/main/kotlin/in/driftzero/core/EskfMath.kt`
