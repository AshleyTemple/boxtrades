#!/usr/bin/env python3
"""
Box Yield collector for the Charles Schwab Trader API.

Pulls index option chains (SPX by default), computes the interest rate implied by
box spreads at every expiration, compares it with the same-maturity Treasury yield,
and writes a small JSON file the Box Yield Android app reads (e.g. from Google Drive).

Standard library only (Python 3.9+). Commands:

  python3 boxyield_collector.py setup      # one time: app key/secret, output folder, rclone remote
  python3 boxyield_collector.py login      # once a week: Schwab refresh tokens expire after 7 days
  python3 boxyield_collector.py snapshot   # one snapshot now; add --if-market-open for cron
  python3 boxyield_collector.py loop       # snapshot every N minutes during market hours
  python3 boxyield_collector.py status     # show token expiry and last snapshot

Not affiliated with Schwab. Not investment or tax advice.
"""
from __future__ import annotations

import argparse
import base64
import csv
import datetime as dt
import io
import json
import math
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

API = "https://api.schwabapi.com"
NY = ZoneInfo("America/New_York")
HOME = Path(os.environ.get("BOXYIELD_HOME", Path.home() / ".boxyield"))
CONFIG_FILE = HOME / "config.json"
TOKEN_FILE = HOME / "tokens.json"
SCHEMA_VERSION = 1

DEFAULT_CONFIG = {
    "app_key": "",
    "app_secret": "",
    "callback_url": "https://127.0.0.1",
    "output_dir": str(HOME / "output"),
    "rclone_remote": "",          # e.g. "gdrive:BoxYield"; empty = don't upload
    "underlyings": ["$SPX"],      # Schwab index symbols; "$XSP", "$NDX", "$RUT" also work
    "max_days": 1100,
    "strike_count": 40,           # strikes above and below the money per expiration
}


# --------------------------------------------------------------------------------------
# Yield math (mirrors core/.../YieldMath.kt so the app and collector agree)
# --------------------------------------------------------------------------------------

def annualized_yield_pct(price: float, face: float, days: int) -> float:
    """Bond-equivalent yield, the convention of Treasury's CMT curve."""
    if price <= 0 or face <= 0 or days <= 0:
        raise ValueError("price, face and days must be positive")
    growth = face / price
    years = days / 365.0
    if days <= 182:
        y = (growth - 1) * 365.0 / days
    else:
        y = 2 * (growth ** (1 / (2 * years)) - 1)
    return y * 100


# --------------------------------------------------------------------------------------
# Treasury curve
# --------------------------------------------------------------------------------------

TENORS = {
    "1 MO": 30, "1.5 MONTH": 45, "2 MO": 61, "3 MO": 91, "4 MO": 122, "6 MO": 182,
    "1 YR": 365, "2 YR": 730, "3 YR": 1095, "5 YR": 1826, "7 YR": 2557,
    "10 YR": 3652, "20 YR": 7305, "30 YR": 10957,
}


@dataclass
class TreasuryCurve:
    date: str
    points: list  # [(days, yield_pct)] sorted

    def yield_at(self, days: int) -> float:
        p = self.points
        if days <= p[0][0]:
            return p[0][1]
        if days >= p[-1][0]:
            return p[-1][1]
        for (d0, y0), (d1, y1) in zip(p, p[1:]):
            if d0 <= days <= d1:
                return y0 + (y1 - y0) * (days - d0) / (d1 - d0)
        return p[-1][1]


def parse_treasury_csv(text: str) -> Optional[TreasuryCurve]:
    rows = list(csv.reader(io.StringIO(text.strip())))
    if len(rows) < 2:
        return None
    header = [h.strip().upper() for h in rows[0]]
    best = None
    for row in rows[1:]:
        try:
            m, d, y = (int(x) for x in row[0].split("/"))
            date = dt.date(y, m, d)
        except (ValueError, IndexError):
            continue
        if best is None or date > best[0]:
            best = (date, row)
    if best is None:
        return None
    date, row = best
    pts = []
    for i, h in enumerate(header):
        if h in TENORS and i < len(row):
            try:
                pts.append((TENORS[h], float(row[i])))
            except ValueError:
                pass
    return TreasuryCurve(date.isoformat(), sorted(pts)) if pts else None


