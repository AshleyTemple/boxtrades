package com.boxyield.app.data

import com.boxyield.core.OccSymbol
import com.boxyield.core.OptionTrade
import com.boxyield.core.SimulatedMarket
import com.boxyield.core.TreasuryCurve
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A source of individual option prints. Status messages go to [onStatus]. */
interface TradeFeed {
    fun trades(onStatus: (String) -> Unit): Flow<OptionTrade>
}

/** Index root -> the underlying symbol brokers/vendors use for chain lookups. */
internal fun underlyingFor(root: String) = when (root) {
    "SPXW" -> "SPX"
    "NDXP" -> "NDX"
    "RUTW" -> "RUT"
    else -> root
}

class SimulatedFeed(private val curve: TreasuryCurve) : TradeFeed {
    override fun trades(onStatus: (String) -> Unit): Flow<OptionTrade> = flow {
        onStatus("Demo mode: simulated SPX prints")
        val sim = SimulatedMarket(curve)
        while (true) {
            sim.nextBatch(System.currentTimeMillis()).forEach { emit(it) }
            delay(700)
        }
    }
}

/** Opens a websocket and turns each text frame into zero or more trades. */
private fun webSocketFlow(
    http: OkHttpClient,
    url: String,
    onConnected: (WebSocket) -> Unit,
    parse: (String, WebSocket) -> List<OptionTrade>,
    onStatus: (String) -> Unit,
): Flow<OptionTrade> = callbackFlow {
    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onConnected(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { parse(text, webSocket) }
                .onSuccess { it.forEach { t -> trySend(t) } }
                .onFailure { onStatus("Parse error: ${it.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            close(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close()
        }
    })
    awaitClose { ws.close(1000, null) }
}

/**
 * Tradier (https://documentation.tradier.com): free real-time streaming for brokerage
 * account holders. Lists option symbols from the chains endpoint, then subscribes to
 * "timesale" events for each.
 */
