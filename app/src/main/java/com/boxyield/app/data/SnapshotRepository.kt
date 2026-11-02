package com.boxyield.app.data

import com.boxyield.core.ExpiryRate
import com.boxyield.core.RateSnapshot
import com.boxyield.core.TreasuryCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Downloads and parses boxyield-latest.json written by collector/boxyield_collector.py. */
class SnapshotRepository(private val http: OkHttpClient) {

    suspend fun load(url: String): RateSnapshot = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "Paste your snapshot file's share link in Settings" }
        val req = Request.Builder().url(directDownloadUrl(url.trim())).header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "Download failed: HTTP ${r.code}" }
            val body = r.body?.string().orEmpty()
            check(body.trimStart().startsWith("{")) {
                "That link didn't return the JSON file. In Google Drive, share it as \"Anyone with the link\"."
            }
            parse(body)
        }
    }

    companion object {
        private val DRIVE_FILE = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]+)""")
        private val DRIVE_ID = Regex("""drive\.google\.com/.*[?&]id=([A-Za-z0-9_-]+)""")

        /** Turns a Google Drive "share" link into a direct-download link; other URLs pass through. */
        fun directDownloadUrl(url: String): String {
            val id = DRIVE_FILE.find(url)?.groupValues?.get(1) ?: DRIVE_ID.find(url)?.groupValues?.get(1)
            return if (id != null) "https://drive.google.com/uc?export=download&id=$id" else url
        }

        fun parse(json: String): RateSnapshot {
            val o = JSONObject(json)
            val schema = o.optInt("schema", 1)
            check(schema == 1) { "Snapshot schema $schema is newer than this app understands; update the app" }
            val curve = o.optJSONObject("treasury")?.let { t ->
                val pts = t.getJSONArray("points")
                val map = (0 until pts.length()).associate { i ->
                    val p = pts.getJSONArray(i)
                    p.getInt(0) to p.getDouble(1)
                }
                if (map.isEmpty()) null else TreasuryCurve(LocalDate.parse(t.getString("date")), map)
            }
            val arr = o.getJSONArray("expiries")
            val rates = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                fun optD(k: String) = if (e.isNull(k)) null else e.optDouble(k).takeIf { !it.isNaN() }
                ExpiryRate(
                    underlying = e.optString("underlying"),
                    root = e.getString("root"),
                    expiry = LocalDate.parse(e.getString("expiry")),
                    days = e.getInt("days"),
                    midYieldPct = e.getDouble("midYieldPct"),
                    lendYieldPct = optD("lendYieldPct"),
                    borrowYieldPct = optD("borrowYieldPct"),
                    treasuryPct = optD("treasuryPct"),
                    lowStrike = e.optDouble("lowStrike", 0.0),
                    highStrike = e.optDouble("highStrike", 0.0),
                    strikes = e.optInt("strikes"),
                    volume = e.optLong("volume"),
                )
            }
            return RateSnapshot(
                generatedAt = Instant.parse(o.getString("generatedAt")),
                tradeDate = LocalDate.parse(o.getString("tradeDate")),
                curve = curve,
                expiries = rates,
            )
        }
    }
}