def fetch_treasury_curve() -> Optional[TreasuryCurve]:
    year = dt.date.today().year
    for y in (year, year - 1):
        url = ("https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
               f"daily-treasury-rates.csv/{y}/all?type=daily_treasury_yield_curve"
               f"&field_tdr_date_value={y}&page&_format=csv")
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "boxyield"}), timeout=30) as r:
                curve = parse_treasury_csv(r.read().decode("utf-8-sig"))
                if curve:
                    return curve
        except (urllib.error.URLError, TimeoutError) as e:
            log(f"Treasury fetch failed ({y}): {e}")
    return None


# --------------------------------------------------------------------------------------
# Box rate from an option chain
# --------------------------------------------------------------------------------------

@dataclass
class Quote:
    bid: float
    ask: float
    volume: int = 0

    @property
    def mid(self) -> float:
        return (self.bid + self.ask) / 2

    @property
    def ok(self) -> bool:
        return self.bid > 0 and self.ask >= self.bid


def box_rates(strikes: dict, days: int) -> Optional[dict]:
    """
    strikes: {strike: {"C": Quote, "P": Quote}} for one root + expiration.

    Put-call parity for European options: C - P = D * (F - K). Every box
    (K1 < K2) costs D * (K2 - K1), so the discount factor D is minus the slope of
    mid(C - P) against K. A weighted least-squares fit over all quoted strikes gives a
    much steadier rate than any single box. Weights favour tight markets.

    Also prices the widest quoted box at the natural (worst-case) prices:
      lend   = buy the box: pay ask on long legs, receive bid on short legs  -> lowest yield
      borrow = sell the box: the opposite                                    -> highest rate
    Real box orders usually fill between those and close to the mid rate.
    """
    pts = []
    for k, q in strikes.items():
        c, p = q.get("C"), q.get("P")
        if not c or not p or not c.ok or not p.ok:
            continue
        spread = max((c.ask - c.bid) + (p.ask - p.bid), 0.05)
        pts.append((float(k), c.mid - p.mid, 1.0 / spread ** 2))
    if len(pts) < 4 or days < 1:
        return None

    sw = sum(w for _, _, w in pts)
    mk = sum(w * k for k, _, w in pts) / sw
    mx = sum(w * x for _, x, w in pts) / sw
    skk = sum(w * (k - mk) ** 2 for k, _, w in pts)
    if skk <= 0:
        return None
    slope = sum(w * (k - mk) * (x - mx) for k, x, w in pts) / skk
    discount = -slope
    if not 0.3 < discount < 1.05:
        return None
    intercept = mx - slope * mk
    resid = [x - (intercept + slope * k) for k, x, _ in pts]
    rms = math.sqrt(sum(r * r for r in resid) / len(resid))

    lo, hi = min(p[0] for p in pts), max(p[0] for p in pts)
    width = hi - lo
    cl, pl = strikes[lo]["C"], strikes[lo]["P"]
    ch, ph = strikes[hi]["C"], strikes[hi]["P"]
    lend_price = cl.ask - ch.bid + ph.ask - pl.bid
    borrow_price = cl.bid - ch.ask + ph.bid - pl.ask

    def y(price):
        return round(annualized_yield_pct(price, width, days), 4) if 0 < price < width * 1.05 else None

    volume = sum((q["C"].volume if q.get("C") else 0) + (q["P"].volume if q.get("P") else 0) for q in strikes.values())
    return {
        "midYieldPct": round(annualized_yield_pct(discount, 1.0, days), 4),
        "lendYieldPct": y(lend_price),
        "borrowYieldPct": y(borrow_price),
        "lowStrike": lo,
        "highStrike": hi,
        "strikes": len(pts),
        # Fit residual as % of box width: rough data-quality indicator.
        "fitErrorPct": round(rms / width * 100, 4) if width > 0 else None,
        "volume": volume,
    }