class TradierFeed(
    private val http: OkHttpClient,
    private val token: String,
    private val roots: Set<String>,
    private val maxDays: Int,
) : TradeFeed {
    private val api = "https://api.tradier.com/v1"

    override fun trades(onStatus: (String) -> Unit): Flow<OptionTrade> = flow {
        require(token.isNotBlank()) { "Enter a Tradier API token in Settings" }
        onStatus("Loading option chains from Tradier…")
        val symbols = optionSymbols()
        onStatus("Subscribing to ${symbols.size} contracts…")
        val session = JSONObject(post("$api/markets/events/session")).getJSONObject("stream").getString("sessionid")
        val sub = JSONObject()
            .put("symbols", JSONArray(symbols))
            .put("sessionid", session)
            .put("filter", JSONArray(listOf("timesale")))
            .put("linebreak", true)
            .toString()
        webSocketFlow(
            http, "wss://ws.tradier.com/v1/markets/events",
            onConnected = { it.send(sub); onStatus("Streaming ${symbols.size} contracts from Tradier") },
            parse = { text, _ -> text.lineSequence().filter { it.isNotBlank() }.mapNotNull(::parse).toList() },
            onStatus = onStatus,
        ).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun parse(line: String): OptionTrade? {
        val o = JSONObject(line)
        if (o.optString("type") != "timesale") return null
        if (o.optBoolean("cancel") || o.optBoolean("correction")) return null
        val c = OccSymbol.parse(o.getString("symbol")) ?: return null
        if (c.root !in roots) return null
        return OptionTrade(
            contract = c,
            price = o.getString("last").toDouble(),
            size = o.getString("size").toDouble().toInt(),
            timestampMillis = o.getString("date").toLong(),
            exchange = o.optString("exch").ifBlank { null },
        )
    }

    private fun optionSymbols(): List<String> {
        val lastDate = LocalDate.now().plusDays(maxDays.toLong())
        val out = LinkedHashSet<String>()
        for (underlying in roots.map(::underlyingFor).toSet()) {
            val exp = JSONObject(get("$api/markets/options/expirations?symbol=$underlying&includeAllRoots=true"))
                .optJSONObject("expirations")?.opt("date")
            val dates = when (exp) {
                is JSONArray -> (0 until exp.length()).map { exp.getString(it) }
                is String -> listOf(exp)
                else -> emptyList()
            }.map(LocalDate::parse).filter { !it.isAfter(lastDate) }
            for (d in dates) {
                val opts = JSONObject(get("$api/markets/options/chains?symbol=$underlying&expiration=$d"))
                    .optJSONObject("options")?.opt("option")
                val arr = when (opts) {
                    is JSONArray -> opts
                    is JSONObject -> JSONArray().put(opts)
                    else -> continue
                }
                for (i in 0 until arr.length()) {
                    val sym = arr.getJSONObject(i).getString("symbol")
                    if (OccSymbol.parse(sym)?.root in roots) out += sym
                }
            }
        }
        return out.toList()
    }

    private fun get(url: String) = call(Request.Builder().url(url))
    private fun post(url: String) = call(Request.Builder().url(url).post(FormBody.Builder().build()))
    private fun call(b: Request.Builder): String =
        http.newCall(b.header("Authorization", "Bearer $token").header("Accept", "application/json").build())
            .execute().use { r ->
                check(r.isSuccessful) { "Tradier HTTP ${r.code}" }
                r.body!!.string()
            }
}

/**
 * Massive (formerly Polygon.io) options websocket: every OPRA print, with sale-condition codes
 * that can flag multi-leg trades. Needs a plan that includes real-time options trades.
 */
class PolygonFeed(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val roots: Set<String>,
    private val maxDays: Int,
    private val multiLegConditions: Set<String>,
) : TradeFeed {
    override fun trades(onStatus: (String) -> Unit): Flow<OptionTrade> = flow {
        require(apiKey.isNotBlank()) { "Enter a Massive/Polygon API key in Settings" }
        onStatus("Loading contracts from Massive/Polygon…")
        val tickers = contracts()
        onStatus("Subscribing to ${tickers.size} contracts…")
        webSocketFlow(
            http, "wss://socket.polygon.io/options",
            onConnected = { ws -> ws.send(JSONObject().put("action", "auth").put("params", apiKey).toString()) },
            parse = { text, ws -> handle(text, ws, tickers, onStatus) },
            onStatus = onStatus,
        ).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun handle(text: String, ws: WebSocket, tickers: List<String>, onStatus: (String) -> Unit): List<OptionTrade> {
        val arr = JSONArray(text)
        val out = ArrayList<OptionTrade>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            when (e.optString("ev")) {
                "status" -> {
                    onStatus(e.optString("message"))
                    if (e.optString("status") == "auth_success") {
                        tickers.chunked(500).forEach { chunk ->
                            ws.send(JSONObject().put("action", "subscribe")
                                .put("params", chunk.joinToString(",") { "T.$it" }).toString())
                        }
                        onStatus("Streaming ${tickers.size} contracts from Massive/Polygon")
                    }
                }
                "T" -> {
                    val c = OccSymbol.parse(e.getString("sym")) ?: continue
                    if (c.root !in roots) continue
                    val conds = e.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.get(it).toString() }.toSet() } ?: emptySet()
                    out += OptionTrade(
                        contract = c,
                        price = e.getDouble("p"),
                        size = e.getInt("s"),
                        timestampMillis = e.getLong("t"),
                        conditions = conds,
                        exchange = e.optString("x").ifBlank { null },
                        multiLeg = conds.any { it in multiLegConditions },
                    )
                }
            }
        }
        return out
    }

    private fun contracts(): List<String> {
        val last = LocalDate.now().plusDays(maxDays.toLong())
        val out = ArrayList<String>()
        for (underlying in roots.map(::underlyingFor).toSet()) {
            var url: String? = "https://api.polygon.io/v3/reference/options/contracts" +
                "?underlying_ticker=$underlying&expiration_date.lte=$last&limit=1000"
            while (url != null) {
                val sep = if ('?' in url) '&' else '?'
                val body = http.newCall(Request.Builder().url("$url${sep}apiKey=$apiKey").build()).execute().use { r ->
                    check(r.isSuccessful) { "Polygon HTTP ${r.code}" }
                    r.body!!.string()
                }
                val j = JSONObject(body)
                val results = j.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val t = results.getJSONObject(i).getString("ticker")
                    if (OccSymbol.parse(t)?.root in roots) out += t
                }
                url = j.optString("next_url").ifBlank { null }
            }
        }
        return out
    }
}
