# Box Yield (Android)

Finds box-spread trades in a live options feed in real time. It turns each box into an
implied interest rate and ranks maturities by how much they pay over the same-maturity
Treasury, after tax. The goal is to show where parking cash in a box beats T-bills,
savings or money funds.

## How it works

A **long box** on strikes K1 < K2 is `+C(K1) −C(K2) +P(K2) −P(K1)`. On European,
cash-settled index options (SPX, XSP, NDX, RUT) it always pays `K2 − K1` at expiry. The
price you pay today is a zero-coupon loan to the market:

```
yield = (width / price − 1) × 365 / days                (≤ 182 days)
yield = 2 × ((width / price)^(1 / (2·years)) − 1)       (> 182 days, semiannual)
```

This is the same bond-equivalent convention Treasury uses for its CMT par curve, so the
spread over Treasuries is like-for-like.

Feeds report the four legs as separate prints. `BoxDetector` groups prints that share a
root and expiry, have the same size, form two strikes with a call and a put at each, and
arrive within a short window (1 s by default). The net price must also imply a plausible
rate. Matched legs are consumed so they can't be reused.

| Module | What's in it |
|---|---|
| `core/` | Pure Kotlin, unit-tested: OCC symbol parsing, box detection, yield math, Treasury curve parsing and interpolation, Section 1256 tax model, duration ranking, demo-market simulator |
| `app/` | Jetpack Compose UI: **Durations** (ranked buckets plus after-tax comparison with savings and money market), **Trades** (each detected box), **Settings** (feed, keys, tax rates, fees) |

The Treasury curve comes from Treasury's free daily par-yield CSV. No key is needed.

## Building

* **Android Studio:** open the folder and run the `app` configuration.
* **CI:** every push runs `.github/workflows/android.yml`, which runs the core tests and
  uploads a debug APK as a workflow artifact.
* **Core only (no Android SDK):** `./gradlew -PcoreOnly=true :core:test`

The app starts in **Demo** mode with simulated SPX prints. Pick a live feed in Settings.

## Data sources

| Source | What you get | Cost / access | Notes |
|---|---|---|---|
| **Tradier** (implemented) | Real-time streaming `timesale` events per option symbol | Free real-time data with a funded brokerage account | Easiest "personal brokerage feed". You must subscribe symbol by symbol, so the app lists chains first. Prints carry no multi-leg flag, so detection relies on timing. |
| **Massive (formerly Polygon.io)** (implemented) | Every OPRA trade, with sale-condition codes | Paid options plan with real-time trades | Condition codes can mark multi-leg/complex prints, which cuts false matches a lot. Enter your plan's multi-leg codes in Settings. |
| **Charles Schwab Trader API** | Streaming option quotes and trades | Free with a Schwab account; developer app approval needed | OAuth tokens expire every 7 days. A good alternative if you bank at Schwab. |
| **Interactive Brokers (TWS / Client Portal API)** | Tick-by-tick option trades, plus quotes for combos | IBKR account and OPRA subscription (~$1.50/mo non-pro) | Can also quote the box as a combo, which shows *executable* rates. IBKR is the most common broker for actually trading boxes. |
| **Databento** (OPRA.PILLAR) | Full-depth OPRA trades with the raw OPRA message types | Usage-based | Best raw data: OPRA itself labels complex/multi-leg trades. Better run on a server than on a phone. |
| **ThetaData** | Real-time and historical OPRA trades/quotes | Subscription | Popular with retail quants. Good for backtesting box spreads over time. |
| **Cboe DataShop / LiveVol** | Historical SPX trades with complex-order flags | Paid | For research and backtests rather than live use. |
| **Treasury.gov / FRED** | Daily par yield curve (`DGS1MO` … `DGS30` on FRED) | Free | Used here for the benchmark. Treasury publishes about 3:30 pm ET each day. |

**Architecture tip:** a full SPX chain is thousands of contracts. That works on a phone
over Wi-Fi, but a small always-on server (or a home PC) can run the same `core` module
against the feed and push only the detected boxes to the phone. You'd also get
history and alerts while the app is closed.

## What else is worth tracking (for yield improvement)

Already in the app:

* **Spread over same-maturity Treasury, by duration bucket**, weighted by size and ranked
  (this is the main signal).
* **After-tax spread.** Index boxes are Section 1256 contracts, taxed 60% long-term / 40%
  short-term but fully state-taxable. Treasury interest is federally taxable but
  state-exempt. In a high-tax state the after-tax answer can differ from the pre-tax
  ranking, and in a high federal bracket boxes often win.
* **Net yield after commissions and fees.** You pay fees on four legs. That matters for
  narrow boxes or small sizes.
* **After-tax comparison** against your high-yield savings and money-market rates.

Ideas to add next:

1. **Quoted (executable) box rate from NBBO.** Printed trades show where boxes *traded*.
   Quotes on the four legs (or a combo quote from IBKR) show where you can trade *now*,
   and the bid/ask gives a fair limit price.
2. **History of the spread over Treasuries per tenor.** Spreads widen around quarter-end,
   year-end and funding stress. Setting a limit order on those days pays more.
3. **Alerts.** Notify when a tenor's after-tax spread passes your threshold, or when a
   large print (> $10M face) lands.
4. **Borrowing side.** Selling a box is a loan to you. Compare its rate with your
   broker's margin rate, SBLOC and HELOC rates. It is often 1–3 points cheaper.
5. **Volume by tenor and strike width.** Where the liquidity sits tells you which
   expirations will fill near the printed rate.
6. **Other cash alternatives on the same screen**, after tax: T-bill auction results,
   SOFR, CD rates, treasury-only money funds (state-exempt), and muni money funds.
7. **Rolling ladder planner.** Show the blended yield of a ladder of boxes for a target
   cash need (e.g. a tax payment on a known date).
8. **Settlement and margin notes.** Show only European cash-settled roots (the default
   filter). Flag that a box ties up margin or cash until expiry, and that early exit
   gives back some of the bid/ask.

## Caveats

This app analyzes printed trades. It does not place orders. Box spreads have execution,
margin and broker-handling risks, and American-style boxes (e.g. SPY) can be assigned
early. Verify tax treatment with a professional. Nothing here is investment or tax
advice.