def parse_chain(resp: dict) -> dict:
    """Schwab /chains response -> {(root, expiry): {strike: {"C": Quote, "P": Quote}}}."""
    out: dict = {}
    for map_name, side in (("callExpDateMap", "C"), ("putExpDateMap", "P")):
        for exp_key, by_strike in (resp.get(map_name) or {}).items():
            expiry = exp_key.split(":")[0]
            for strike_key, contracts in by_strike.items():
                for c in contracts:
                    root = (c.get("optionRoot") or c.get("symbol", "").split()[0] or "?").upper()
                    exp = (c.get("expirationDate") or expiry)[:10]
                    strike = float(c.get("strikePrice", strike_key))
                    q = Quote(float(c.get("bid") or 0), float(c.get("ask") or 0), int(c.get("totalVolume") or 0))
                    out.setdefault((root, exp), {}).setdefault(strike, {})[side] = q
    return out


# --------------------------------------------------------------------------------------
# Schwab OAuth + HTTP
# --------------------------------------------------------------------------------------

def load_json(path: Path, default=None):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def save_private(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2))
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(load_json(CONFIG_FILE, {}) or {})
    for k in ("app_key", "app_secret", "callback_url", "output_dir", "rclone_remote"):
        env = os.environ.get("BOXYIELD_" + k.upper())
        if env:
            cfg[k] = env
    return cfg


