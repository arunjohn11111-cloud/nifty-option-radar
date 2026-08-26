# Nifty Option Radar — Phases 1-3

A private, personal-use Android app that watches NIFTY 50 spot + a locked set of
22 option contracts (11 strikes × CE/PE) for a full trading session, using only
the official Upstox API and Market Data Feed V3 WebSocket. No backend server;
everything runs on-device. Full requirements: see `PROJECT_SPEC.md`.

This checkout contains **Phases 1-3 only**, by design (see "Development
approach" in the spec):

- **Phase 1:** paste your Upstox access token, store it encrypted on-device,
  verify it against Upstox's real `GET /v2/user/profile` endpoint.
- **Phase 2:** fetch the NIFTY option chain for a (configurable) expiry via
  `GET /v2/option/contract`.
- **Phase 3:** fetch NIFTY 50 spot (`GET /v3/market-quote/ltp`), find the
  nearest strike as ATM, select 5 below + ATM + 5 above from the strikes
  Upstox actually returned, resolve CE+PE instrument keys for all 11, and
  **lock** that as today's radar (22 contracts) — reopening the app the same
  day loads the same lock back rather than rebuilding it.

Nothing about the WebSocket, local recording, or charts exists yet — that's
Phase 4 onward, built and tested one at a time.

## Building from a phone only (no computer) — GitHub Actions

`.github/workflows/build.yml` builds a debug APK entirely on GitHub's free
cloud runners and lets you download it from a phone browser — no Android
Studio, no computer. One-time setup:

1. Create a free GitHub account if you don't have one (github.com, works
   fine in a phone browser).
2. Create a new **empty** repository (no README/license added) — public or
   private both work, Actions has free minutes either way for a personal
   account.
3. Get this project's files into that repo, then push. If you have someone
   push it for you (or do it yourself later from a computer/Termux), that's
   all this step is — plain `git init && git add . && git commit && git push`
   from inside this folder.
4. Open the repo's **Actions** tab. The `Build debug APK` workflow runs
   automatically on push (or tap **Run workflow** to trigger it by hand).
5. When it finishes (a few minutes), open the run → **Artifacts** →
   download `nifty-option-radar-debug-apk` (a zip containing the `.apk`).
6. On the phone that will run the app: extract that zip, tap the `.apk`
   file to install it. Android will ask to allow "install unknown apps" for
   whichever app you opened it from (Files/Downloads) — allow it just for
   that app, just this once, per install.

This produces the exact same debug build Android Studio would, since it's
the same Gradle project — nothing in the workflow changes what gets built.

## Important — read before you open this in Android Studio

This project was written in a cloud sandbox that has **no access to Google's
Maven repo, Maven Central, or the Gradle distribution server** — only a small
allowlist of package registries (npm, PyPI, etc). That means:

- The code was written carefully and reviewed by hand. The strike-selection
  algorithm (ATM + 5-below + 5-above) was independently re-implemented in
  Python and checked against the spec's own worked example (spot 24,207 →
  ATM 24,200 → 23,950-24,450) plus an edge case (ATM near the bottom of the
  chain, fewer than 5 strikes below). Both matched. JSON-parsing logic for
  the profile/LTP/option-contract responses was cross-checked against
  Upstox's documented response shapes.
- It has **not** been compiled or run here. There is no Gradle wrapper
  (`gradlew`/`gradlew.bat`/`gradle-wrapper.jar`) checked in, because generating
  one correctly also requires reaching `services.gradle.org`, which this
  sandbox cannot do either.

**You will need to do two things on your own machine, both one-time, both
standard for any fresh Android Gradle project:**

1. Open the project folder in Android Studio (Ladybug or newer recommended).
   Android Studio will notice there's no Gradle wrapper and offer to create
   one — accept that, or run `gradle wrapper` yourself from a terminal with
   internet access if you have Gradle installed.
2. Let Android Studio sync. It will download AGP 8.6.1, Kotlin 2.0.21, and the
   AndroidX/Compose/OkHttp dependencies listed in `app/build.gradle.kts` from
   the real Google/Maven Central repos. If Android Studio's "Upgrade Assistant"
   suggests newer stable AGP/Compose/Gradle versions, it's safe to accept —
   nothing here depends on an exact version.

If sync fails on a specific version number (dependency versions drift over
time), bump just that one line in `app/build.gradle.kts` to whatever Android
Studio suggests and re-sync.

