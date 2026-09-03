# NavIC / IRNSS on the phone

Retrieved 2026-09-03. Independent fetch log: [SIH26168_EVIDENCE.md](SIH26168_EVIDENCE.md) section E. Product decisions are DriftZero's. ISRO and Android do not endorse the app.

Visibility is not integrity. Android can log IRNSS space vehicles when the chipset HAL reports constellation 7. `usedInFix` and `getCn0DbHz` are OS reports. They are not an integrity service. ISRO states NavIC does not provide integrity and does not support safety-of-life. GAGAN is the GPS SBAS integrity path. L1 from NVS-01 (29 May 2023) onward is the consumer-band addition. Many phones still never surface IRNSS.

This is the claim boundary for constellation logging. The live filter does not treat NavIC counts as integrity, anti-jam, or a safe-lock flag.

## What we log

Android `GnssStatus` (API 29+, `CONSTELLATION_IRNSS = 7`) is copied in `GnssLocationSource` into `NavicMonitor`. Sensor callbacks only copy satellite rows. They do not write disk, run the filter, or update Compose directly.

On each status callback we tally:

| Field | Meaning |
|---|---|
| `IRNSS visible` | Satellites whose constellation type is `CONSTELLATION_IRNSS` |
| `IRNSS used` | Of those, `usedInFix == true` |
| `GPS used` | `CONSTELLATION_GPS` and `usedInFix` |
| `Galileo used` | `CONSTELLATION_GALILEO` and `usedInFix` |

If any IRNSS satellite is in the snapshot, or IRNSS just disappeared, logcat gets one line on **change** (not every epoch):

```text
IRNSS visible=3 used=2 GPS used=8 Galileo used=4
```

Tag: `DriftZeroNavIC`. Missing IRNSS produces no line. That silence is not a NavIC outage report.

Counts never enter `DeadReckoningFilter` or `NavigationState`. They are not a GNSS-health input.

## How a tester sees NavIC satellites

1. Phone with precise location allowed, outdoors, sky view, in the NavIC service region (India and the published coverage). Many chipsets still never surface IRNSS. That is firmware, not an app bug.
2. Logcat (USB or `adb`):

```text
adb logcat -s DriftZeroNavIC
```

Expect a line like `IRNSS visible=3 used=2 GPS used=8 Galileo used=4` when the OS classifies IRNSS SVs. `visible=2 used=0` means the chipset listed IRNSS but did not use them in the current fix.

3. On-screen counts belong in the status sheet (`%1$d visible, %2$d used`). `NavicMonitor.chipLabel` can format `NavIC N` when used > 0. That is a count, not a lock. Do not compose it as integrity. Idle chrome is the mode lamp (GNSS / Assisted / Dead reckoning / Reacquiring / Low confidence), not a GPS chip.
4. Cross-check with a GNSS status app that shows constellation (GPSTest or equivalent). Match `IRNSS` / NavIC counts. Do not treat a mismatch as DriftZero "losing NavIC". We display Android's `usedInFix` for that constellation.

Hold GNSS on the mode lamp to simulate GPS off. NavIC counts and the log snapshot clear. IMU coast continues. That is a demo hold, not a jamming test. Example log numbers above are format only.

## ISRO FAQ limits (do not talk past these)

Source: [ISRO Navigation FAQ](https://www.isro.gov.in/FAQ_Navigation.html), fetched 2026-09-03. Services page: [Satellite Navigation Services](https://www.isro.gov.in/SatelliteNavigationServices.html). NVS-01: [GSLV-F12 / NVS-01](https://www.isro.gov.in/GSLV_F12_Landingpage.html) (29 May 2023). A gazetted DoT mandate that all phones must support NavIC is not independently verified on 2026-09-03. A primary MediaTek NavIC announcement page is not independently verified on 2026-09-03.

Names (FAQ Q1): NavIC (Navigation with Indian Constellation) is the operational name. IRNSS was the earlier name. Android's constant is still `IRNSS`. We log `IRNSS` and label the chip `NavIC`.

GPS is not a generic word for satellite navigation (FAQ Q3, Q4). GNSS is. The mode lamp is the provider state, not a claim that only GPS is used.

FAQ Q16, quoted in substance:

| GAGAN | NavIC |
|---|---|
| An augmentation to GPS | An independent stand-alone navigation system |
| Provides integrity information | Does not provide integrity information |
| Provides safety-of-life operation support | Does not support safety-of-life operations |

GAGAN is GPS Aided Geo Augmented Navigation, ISRO + AAI, aimed at civil aviation in Indian airspace (FAQ Q15). It sends correction **and integrity** messages for GPS. NavIC is a regional constellation. It is not GAGAN. Seeing NavIC on a phone is not GAGAN service.

SPS ICDs (signal structure, not a phone API): L5/S ICD linked from the services page, filename `irnss_sps_icd_version1.1-2017.pdf` (ISRO's spelling). L1 SPS ICD is linked from the same page. We do not parse NavIC navigation messages on the phone.

## Why judges should not hear "NavIC lock = safe"

Because ISRO already said NavIC does not provide integrity and does not support safety-of-life. GAGAN is the integrity path, and it augments GPS.

`GnssStatus.usedInFix` means the positioning engine included that SV in the **current** fix calculation. It is not:

- a RAIM / SBAS integrity flag
- authentication of the navigation message (NavIC NMA has been discussed in ICG notes; it is not an Android API we call)
- proof the measurement is unjammed or unspoofed
- proof the horizontal protection level is acceptable for aviation or any certified use

A phone in India can list IRNSS, use three of them, and still have a 20 m urban fix, a multipath bounce, or a fused location that mixed in a cell estimate. A `NavIC 3` string is a count. It is not a green board.

Missing `CONSTELLATION_IRNSS` on a supported API level usually means the chipset or GNSS HAL did not report that constellation. It does not mean NavIC satellites are down, and it does not mean the app failed a NavIC requirement.

## What we do not claim

- Anti-jam, anti-spoof, military hardening, or certified integrity from constellation flags
- Safety-of-life navigation
- That DriftZero is a NavIC receiver product or a GAGAN user
- That logging IRNSS replaces GNSS-denied inertial work. Blackout continuity is still IMU + filter

See `docs/09_SECURITY_PRIVACY_SAFETY.md` for allowed integrity language. Android reference: [GnssStatus](https://developer.android.com/reference/android/location/GnssStatus) and [CONSTELLATION_IRNSS](https://developer.android.com/reference/android/location/GnssStatus#CONSTELLATION_IRNSS).