def token_request(cfg: dict, form: dict) -> dict:
    basic = base64.b64encode(f"{cfg['app_key']}:{cfg['app_secret']}".encode()).decode()
    req = urllib.request.Request(
        f"{API}/v1/oauth/token",
        data=urllib.parse.urlencode(form).encode(),
        headers={"Authorization": f"Basic {basic}", "Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            tok = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Schwab token request failed: HTTP {e.code} {e.read().decode(errors='replace')}")
    now = time.time()
    tok["access_expires_at"] = now + int(tok.get("expires_in", 1800))
    return tok


def access_token(cfg: dict, force_refresh: bool = False) -> str:
    tok = load_json(TOKEN_FILE)
    if not tok:
        raise SystemExit("Not logged in. Run: python3 boxyield_collector.py login")
    if not force_refresh and tok.get("access_expires_at", 0) - 60 > time.time():
        return tok["access_token"]
    if tok.get("refresh_expires_at", 0) < time.time():
        raise SystemExit("Schwab refresh token expired (they last 7 days). Run: python3 boxyield_collector.py login")
    new = token_request(cfg, {"grant_type": "refresh_token", "refresh_token": tok["refresh_token"]})
    new.setdefault("refresh_token", tok["refresh_token"])
    new["refresh_expires_at"] = tok["refresh_expires_at"]  # refreshing does not extend the 7 days
    save_private(TOKEN_FILE, new)
    return new["access_token"]


def api_get(cfg: dict, path: str, params: dict) -> dict:
    url = f"{API}{path}?{urllib.parse.urlencode(params)}"
    last_code = None
    for attempt in range(4):
        token = access_token(cfg, force_refresh=attempt > 0 and last_code == 401)
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            last_code = e.code
            body = e.read().decode(errors="replace")[:300]
            if e.code in (401, 429, 500, 502, 503, 504) and attempt < 3:
                log(f"HTTP {e.code} from Schwab, retrying… {body}")
                time.sleep(2 ** attempt * 2)
                continue
            raise RuntimeError(f"Schwab HTTP {e.code}: {body}")
        except (urllib.error.URLError, TimeoutError) as e:
            last_code = None
            if attempt < 3:
                time.sleep(2 ** attempt * 2)
                continue
            raise RuntimeError(f"Network error: {e}")
    raise RuntimeError("unreachable")


# --------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------

def log(msg: str) -> None:
    print(f"[{dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def cmd_setup(_args) -> None:
    cfg = load_config()
    print("Box Yield collector setup. Press Enter to keep the value in [brackets].\n")
    print("Create an app at https://developer.schwab.com (product: 'Market Data Production'),")
    print("with callback URL exactly https://127.0.0.1 . Copy its App Key and Secret here.\n")
    for key, prompt, secret in (
        ("app_key", "Schwab App Key", False),
        ("app_secret", "Schwab App Secret", True),
        ("callback_url", "Callback URL", False),
        ("output_dir", "Output folder (can be inside your Google Drive folder)", False),
        ("rclone_remote", "rclone remote to upload to, e.g. gdrive:BoxYield (blank = none)", False),
    ):
        shown = ("*" * 6 if cfg[key] else "") if secret else cfg[key]
        val = input(f"{prompt} [{shown}]: ").strip()
        if val:
            cfg[key] = val
    save_private(CONFIG_FILE, cfg)
    print(f"\nSaved {CONFIG_FILE}. Next: python3 {Path(sys.argv[0]).name} login")


def cmd_login(_args) -> None:
    cfg = load_config()
    if not cfg["app_key"] or not cfg["app_secret"]:
        raise SystemExit("Run setup first.")
    url = f"{API}/v1/oauth/authorize?" + urllib.parse.urlencode(
        {"client_id": cfg["app_key"], "redirect_uri": cfg["callback_url"]})
    print("1. Sign in to Schwab and approve access in the browser window that opens:")
    print(f"   {url}")
    print("2. The browser then goes to an address starting with", cfg["callback_url"])
    print("   and shows an error page. That's expected. Copy the full address from the address bar.\n")
    try:
        webbrowser.open(url)
    except webbrowser.Error:
        pass
    redirected = input("3. Paste it here: ").strip()
    code = urllib.parse.parse_qs(urllib.parse.urlparse(redirected).query).get("code", [None])[0]
    if not code:
        raise SystemExit("No ?code= found in that address.")
    tok = token_request(cfg, {"grant_type": "authorization_code", "code": code, "redirect_uri": cfg["callback_url"]})
    tok["refresh_expires_at"] = time.time() + 7 * 24 * 3600
    save_private(TOKEN_FILE, tok)
    exp = dt.datetime.fromtimestamp(tok["refresh_expires_at"]).strftime("%a %b %d %H:%M")
    print(f"Logged in. Login again before {exp} (Schwab refresh tokens last 7 days).")


def chain_windows(today: dt.date, max_days: int):
    """Small date windows keep each /chains response a manageable size."""
    start = 0
    while start <= max_days:
        step = 14 if start < 60 else 45 if start < 400 else 180
        end = min(start + step - 1, max_days)
        yield today + dt.timedelta(days=start), today + dt.timedelta(days=end)
        start = end + 1


def take_snapshot(cfg: dict) -> dict:
    now = dt.datetime.now(dt.timezone.utc)
    today = now.astimezone(NY).date()
    curve = fetch_treasury_curve()
    if curve is None:
        log("WARNING: no Treasury curve; spreads will be blank")
    expiries = []
    underlyings = []
    for sym in cfg["underlyings"]:
        groups: dict = {}
        price = None
        for frm, to in chain_windows(today, int(cfg["max_days"])):
            resp = api_get(cfg, "/marketdata/v1/chains", {
                "symbol": sym, "contractType": "ALL", "strikeCount": cfg["strike_count"],
                "includeUnderlyingQuote": "true", "fromDate": frm.isoformat(), "toDate": to.isoformat(),
            })
            price = resp.get("underlyingPrice") or price
            for key, strikes in parse_chain(resp).items():
                groups.setdefault(key, {}).update(strikes)
        underlyings.append({"symbol": sym, "price": price})
        for (root, exp), strikes in sorted(groups.items(), key=lambda kv: (kv[0][1], kv[0][0])):
            days = (dt.date.fromisoformat(exp) - today).days
            r = box_rates(strikes, days)
            if not r:
                continue
            tsy = round(curve.yield_at(days), 4) if curve else None
            expiries.append({"underlying": sym, "root": root, "expiry": exp, "days": days, "treasuryPct": tsy, **r})
        log(f"{sym}: {len(groups)} expirations, {sum(1 for e in expiries if e['underlying'] == sym)} with a usable rate")
    return {
        "schema": SCHEMA_VERSION,
        "source": "schwab",
        "generatedAt": now.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "tradeDate": today.isoformat(),
        "treasury": {"date": curve.date, "points": curve.points} if curve else None,
        "underlyings": underlyings,
        "expiries": expiries,
    }


def write_outputs(cfg: dict, snap: dict) -> list:
    out = Path(cfg["output_dir"]).expanduser()
    (out / "history").mkdir(parents=True, exist_ok=True)
    latest = out / "boxyield-latest.json"
    daily = out / "history" / f"boxyield-{snap['tradeDate']}.json"
    body = json.dumps(snap, indent=1)
    for p in (latest, daily):
        tmp = p.with_suffix(".tmp")
        tmp.write_text(body)
        tmp.replace(p)
    # One row per expiry per snapshot: handy for charting spread history in a spreadsheet.
    hist = out / "history" / "box-rates.csv"
    new = not hist.exists()
    with hist.open("a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["generatedAt", "root", "expiry", "days", "midYieldPct", "lendYieldPct", "borrowYieldPct", "treasuryPct", "volume"])
        for e in snap["expiries"]:
            w.writerow([snap["generatedAt"], e["root"], e["expiry"], e["days"], e["midYieldPct"],
                        e["lendYieldPct"], e["borrowYieldPct"], e["treasuryPct"], e["volume"]])
    return [latest, daily, hist]


def upload(cfg: dict, files: list) -> None:
    remote = cfg.get("rclone_remote", "").rstrip("/")
    if not remote:
        return
    if not shutil.which("rclone"):
        log("rclone not found on PATH; skipping upload")
        return
    out = Path(cfg["output_dir"]).expanduser()
    for f in files:
        rel = Path(f).relative_to(out).as_posix()
        # copyto updates the existing Drive file in place, so its share link stays the same.
        res = subprocess.run(["rclone", "copyto", str(f), f"{remote}/{rel}"], capture_output=True, text=True)
        if res.returncode != 0:
            log(f"rclone failed for {rel}: {res.stderr.strip()}")
    log(f"Uploaded to {remote}")


def summary(snap: dict) -> str:
    rows = [e for e in snap["expiries"] if e["treasuryPct"] is not None]
    if not rows:
        return "no rates"
    best = sorted(rows, key=lambda e: e["midYieldPct"] - e["treasuryPct"], reverse=True)[:5]
    lines = ["Top spreads over Treasury (mid):"]
    for e in best:
        lines.append(f"  {e['root']:5} {e['expiry']} {e['days']:>5}d  box {e['midYieldPct']:.3f}%  "
                     f"UST {e['treasuryPct']:.3f}%  {(e['midYieldPct'] - e['treasuryPct']) * 100:+.0f} bp")
    return "\n".join(lines)


def cmd_snapshot(args) -> None:
    if getattr(args, "if_market_open", False) and not market_open(dt.datetime.now(dt.timezone.utc)):
        return
    cfg = load_config()
    snap = take_snapshot(cfg)
    files = write_outputs(cfg, snap)
    log(f"Wrote {files[0]} ({len(snap['expiries'])} expirations)")
    print(summary(snap))
    upload(cfg, files)


def market_open(now: dt.datetime) -> bool:
    t = now.astimezone(NY)
    return t.weekday() < 5 and dt.time(9, 35) <= t.time() <= dt.time(16, 10)


def cmd_loop(args) -> None:
    log(f"Snapshot every {args.every} min during market hours (Mon–Fri 9:35–16:10 ET). Ctrl+C to stop.")
    while True:
        if market_open(dt.datetime.now(dt.timezone.utc)):
            try:
                cmd_snapshot(args)
            except (RuntimeError, OSError) as e:
                log(f"Snapshot failed: {e}")
        time.sleep(args.every * 60)


def cmd_status(_args) -> None:
    cfg = load_config()
    tok = load_json(TOKEN_FILE)
    print(f"Config: {CONFIG_FILE} ({'ok' if cfg['app_key'] else 'missing app key'})")
    if tok:
        left = tok.get("refresh_expires_at", 0) - time.time()
        print(f"Login: {'expired' if left <= 0 else f'valid for {left / 86400:.1f} more days'}")
    else:
        print("Login: not logged in")
    latest = Path(cfg["output_dir"]).expanduser() / "boxyield-latest.json"
    snap = load_json(latest)
    print(f"Last snapshot: {snap['generatedAt'] if snap else 'none'} ({latest})")
    print(f"Upload: {cfg['rclone_remote'] or 'off'}")


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("setup").set_defaults(fn=cmd_setup)
    sub.add_parser("login").set_defaults(fn=cmd_login)
    sp = sub.add_parser("snapshot")
    sp.add_argument("--if-market-open", action="store_true",
                    help="do nothing outside Mon–Fri 9:35–16:10 ET (for cron / Task Scheduler)")
    sp.set_defaults(fn=cmd_snapshot)
    lp = sub.add_parser("loop")
    lp.add_argument("--every", type=int, default=30, help="minutes between snapshots (default 30)")
    lp.set_defaults(fn=cmd_loop)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    args = ap.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    main()
