# Careful set review for 0.5.0 (the five packages d7b0d4a5 took out)

The careful sets (D26 amendment, 0.3.5) give a brand with one or two lists only the six packages the
owner reviewed for them (`SureSetGenerator.CAREFUL_PACKAGES`), and only when every rule holds: off by
default in every list of the brand, off by default in at least `CAREFUL_MIN_LISTS` (10) lists, asked
first in none, never-disable, a stock launcher, the wizard or Live TV in none, no safety or role word,
not a TV-stack vendor package, and a facts.tsv category of ads, tracking or preloads. At the d7b0d4a5
bump five of the six were asked first in a new provisional group (UP33-lists.md, UP33-sure-sets.txt),
so rule 2 took them out of every careful set and MOKA, Stellamore and Vidda had none.

Rule for this review, as at the 2.7, b06ab21 and d7b0d4a bumps: a row goes default-off only where the
list's own header records a unit the profile matches tested with it off and rebooted. Each verdict
below changes only that package's row (a `packages` entry, or the group's values when the group holds
nothing else); the rest of each provisional group stays an ask row. No safety rule changes: the
denylist with upstream's core guard, the never-disable lists, the six reviewed packages and the
ten-list minimum are as they were, and `CarefulSetSafetyTest` passes on the new sets.

## The rows that asked first

| Package | List that asked first | Evidence in the list | Verdict |
|-|-|-|-|
| com.android.printspooler | amazon-fire-tv-stick-4k-2018 (the second unit's group, added 2026-11-01) | header: the second unit, same model and build (AFTMM, NS6711/5908), disabled since 2026-10-22, 10 days, rebooted on day one, nothing broke. That unit also had voice search, Prime Video and casting off, which says nothing against the print spooler (no printing on a TV); off by default on amazon-fire-tv-afttiff43 (switched off and rebooted on the Omni unit) and the other Fire OS lists | default-off (preloads); recess, naatyam, neodelegate and csm.htmlruntime stay ask rows (roles not documented) |
| com.android.printspooler | tcl-55p7k (the 55C655 unit's extras) | header: the 55C655 unit, same build (G10_4K_GB, STT2.230203.001 AS16), ran everything above plus the extras at the end for a day and two reboots | default-off (preloads); the calendar sync adapter, onetimeinitializer and Play Games stay ask rows |
| com.android.printspooler | tcl-beyondtv2-latin-america (the second unit's preloads) | header: the second unit, same fingerprint, applied in nine batches with screenshots between them plus a reboot check | default-off (preloads); Play Games stays an ask row |
| com.android.tv.feedbackconsent | kalley-r10g (Privacy Sandbox and telemetry) | header: on the second pass of the #1807 unit (KALLEY_FFM, the same device and firmware the profile matches) these stayed disabled across reboots; upstream then commented out the Health Connect lines of the same change as "untested across reboots" and kept these four | default-off (tracking) |
| com.android.adservices.api | kalley-r10g (Privacy Sandbox and telemetry) | the same header lines | default-off (tracking) |
| com.android.ondevicepersonalization.services | kalley-r10g (Privacy Sandbox and telemetry) | the same header lines | default-off (tracking) |
| com.android.federatedcompute.services | kalley-r10g (Privacy Sandbox and telemetry) | the same header lines | default-off (tracking) |

## The row that stays out

| Package | List | Evidence in the list | Verdict |
|-|-|-|-|
| com.android.tv.feedbackconsent and the three Privacy Sandbox packages | prismplus-q65-ultra-r10g (MOKA) | upstream PR #1146 added them on 2026-10-23; the header says the set was "tested on one set ... lived with for a few days" (before that change, 2026-10-20) and records a reboot on 2026-10-25 only on the setup wizard line, not the set with these off. kalley-r10g, the same R10G board, does record them off across reboots | stays left out (leftOut), as in 0.4.0 to 0.4.9; the MOKA careful set is the print spooler alone. Owner decision: the same-board Kalley unit and the wizard reboot after the 2026-10-23 change are enough to switch them off here too, or wait for the header |

## The brand lists the careful sets come from

The careful sets also need each package off by default in every list of the brand. Those rows did not
change; they are the rows 0.4.8 shipped:

| Brand | Lists | Package | Evidence | Verdict |
|-|-|-|-|-|
| MOKA | kalley-43g200, prismplus-q65-ultra-r10g | com.android.printspooler | kalley-43g200: tested 2026-10-20 rebooting after each group; prismplus-q65-ultra-r10g: lived with for a few days, the wizard reboot of 2026-10-25 | in the MOKA set |
| Stellamore | stellamore-t950s | com.android.printspooler, com.android.adservices.api, com.android.ondevicepersonalization.services | tested 2026-10-19, no reboot recorded; the set rests on rule 2 (off by default in 38, 16 and 13 feeding lists, asked first in none), as it did from 0.3.5 to 0.4.8 | in the Stellamore set |
| Vidda | vidda-mt9653 | com.android.printspooler | applied in batches, then two cold reboots | in the Vidda set |
| Kalley | kalley-r10g | feedbackconsent and the three Privacy Sandbox packages | the header lines above | a new careful set (Kalley had none in 0.4.8: the b06ab21 list did not name them) |

## The sets after the change

Every careful set is its 0.4.8 self again (UP33-sure-sets.txt, the "before" lines), and Kalley gets one:

- MOKA, Vidda: com.android.printspooler
- Stellamore: com.android.printspooler, com.android.adservices.api, com.android.ondevicepersonalization.services
- Kalley (new): com.android.tv.feedbackconsent and the three Privacy Sandbox packages
- back to their 0.4.8 rows: AIWA, BYINTEK, Compac, EKO, Expressluck, Freebox, google, JMGO, Jvc, Kogan,
  Motorola, onn, Polytron, RCA, SANYO, SHARP, Skyworth, SkyworthDigital
- unchanged (com.google.android.feedback only): CHiQ, Haier, Hitachi, HVCL, MTC, OnePlus, Telesystem,
  Thomson, Vestel

The full sets keep their rows (the 0.3.4 digest in ProfilesTest holds). The lists under review
(`REVIEW_PENDING`) still feed no set; philips-google-tv-ta1, xiaomi-mi-box-3, xiaomi-mitv-mssp1 and
xiaomi-mitv-ayfr0 ask for some of these packages first, which rule 2 does not read while they wait.
