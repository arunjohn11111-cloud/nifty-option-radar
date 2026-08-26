# Nifty Option Radar — Project Specification

Private, personal-use Android app. Only the owner uses it. No order placement,
no algo trading, no trade execution in this version — pure live monitoring and
historical charting.

## 1. Purpose

Monitor NIFTY 50 and a fixed set of NIFTY option contracts for the entire
trading day.

At market open: find the NIFTY 50 spot price, identify the ATM strike, then
select 5 strikes below ATM + ATM + 5 strikes above ATM = **11 strikes**. For
each strike, track both CE and PE = **22 option contracts**. Plus one NIFTY 50
spot chart. **Total: 23 charts.**

## 2. Worked example

Spot ≈ 24,207 → ATM = 24,200 → radar strikes: 23950, 24000, 24050, 24100,
24150, 24200 (ATM), 24250, 24300, 24350, 24400, 24450. Each strike tracked as
CE + PE (22 contracts total) plus NIFTY 50 spot.

## 3. Radar is locked for the day (critical)

Once the 11 strikes are chosen at session start, they are **never** replaced
even if spot moves far outside the range later. If spot moves outside the
radar, show a warning ("SPOT OUTSIDE RADAR RANGE") but keep tracking the
original 22 contracts — the whole point is preserving one continuous intraday
history per contract (morning → now).

## 4. Data required

**NIFTY 50 spot:** live price, timestamp/history.

**Per option contract (×22):** premium/LTP, Open Interest (OI), OI change
(ΔOI), volume, timestamp/history.

Explicitly out of scope for v1: Greeks, IV, bid/ask, market depth, order
placement, algo trading.

## 5. Charts

23 independent live line charts — one per option contract (22) plus one for
NIFTY 50 spot. Never combine multiple option premiums into a single chart.
Charts auto-update as WebSocket ticks arrive; no manual refresh. Load full
chart detail lazily (on tap) while background data collection keeps running
for all 23 regardless of what's currently visible, so the phone stays usable.

## 6. Per-contract detail

Tapping a contract shows: premium chart + OI + OI-change + volume, all against
a shared timestamp axis, so price/OI/volume relationships are visible together.

## 7–8. Data source & Upstox APIs

Official Upstox API only. No scraping, no OCR. Never ask the user to paste
API secret/credentials in chat; store only the access token, encrypted
on-device, never hard-coded, never logged.

- **Underlying:** `NSE_INDEX|Nifty 50`
- **Option Contracts API:** `GET https://api.upstox.com/v2/option/contract`
  with `instrument_key` (+ optional `expiry_date`), `Authorization: Bearer
  {access_token}`. Returns `strike_price`, `instrument_type` (CE/PE),
  `instrument_key`, `expiry`, `trading_symbol`, `lot_size`, etc. per contract.
- **Market Data Feed V3 WebSocket:** authorize via the V3 authorize endpoint,
  connect, subscribe (binary protobuf) to NIFTY 50 spot + the 22 locked
  instrument keys. Modes: `ltpc` (LTP/close only — not enough), `full`
  (LTPC + 5-level depth + extended fields + option greeks — this is the one
  that carries `oi` and `vtt`/volume), `option_greeks`, `full_d30` (Upstox
  Plus only). Use **`full`** mode so OI and volume are present. Field names
  (confirmed against Upstox docs, may drift — re-check before Phase 4):
  `ltpc.ltp`, `ltpc.cp`, `ltpc.ltt`, `ltpc.ltq`, `vtt` (volume traded today),
  `oi` (open interest).
- **Get Profile API:** `GET https://api.upstox.com/v2/user/profile` — used in
  Phase 1 purely to verify a token is valid (`data.user_name`, `data.user_id`,
  `data.broker`, `data.exchanges`, `data.is_active`).

Re-verify all of the above against the live Upstox docs before building each
phase — endpoints and field names can change.

## 9. Expiry

Configurable field, not hard-coded. Initial value: `2026-09-01`.

## 10–11. Strike selection algorithm

1. Get NIFTY 50 spot.
2. Find nearest available strike from the Option Contracts API response (do
   **not** assume a fixed 50-point interval — use whatever strikes Upstox
   actually returns) → that's ATM.
3. Take 5 strikes below + ATM + 5 strikes above = 11 strikes.
4. Resolve CE + PE `instrument_key` for each of the 11 strikes = 22 contracts.
5. Lock these 22 `instrument_key`s (+ NIFTY 50 spot) for the session.

## 12. Historical data / local storage

Continuously record every WebSocket tick during the session. Minimum fields
per row: `timestamp, instrument, strike, option_type, ltp, oi, oi_change,
volume`. Store locally (SQLite/Room), no cloud backend. Chart range: 09:15 →
now, for the current session only — never mix days.

## 13. Real-time chart behavior

Auto-update on new data, no manual refresh, efficient enough that 23
concurrently-tracked contracts don't make the phone unusable (detail charts
render lazily on selection; background recording is independent of what's on
screen).

## 14–15. UI

Header: NIFTY 50 live spot, radar range (e.g. 23,950–24,450), connection
status (CONNECTED/DISCONNECTED). Below: a compact STRIKE | CE | PE grid/list
(not 23 large charts at once) — LTP, OI, ΔOI, volume per side, tap to open the
detailed chart. Scrollable.

## 16. Daily session lifecycle

Session start: determine ATM, lock 11 strikes/22 contracts, subscribe, start
recording. Session end: stop live collection, preserve the day's data, allow
review. Never mix data across trading days.

## 17. Error handling

Handle: internet loss, WebSocket disconnect (auto-reconnect + re-subscribe to
the *same* locked instruments, never silently pick new ones), token expiry,
API errors, missing instrument, market closed, invalid expiry, missing
OI/volume fields.

## 18. Design principle

This is a visual observation tool, not a predictor. It exists so the owner can
visually compare NIFTY spot, option premium, OI, OI-change, and volume for a
fixed set of contracts across the whole session — morning vs. now.

## 19. Cost

No paid backend required for v1: Upstox API + WebSocket + on-device storage +
on-device charts only. If a backend ever becomes necessary later, that has to
be explained and justified first.

## 20. Phased build plan

1. Verify Upstox authentication/token. **(this checkout)**
2. Retrieve NIFTY option contracts for the configured expiry.
3. Find ATM, select 5 below + ATM + 5 above.
4. Connect to Market Data Feed V3 WebSocket.
5. Confirm live NIFTY spot + LTP/OI/OI-change/volume arriving.
6. Store live data locally.
7. One working live option chart.
8. Expand to all 22 option charts.
9. Add NIFTY spot chart.
10. Final 23-chart radar UI.
11. Daily session locking + historical review.

Each phase gets tested before the next one starts.

## Sandbox note (2026-08-26)

Phases 1-3 were built and code-reviewed in a cloud sandbox with no access to
Google's Maven repo / Maven Central / the Gradle distribution server — so none
of it could be compiled or run there. It was hand-reviewed line by line; the
ATM/strike-selection algorithm was independently re-implemented in Python and
checked against this doc's own worked example plus an edge case; JSON
response parsing for Get Profile, LTP Quotes V3, and Option Contracts was
cross-checked against Upstox's real documented response shapes. Actual
compilation, running, and all further phases' build-and-test cycles happen in
Android Studio on a machine with normal internet access — see `README.md`.