## What to test

### Phase 1

1. Build & run on a device/emulator.
2. Paste your Upstox access token (the one you already generate from your own
   Upstox login/OAuth flow — this app has no login screen and never asks for
   your API secret).
3. Tap **Save & Verify**. You should see either:
   - ✅ **CONNECTED** with your Upstox user name, user ID, broker, and
     exchanges — token is valid.
   - ❌ **FAILED** with a message straight from Upstox (e.g. "Invalid token
     used to access API") if the token is wrong/expired.
4. Kill and reopen the app — the token should still be there (encrypted via
   Android Keystore / `EncryptedSharedPreferences`), and **Re-verify saved
   token** should work without re-pasting it.

### Phase 2/3

1. From the Connected screen, tap **Continue to Phase 2/3**.
2. Confirm/edit the expiry date (defaults to `2026-09-01`) and tap **Lock
   Today's Radar**.
3. You should see 🔒 **RADAR LOCKED** with: the spot price used, the ATM
   strike, the radar range (min-max strike), and a list of all 11 strikes
   with their CE/PE instrument keys. Sanity-check the ATM strike against
   whatever NIFTY 50 actually is right now, and check the range is 5 strikes
   either side of it.
4. Kill and reopen the app, go back to Phase 2/3 — it should show 🔒 **RADAR
   ALREADY LOCKED (loaded, not rebuilt)** with the *same* strikes/instrument
   keys as before, without calling the API again. This is the "locked for the
   day" behavior from spec section 3/16 — confirm it holds.
5. If you're testing outside market hours or on a stale expiry, you may see
   warnings (e.g. "no PE contract found for strike X") — that's the expected
   degrade-gracefully behavior, not a crash.

Only once both of these work reliably should Phase 4 (Market Data Feed V3
WebSocket) get built on top.

## Security notes

- The Upstox access token is never hard-coded, never logged, never sent
  anywhere except as the `Authorization: Bearer ...` header on direct HTTPS
  calls to `api.upstox.com`, and is stored encrypted (Android Keystore /
  `EncryptedSharedPreferences`).
- The locked radar (strikes + instrument keys) is public market structure,
  not a secret — it's stored in a separate, plain `SharedPreferences` file,
  deliberately not mixed with the token store.
- `android:allowBackup="false"` so the encrypted prefs file is excluded from
  Android auto-backup.
- `network_security_config.xml` blocks cleartext HTTP entirely.
- There is deliberately no server of ours in the loop — nowhere else for the
  token to leak to.

## Project layout

```
app/src/main/java/com/niftyradar/app/
  MainActivity.kt                 Screen switcher (Auth <-> Radar setup)
  ui/AuthViewModel.kt              Phase 1: token -> verify -> Connected/Failed
  ui/RadarSetupViewModel.kt        Phase 2/3: spot + contracts -> ATM/strikes -> lock
  ui/RadarSetupScreen.kt           Phase 2/3 debug UI
  network/UpstoxApiClient.kt       Get Profile / LTP Quotes V3 / Option Contracts calls
  domain/RadarStrikeSelector.kt    Pure ATM + 5-below/5-above selection logic
  model/OptionContract.kt          One row from the Option Contracts API
  model/RadarSession.kt            The locked 22-contract radar + JSON (de)serialization
  security/SecureTokenStore.kt     EncryptedSharedPreferences wrapper (token only)
  storage/RadarSessionStore.kt     Plain SharedPreferences wrapper (locked radar only)
```

## Roadmap (unbuilt, tracked in PROJECT_SPEC.md)

Phase 4: Market Data Feed V3 WebSocket connection + subscribe to the 22
locked option instrument keys + NIFTY 50 spot (mode `full`, for OI + volume).
Phase 5: confirm live LTP/OI/OI-change/volume arriving correctly, and
auto-reconnect + re-subscribe to the same locked instruments on drop.
Phase 6: local Room database, one row per tick per instrument.
Phase 7: first single live premium chart.
Phase 8: all 22 option charts.
Phase 9: NIFTY spot chart.
Phase 10: full 23-chart radar UI (STRIKE | CE | PE grid), with the "SPOT
OUTSIDE RADAR RANGE" warning wired to `RadarStrikeSelector.isSpotWithinRadar`.
Phase 11: daily session locking + historical review across days.
