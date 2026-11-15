package com.nazmiefearmutcu.flowmap.mobile.data.news

import android.util.Xml
import com.nazmiefearmutcu.flowmap.mobile.data.FearGreed
import com.nazmiefearmutcu.flowmap.mobile.data.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.sqrt

enum class NewsCategory(val label: String) {
    CRYPTO("Crypto"),
    MARKETS("Markets"),
    ECONOMY("Economy"),
    CENTRAL_BANKS("Central banks"),
    REGULATION("Regulation"),
}

/** Publisher tier, from the Awareness registry: 1 official + top wires, 2 major financial press, 3 quality specialist. */
data class Feed(val publisher: String, val tier: Int, val category: NewsCategory, val url: String)

data class NewsItem(
    val title: String,
    val url: String,
    val publisher: String,
    val tier: Int,
    val category: NewsCategory,
    val published: Long,
    val summary: String,
    val sentiment: Double,
)

/** Several publishers covering the same event, grouped around the highest-tier, earliest report. */
data class Story(val lead: NewsItem, val related: List<NewsItem>) {
    val sources: Int get() = 1 + related.map { it.publisher }.distinct().count { it != lead.publisher }
    val sentiment: Double get() = (listOf(lead) + related).map { it.sentiment }.average()
    val latest: Long get() = maxOf(lead.published, related.maxOfOrNull { it.published } ?: 0L)
}

data class FeedHealth(val ok: Int, val failed: Int)

/**
 * Curated feeds (English, tiers 1–3) taken from the Awareness publisher registry.
 * Each URL was verified live by Awareness; only public RSS/Atom is used.
 */
object FeedRegistry {
    val feeds = listOf(
        Feed("CoinDesk", 3, NewsCategory.CRYPTO, "https://www.coindesk.com/arc/outboundfeeds/rss/"),
        Feed("Cointelegraph", 3, NewsCategory.CRYPTO, "https://cointelegraph.com/rss"),
        Feed("Decrypt", 3, NewsCategory.CRYPTO, "https://decrypt.co/feed"),
        Feed("The Block", 3, NewsCategory.CRYPTO, "https://www.theblock.co/rss.xml"),
        Feed("Bloomberg", 1, NewsCategory.MARKETS, "https://feeds.bloomberg.com/markets/news.rss"),
        Feed("The Wall Street Journal", 2, NewsCategory.MARKETS, "https://feeds.a.dj.com/rss/RSSMarketsMain.xml"),
        Feed("CNBC", 2, NewsCategory.MARKETS, "https://www.cnbc.com/id/100003114/device/rss/rss.html"),
        Feed("MarketWatch", 2, NewsCategory.MARKETS, "https://feeds.content.dowjones.io/public/rss/mw_topstories"),
        Feed("Financial Times", 2, NewsCategory.MARKETS, "https://www.ft.com/markets?format=rss"),
        Feed("Bloomberg", 1, NewsCategory.ECONOMY, "https://feeds.bloomberg.com/economics/news.rss"),
        Feed("CNBC", 2, NewsCategory.ECONOMY, "https://www.cnbc.com/id/20910258/device/rss/rss.html"),
        Feed("Financial Times", 2, NewsCategory.ECONOMY, "https://www.ft.com/global-economy?format=rss"),
        Feed("The Economist", 2, NewsCategory.ECONOMY, "https://www.economist.com/finance-and-economics/rss.xml"),
        Feed("Federal Reserve", 1, NewsCategory.CENTRAL_BANKS, "https://www.federalreserve.gov/feeds/press_all.xml"),
        Feed("European Central Bank", 1, NewsCategory.CENTRAL_BANKS, "https://www.ecb.europa.eu/rss/press.html"),
        Feed("Bank of England", 1, NewsCategory.CENTRAL_BANKS, "https://www.bankofengland.co.uk/rss/news"),
        Feed("Bank of Japan", 1, NewsCategory.CENTRAL_BANKS, "https://www.boj.or.jp/en/rss/whatsnew.xml"),
        Feed("U.S. SEC", 1, NewsCategory.REGULATION, "https://www.sec.gov/news/pressreleases.rss"),
    )
}

