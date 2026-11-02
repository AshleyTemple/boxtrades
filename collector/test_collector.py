import datetime as dt
import random
import unittest

import boxyield_collector as c


def chain_response(expiry: str, days: int, discount: float, forward: float, strikes, noise=0.0, seed=1):
    """Fake Schwab /chains payload satisfying C - P = D (F - K)."""
    rnd = random.Random(seed)
    calls, puts = {}, {}
    for k in strikes:
        put_mid = 20 + max(k - forward, 0) * discount
        call_mid = put_mid + discount * (forward - k) + rnd.uniform(-noise, noise)
        for side, mid, book in (("CALL", call_mid, calls), ("PUT", put_mid, puts)):
            sym = f"SPXW  {expiry[2:4]}{expiry[5:7]}{expiry[8:10]}{side[0]}{int(k * 1000):08d}"
            book[f"{k:.1f}"] = [{
                "putCall": side, "symbol": sym, "optionRoot": "SPXW", "bid": round(mid - 0.5, 2),
                "ask": round(mid + 0.5, 2), "totalVolume": 10, "strikePrice": k,
                "expirationDate": f"{expiry}T20:00:00.000+00:00", "daysToExpiration": days,
            }]
    key = f"{expiry}:{days}"
    return {"underlyingPrice": forward, "callExpDateMap": {key: calls}, "putExpDateMap": {key: puts}}


class CollectorTest(unittest.TestCase):
    def test_yield_matches_kotlin_core(self):
        self.assertAlmostEqual(c.annualized_yield_pct(980, 1000, 180), 4.138, places=3)
        self.assertAlmostEqual(c.annualized_yield_pct(0.96, 1, 365), 4.1241, places=3)

    def test_recovers_discount_factor(self):
        days = 200
        target = 4.25
        years = days / 365
        discount = 1 / (1 + target / 200) ** (2 * years)
        resp = chain_response("2027-05-21", days, discount, 6000, range(5500, 6501, 25), noise=0.2)
        groups = c.parse_chain(resp)
        self.assertEqual(list(groups), [("SPXW", "2027-05-21")])
        r = c.box_rates(groups[("SPXW", "2027-05-21")], days)
        self.assertAlmostEqual(r["midYieldPct"], target, delta=0.02)
        # natural prices bracket the mid
        self.assertLess(r["lendYieldPct"], r["midYieldPct"])
        self.assertGreater(r["borrowYieldPct"], r["midYieldPct"])
        self.assertEqual((r["lowStrike"], r["highStrike"]), (5500.0, 6500.0))
        self.assertEqual(r["strikes"], 41)

    def test_rejects_too_few_quotes(self):
        resp = chain_response("2027-05-21", 200, 0.97, 6000, [5900, 6000, 6100])
        self.assertIsNone(c.box_rates(c.parse_chain(resp)[("SPXW", "2027-05-21")], 200))

    def test_treasury_csv(self):
        csv = ('Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr"\n'
               "10/29/2026,9,9,9,9,9,9,9,9\n"
               "10/30/2026,4.00,3.98,3.96,3.90,3.85,3.80,3.70,3.60\n")
        curve = c.parse_treasury_csv(csv)
        self.assertEqual(curve.date, "2026-10-30")
        self.assertEqual(curve.yield_at(91), 3.90)
        self.assertAlmostEqual(curve.yield_at(273), 3.80 + (3.70 - 3.80) * 91 / 183)

    def test_chain_windows_cover_range(self):
        today = dt.date(2026, 11, 2)
        wins = list(c.chain_windows(today, 1100))
        self.assertEqual(wins[0][0], today)
        self.assertEqual(wins[-1][1], today + dt.timedelta(days=1100))
        for (_, a_end), (b_start, _) in zip(wins, wins[1:]):
            self.assertEqual(b_start, a_end + dt.timedelta(days=1))

    def test_market_hours(self):
        ny = c.NY
        self.assertTrue(c.market_open(dt.datetime(2026, 11, 2, 10, 0, tzinfo=ny)))
        self.assertFalse(c.market_open(dt.datetime(2026, 11, 1, 10, 0, tzinfo=ny)))  # Sunday
        self.assertFalse(c.market_open(dt.datetime(2026, 11, 2, 17, 0, tzinfo=ny)))


if __name__ == "__main__":
    unittest.main()


class SnapshotEndToEndTest(unittest.TestCase):
    def test_snapshot_json_matches_app_schema(self):
        import json, tempfile
        from pathlib import Path
        from unittest import mock

        def fake_get(cfg, path, params):
            frm = dt.date.fromisoformat(params["fromDate"])
            exp = frm + dt.timedelta(days=5)
            days = (exp - dt.date.today()).days
            if days < 1 or days > 400:
                return {}
            disc = 1 / (1 + 0.042 * days / 365)
            return chain_response(exp.isoformat(), days, disc, 6000, range(5800, 6201, 25))

        curve = c.TreasuryCurve("2026-10-30", [(30, 4.0), (365, 3.7)])
        with tempfile.TemporaryDirectory() as d, \
                mock.patch.object(c, "api_get", fake_get), \
                mock.patch.object(c, "fetch_treasury_curve", lambda: curve), \
                mock.patch.object(c, "load_config", lambda: {**c.DEFAULT_CONFIG, "output_dir": d}):
            c.cmd_snapshot(mock.Mock(if_market_open=False))
            snap = json.loads((Path(d) / "boxyield-latest.json").read_text())
            self.assertTrue((Path(d) / "history" / "box-rates.csv").exists())

        self.assertEqual(snap["schema"], 1)
        self.assertTrue(snap["generatedAt"].endswith("Z"))
        self.assertEqual(snap["treasury"]["points"][0], [30, 4.0])
        self.assertGreater(len(snap["expiries"]), 5)
        e = snap["expiries"][0]
        for key in ("underlying", "root", "expiry", "days", "midYieldPct", "lendYieldPct", "borrowYieldPct",
                    "treasuryPct", "lowStrike", "highStrike", "strikes", "volume"):
            self.assertIn(key, e)
        # Cent-rounded quotes make very short expirations noisy; check a 30+ day one.
        e30 = next(x for x in snap["expiries"] if x["days"] >= 30)
        days = e30["days"]
        expected = c.annualized_yield_pct(1 / (1 + 0.042 * days / 365), 1, days)
        self.assertAlmostEqual(e30["midYieldPct"], expected, delta=0.03)
