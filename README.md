# Box Yield (Android)

Shows the interest rate the market is paying on SPX **box spreads** at every expiration.
It ranks durations by how much they pay over the same-maturity Treasury, after tax, so you
can see where parking cash in a box beats T-bills, savings or money funds.

```
 laptop (cron)                         Google Drive                     phone
 boxyield_collector.py  --rclone-->  boxyield-latest.json  --HTTPS-->  Box Yield app
   Schwab Trader API                  history/…                         (ranks, after-tax)
   + Treasury.gov curve
```

## How the rate is computed

A **long box** on strikes K1 < K2 is `+C(K1) −C(K2) +P(K2) −P(K1)`. On European,
cash-settled index options (SPX, XSP, NDX, RUT) it always pays `K2 − K1` at expiry, so its
price today is a zero-coupon loan.

Put-call parity says `C − P = D·(F − K)` at every strike. The collector fits a weighted
line through `mid(C) − mid(P)` across all quoted strikes for an expiration, where tight
markets count more. Minus the slope is the discount factor `D`, and every box at that
expiration costs `D × width`. One fit across all strikes is steadier than any single
box quote. The collector also prices the widest quoted box at natural prices:

* **mid rate:** where box orders typically fill. The app ranks on this.
* **natural lend rate:** buying the box paying the ask and receiving the bid. This is the
  worst case for a lender.
* **natural borrow rate:** the reverse.

Yields use the bond-equivalent convention of Treasury's CMT curve, so the spread over
Treasuries is like-for-like:

```
yield = (width / price − 1) × 365 / days                (≤ 182 days)
yield = 2 × ((width / price)^(1 / (2·years)) − 1)       (> 182 days)
```

Expirations only a few days out are noisy: a one-cent quote error moves the annualized
rate a lot. Trust the 1-month-and-longer buckets more.

## Laptop setup (Schwab → Google Drive)

You need Python 3.9+ and the [rclone](https://rclone.org) tool. The collector is one
file and uses only the Python standard library.

### 1. Create a Schwab developer app (one time)

1. Sign in at <https://developer.schwab.com> with your Schwab login and create an app.
   Choose the **Market Data Production** product.
2. Set the callback URL to exactly `https://127.0.0.1`.
3. Wait for the status to read *Ready For Use*. Approval can take a few days.
4. Copy the **App Key** and **Secret**.

### 2. Install rclone and connect Google Drive (one time)

macOS:

```bash
brew install rclone
```

Windows:

```bash
winget install Rclone.Rclone
```

Linux:

```bash
sudo apt install rclone
```

Then create a remote named `gdrive`. Choose `drive` as the storage type and accept the
defaults; a browser opens for Google sign-in.

```bash
rclone config create gdrive drive
```

### 3. Configure and log in to the collector

```bash
git clone https://github.com/AshleyTemple/boxtrades.git && cd boxtrades/collector
```

Enter your app key and secret. When it asks for the rclone remote, enter `gdrive:BoxYield`.

```bash
python3 boxyield_collector.py setup
```

Log in. A browser opens to Schwab. After you approve, paste the address you land on.

```bash
python3 boxyield_collector.py login
```

Take a first snapshot. It prints the top spreads and uploads the file.

```bash
python3 boxyield_collector.py snapshot
```

On Windows, use `py` instead of `python3`.

### 4. Share the file and point the app at it (one time)

In Google Drive, open the `BoxYield` folder and right-click `boxyield-latest.json`. Choose
**Share → General access → Anyone with the link**, then **Copy link**. In the app, go to
**Settings → Schwab snapshot file**, paste the link and tap **Save**. Later uploads use
`rclone copyto`, which updates the same Drive file, so the link keeps working.

The file only holds market rates, nothing about your account.

### 5. Run it on a schedule

The simplest option is to leave a terminal open. This takes a snapshot every 30 minutes
during market hours:

```bash
python3 boxyield_collector.py loop --every 30
```

Or with **cron** (macOS/Linux): run `crontab -e` and add the line below with your own
paths. `--if-market-open` skips runs outside Mon–Fri 9:35–16:10 ET, whatever your
laptop's time zone.

```
*/30 * * * 1-5 /usr/bin/python3 /path/to/boxtrades/collector/boxyield_collector.py snapshot --if-market-open >> ~/.boxyield/collector.log 2>&1
```

Or with **Windows Task Scheduler**:

```bash
schtasks /Create /SC MINUTE /MO 30 /TN BoxYield /TR "py C:\path\to\boxtrades\collector\boxyield_collector.py snapshot --if-market-open"
```

The laptop has to be awake for a run to happen. The app shows how old the data is and
turns the timestamp red after 24 hours.

### 6. Log in again once a week

Schwab refresh tokens expire after **7 days**. This is Schwab's rule, and there is no way
to extend it. Run `login` again each week. `status` shows how many days are left:

```bash
python3 boxyield_collector.py status
```

### Files written

| File | Purpose |
|---|---|
| `boxyield-latest.json` | What the app reads |
| `history/boxyield-YYYY-MM-DD.json` | Last snapshot of each day |
| `history/box-rates.csv` | One row per expiration per snapshot. Open it in Google Sheets to chart spreads over time. |

Your config and tokens live in `~/.boxyield/` and are readable only by your user. Set
`BOXYIELD_HOME` to keep them somewhere else.

## The app

| Module | What's in it |
|---|---|
| `collector/` | Python script for your laptop (Schwab OAuth, chain download, rate fit, upload) plus tests |
| `core/` | Pure Kotlin, unit-tested: yield math, Treasury curve, Section 1256 tax model, ranking by duration, demo data, and a trade-print box detector for a future streaming feed |
| `app/` | Jetpack Compose UI: **Durations** (best expiration per bucket plus an after-tax comparison with savings and money market), **Expirations** (every expiration), **Settings** |

The app starts in **Demo** mode with simulated rates.

**Building:** open the folder in Android Studio, or download the debug APK attached to
each GitHub Actions run. To build and test just the Kotlin engine without the Android
SDK, run `./gradlew -PcoreOnly=true :core:test`.

## Why quotes rather than detected trades?

Sites like boxtrades.com detect boxes from the stream of *executed* trades. Schwab's API
offers quotes and last-trade updates but no option time-and-sales, so individual box
prints can't be reliably picked out of it. Quote-implied rates answer the practical
question, *what could I lend at right now?*, and they exist for every expiration, not just
the ones that traded. If you later want executed-trade detection, a feed with full OPRA
prints (Massive/Polygon, Databento, ThetaData) can drive the `BoxDetector` in `core/`.

## What else is worth tracking (for yield improvement)

Already in the app:

* **Spread over the same-maturity Treasury, by duration bucket**, ranked. This is the
  main signal.
* **After-tax spread.** Index boxes are Section 1256 contracts, taxed 60% long-term / 40%
  short-term but fully state-taxable. Treasury interest is federally taxable but
  state-exempt. In a high-tax state the after-tax answer can differ from the pre-tax
  ranking, and in a high federal bracket boxes often win.
* **Natural lend and borrow rates**, which bound what you'd pay if you crossed the market.
* **After-tax comparison** against your high-yield savings and money-market rates.

Ideas to add next:

1. **Executed box trades** from a full OPRA feed, to see where size actually trades.
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

This app analyzes market quotes. It does not place orders. Box spreads have execution,
margin and broker-handling risks, and American-style boxes (e.g. SPY) can be assigned
early. Verify tax treatment with a professional. Nothing here is investment or tax
advice.