/** Finance-specific word lists (a compact Loughran–McDonald style lexicon). Output is an estimate. */
object Sentiment {
    private val positive = setOf(
        "surge", "surges", "surged", "rally", "rallies", "rallied", "gain", "gains", "gained", "jump", "jumps", "jumped",
        "rise", "rises", "rose", "record", "high", "highs", "beat", "beats", "strong", "stronger", "growth", "boost", "boosts",
        "upgrade", "upgraded", "approval", "approved", "approves", "inflows", "bullish", "recover", "recovers", "recovery",
        "rebound", "rebounds", "soar", "soars", "soared", "optimism", "profit", "profits", "expands", "expansion", "climbs", "climb",
    )
    private val negative = setOf(
        "fall", "falls", "fell", "drop", "drops", "dropped", "plunge", "plunges", "plunged", "slump", "slumps", "crash", "crashes",
        "decline", "declines", "declined", "loss", "losses", "lose", "weak", "weaker", "miss", "misses", "cut", "cuts", "downgrade",
        "downgraded", "outflows", "bearish", "selloff", "sell-off", "fear", "fears", "risk", "risks", "hack", "hacked", "exploit",
        "lawsuit", "sues", "sued", "fraud", "probe", "ban", "bans", "default", "recession", "inflation", "tumble", "tumbles",
        "slide", "slides", "liquidated", "liquidations", "warning", "warns", "crisis", "tariff", "tariffs",
    )

    /** Score in [-1, 1]; 0 when no lexicon word is present. */
    fun score(text: String): Double {
        val words = text.lowercase(Locale.US).split(Regex("[^a-z\\-]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return 0.0
        val p = words.count { it in positive }
        val n = words.count { it in negative }
        if (p + n == 0) return 0.0
        return ((p - n) / sqrt((p + n).toDouble() + 1.0)).coerceIn(-1.0, 1.0)
    }
}

object NewsQuality {
    private val promo = listOf(
        "price prediction", "could reach", "presale", "pre-sale", "sponsored", "giveaway", "best crypto to buy",
        "top crypto to buy", "100x", "1000x", "next big", "don't miss", "press release", "airdrop guide",
    )

    /** Awareness quality gate: no promotional or near-empty headlines. */
    fun accept(title: String): Boolean {
        val t = title.lowercase(Locale.US)
        if (t.split(' ').count { it.isNotBlank() } < 4) return false
        return promo.none { it in t }
    }
}

object StoryClusterer {
    private val stop = setOf(
        "the", "and", "for", "with", "from", "that", "this", "into", "over", "after", "amid", "says", "said", "will", "its", "are",
        "was", "has", "have", "new", "how", "why", "what", "who", "more", "than", "but", "not", "you", "your", "about", "as", "on",
    )

    fun tokens(title: String): Set<String> =
        title.lowercase(Locale.US).split(Regex("[^a-z0-9$]+")).filter { it.length >= 3 && it !in stop }.toSet()

    fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size.toDouble()
        return inter >= 3 && inter / (a.size + b.size - inter) >= 0.34
    }

    fun cluster(items: List<NewsItem>): List<Story> {
        val sorted = items.sortedWith(compareBy<NewsItem> { it.tier }.thenBy { it.published })
        val groups = ArrayList<Pair<Set<String>, MutableList<NewsItem>>>()
        for (it in sorted) {
            val tk = tokens(it.title)
            val g = groups.firstOrNull { (lead, _) -> similar(lead, tk) }
            if (g != null) g.second += it else groups += tk to mutableListOf(it)
        }
        return groups.map { (_, list) -> Story(list.first(), list.drop(1)) }.sortedByDescending { it.latest }
    }
}

object FeedParser {
    private val datePatterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "EEE, dd MMM yyyy HH:mm Z",
    )

    fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        // RFC 822 allows "Z", "UT" and "GMT" as zones; SimpleDateFormat's Z wants a numeric offset.
        val v = s.trim().replace(Regex(" (Z|UT|UTC|GMT)$"), " +0000")
        for (p in datePatterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return f.parse(v)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private val namedEntities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“",
        "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
    )

    fun stripHtml(s: String): String {
        var t = s.replace(Regex("<[^>]+>"), " ")
        for ((k, v) in namedEntities) t = t.replace(k, v)
        t = t.replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return t.replace(Regex("[ \\t\\r\\n]+"), " ").trim()
    }

    /**
     * Parses RSS 2.0, RSS 1.0 (RDF) and Atom into (title, link, date, summary). Only direct children
     * of an item are read, so nested elements such as media:title never leak into the headline.
     * Relaxed mode keeps undeclared HTML entities (&rsquo;) from aborting the rest of the feed.
     */
    fun parse(xml: String): List<RawEntry> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(StringReader(xml))
        val out = ArrayList<RawEntry>()
        var itemDepth = -1
        var title = ""
        var link = ""
        var date = ""
        var summary = ""
        var field = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.substringAfter(':')
                    if (itemDepth < 0 && (name == "item" || name == "entry")) {
                        itemDepth = p.depth; title = ""; link = ""; date = ""; summary = ""; field = ""
                    } else if (itemDepth >= 0 && p.depth == itemDepth + 1) {
                        // Exact element names: a namespaced look-alike (media:title) is not a field.
                        field = when (p.name) {
                            "title" -> "title"
                            "link" -> "link"
                            "pubDate", "published", "updated", "dc:date" -> "date"
                            "description", "summary", "content", "content:encoded" -> "summary"
                            else -> ""
                        }
                        if (field == "link") {
                            val href = p.getAttributeValue(null, "href")
                            val rel = p.getAttributeValue(null, "rel")
                            if (href != null && (rel == null || rel == "alternate") && link.isEmpty()) link = href
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (itemDepth >= 0 && p.depth == itemDepth + 1) {
                    val t = p.text ?: ""
                    when (field) {
                        "title" -> title += t
                        "link" -> if (t.isNotBlank() && link.isEmpty()) link = t.trim()
                        "date" -> if (date.isBlank()) date = t
                        "summary" -> if (summary.length < 600) summary += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = p.name.substringAfter(':')
                    if (itemDepth >= 0 && p.depth == itemDepth && (name == "item" || name == "entry")) {
                        itemDepth = -1
                        if (title.isNotBlank() && link.isNotBlank()) out += RawEntry(stripHtml(title), link.trim(), parseDate(date), stripHtml(summary).take(280))
                    } else if (itemDepth >= 0 && p.depth == itemDepth + 1) {
                        field = ""
                    }
                }
            }
            // next() folds CDATA and entity references into TEXT events.
            ev = try {
                p.next()
            } catch (_: Exception) {
                XmlPullParser.END_DOCUMENT
            }
        }
        return out
    }

    data class RawEntry(val title: String, val link: String, val published: Long, val summary: String)
}

object NewsRepository {
    data class Result(val stories: List<Story>, val health: FeedHealth, val fetchedAt: Long)

    suspend fun load(maxAgeHours: Int = 72): Result = coroutineScope {
        val cutoff = System.currentTimeMillis() - maxAgeHours * 3_600_000L
        val results = FeedRegistry.feeds.map { feed ->
            async {
                withTimeoutOrNull(15_000) {
                    try {
                        FeedParser.parse(Http.get(feed.url)).mapNotNull { e ->
                            if (!NewsQuality.accept(e.title)) return@mapNotNull null
                            // Undated entries are kept but never presented as new: they sort last.
                            val ts = e.published
                            if (ts in 1 until cutoff) return@mapNotNull null
                            NewsItem(e.title, e.link, feed.publisher, feed.tier, feed.category, ts, e.summary, Sentiment.score(e.title + " " + e.summary))
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
        val items = results.filterNotNull().flatten().distinctBy { it.url }
        Result(StoryClusterer.cluster(items), FeedHealth(results.count { it != null }, results.count { it == null }), System.currentTimeMillis())
    }
}

object FearGreedRepository {
    /** Crypto Fear & Greed Index by alternative.me; newest first in the API, returned oldest first. */
    suspend fun history(days: Int = 60): List<FearGreed> {
        val o = JSONObject(Http.get("https://api.alternative.me/fng/?limit=$days"))
        val arr = o.getJSONArray("data")
        return List(arr.length()) { i ->
            arr.getJSONObject(i).let { FearGreed(it.getString("value").toInt(), it.getString("value_classification"), it.getString("timestamp").toLong() * 1000) }
        }.sortedBy { it.t }
    }
}
