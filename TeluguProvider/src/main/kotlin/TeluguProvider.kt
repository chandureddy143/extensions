package com.shekar.telugu

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TeluguProvider : MainAPI() {
    // If the site changes its address, change ONLY this line.
    override var mainUrl = "https://www.5movierulz.supplies"
    override var name = "MovieRulz Telugu"
    override val hasMainPage = true
    override var lang = "te"
    override val supportedTypes = setOf(TvType.Movie)

    private val tag = "TeluguDbg"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/category/telugu-featured" to "Telugu Featured",
        "$mainUrl/category/telugu-movies-2026" to "Telugu 2026",
        "$mainUrl/category/telugu-movies-2025" to "Telugu 2025",
        "$mainUrl/category/telugu-movies-2024" to "Telugu 2024",
        "$mainUrl/category/telugu-movies-2023" to "Telugu 2023",
        "$mainUrl/category/telugu-movies-2022" to "Telugu 2022",
        "$mainUrl/language/telugu-dubbed" to "Telugu Dubbed"
    )

    // ---------- listing ----------

    // True if the element lives inside a sidebar / widget / nav area
    private fun Element.inSidebar(): Boolean =
        parents().any { p ->
            p.tagName() == "aside" ||
                p.tagName() == "nav" ||
                p.id().contains("sidebar", true) ||
                p.className().contains("sidebar", true) ||
                p.className().contains("widget", true)
        }

    private val movieHref = Regex("""-\d+\.html$""")

    private fun Document.parseCards(): List<SearchResponse> {
        val scope: Element = selectFirst("#main") ?: this
        val all = scope.select("a[href\$=\".html\"]").filter { !it.inSidebar() }
        var links = all.filter { it.selectFirst("img") != null }
        if (links.isEmpty()) links = all.filter { it.attr("title").isNotBlank() }
        if (links.isEmpty()) links = all.filter { movieHref.containsMatchIn(it.attr("href")) && it.text().isNotBlank() }
        return links.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("href"))
        val img = selectFirst("img")
        val title = attr("title").ifBlank { img?.attr("alt").orEmpty() }
            .ifBlank { text() }.trim()
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        )
        if (title.isBlank() || href.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url, referer = mainUrl, headers = mapOf("User-Agent" to userAgent)).document
        val home = doc.parseCards()
        Log.d(tag, "list url=$url cards=${home.size}")
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty() && page < 25)
    }

    // ---------- fuzzy search ----------

    private val stopWords = setOf(
        "movie", "movies", "film", "full", "hd", "telugu", "watch", "online", "free", "download"
    )

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun normQuery(s: String): String {
        var t = norm(s).replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
        t = t.split(' ').filter { it.isNotBlank() && it !in stopWords }.joinToString(" ")
        return t.ifBlank { norm(s) }
    }

    // "Mahasakthi (2026) DVDScr [Telugu]" -> "mahasakthi"
    private fun coreTitle(s: String): String =
        norm(s.replace(Regex("\\(.*?\\)|\\[.*?]"), " "))

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    // 0.0 .. 1.0 ; tolerant to spelling mistakes (Mahasakti ~ Mahasakthi)
    private fun score(title: String, query: String): Double {
        val q = normQuery(query)
        if (q.isEmpty()) return 1.0
        val t = coreTitle(title)
        if (t.isEmpty()) return 0.0
        if (t.contains(q)) return 1.0
        val qc = q.replace(" ", "")
        val tc = t.replace(" ", "")
        val tt = t.split(' ').filter { it.isNotEmpty() }
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val compact = sim(qc, tc)
        val prefix = sim(qc, tc.take(qc.length)) * 0.95
        val tokenAvg = qt.map { w ->
            tt.maxOfOrNull { x ->
                maxOf(
                    sim(w, x),
                    if (w.length >= 4 && x.startsWith(w.take(4))) 0.75 else 0.0
                )
            } ?: 0.0
        }.average()
        return maxOf(compact, prefix, tokenAvg)
    }

    private fun queryVariants(q: String): List<String> {
        val base = q.trim()
        val out = linkedSetOf(base)
        val n = norm(base)
        if (n.isNotEmpty()) out.add(n)
        val longest = n.split(' ').filter { it.length >= 3 }.maxByOrNull { it.length }
        if (longest != null) {
            out.add(longest)
            if (longest.length > 5) out.add(longest.take(longest.length - 2))
            if (longest.length >= 4) out.add(longest.take(4))
        }
        return out.filter { it.isNotBlank() }.take(4)
    }

    @Volatile private var indexCache: List<SearchResponse> = emptyList()
    @Volatile private var indexTime = 0L

    // A small local list of recent movies, used to catch badly spelled searches
    private suspend fun siteIndex(): List<SearchResponse> {
        if (indexCache.isNotEmpty() && System.currentTimeMillis() - indexTime < 10 * 60 * 1000L) {
            return indexCache
        }
        val urls = listOf(
            mainUrl,
            "$mainUrl/category/telugu-featured",
            "$mainUrl/category/telugu-featured/page/2",
            "$mainUrl/category/telugu-movies-2026",
            "$mainUrl/category/telugu-movies-2026/page/2",
            "$mainUrl/category/telugu-movies-2025",
            "$mainUrl/language/telugu-dubbed",
            "$mainUrl/category/telugu-movies-2024"
        )
        val pages: List<List<SearchResponse>> = urls.amap { u ->
            try {
                app.get(u, referer = mainUrl, headers = mapOf("User-Agent" to userAgent), timeout = 20L)
                    .document.parseCards()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }
        val all = pages.flatten().distinctBy { it.url }
        if (all.isNotEmpty()) {
            indexCache = all
            indexTime = System.currentTimeMillis()
        }
        Log.d(tag, "index size=${all.size}")
        return all
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variants = queryVariants(query)
        val urls = mutableListOf<String>()
        variants.forEachIndexed { i, v ->
            val q = URLEncoder.encode(v, "UTF-8")
            urls.add("$mainUrl/search_movies?s=$q")
            if (i == 0) urls.add("$mainUrl/?s=$q")
        }

        // results of the ORIGINAL query get a small trust bonus
        val trusted = HashSet<String>()
        val pool = LinkedHashMap<String, SearchResponse>()
        val pages: List<Pair<String, List<SearchResponse>>> = urls.amap { u ->
            try {
                val r = app.get(u, referer = mainUrl, headers = mapOf("User-Agent" to userAgent), timeout = 20L)
                u to r.document.parseCards()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                u to emptyList<SearchResponse>()
            }
        }
        val originalEnc = URLEncoder.encode(variants.first(), "UTF-8")
        for ((u, list) in pages) {
            Log.d(tag, "search url=$u results=${list.size}")
            for (r in list) {
                pool.putIfAbsent(r.url, r)
                if (u.endsWith("=$originalEnc")) trusted.add(r.url)
            }
        }

        fun rank(items: Collection<SearchResponse>): List<Pair<SearchResponse, Double>> =
            items.map { r ->
                val s = score(r.name, query)
                r to (if (r.url in trusted) minOf(1.0, s + 0.15) else s)
            }.sortedByDescending { it.second }

        var ranked = rank(pool.values)
        var good = ranked.filter { it.second >= 0.5 }

        // Few good matches -> also look through the site's recent lists (handles typos)
        if (good.size < 5) {
            siteIndex().forEach { pool.putIfAbsent(it.url, it) }
            ranked = rank(pool.values)
            good = ranked.filter { it.second >= 0.5 }
        }

        val out = if (good.isNotEmpty()) good.take(40) else ranked.take(12)
        Log.d(tag, "search '$query' pool=${pool.size} good=${good.size} out=${out.size}")
        return out.map { it.first }
    }

    // ---------- details ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl, headers = mapOf("User-Agent" to userAgent)).document

        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h2.entry-title")?.text()
            ?: doc.selectFirst("h2")?.text() ?: "Movie")
            .substringBefore(" | MovieRulz").trim()
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".entry-content img")?.attr("src")
        )
        val plot = doc.selectFirst(".synopsis-section p")?.text()
            ?: doc.selectFirst("h4:containsOwn(Synopsis)")?.nextElementSibling()?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val genres = doc.select(".entry-content a[href*=/genre/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        Log.d(tag, "load url=$url title=$title")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            if (genres.isNotEmpty()) this.tags = genres
        }
    }

    // ---------- link extraction ----------

    private val maxLinks = 40                 // Cap on hosts tried per movie
    private val maxUnknownHosts = 8           // Unknown (non-button) hosts are limited
    private val hostTimeoutMs = 25_000L       // Soft limit per host
    private val cacheMs = 5 * 60 * 1000L      // Reuse found links for 5 minutes

    private class CachedLinks(
        val time: Long,
        val links: List<ExtractorLink>,
        val subs: List<SubtitleFile>
    )

    private val linkCache = ConcurrentHashMap<String, CachedLinks>()

    private val whitespace = Regex("\\s+")
    private val urlRegex = Regex("""https?://[^\s"'<>\\)]+""")
    private val base64Chars = Regex("^[A-Za-z0-9+/=_-]+$")
    private val base64InScript = Regex("""["']([A-Za-z0-9+/=_-]{24,})["']""")

    private val skipExt = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        ".css", ".js", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".torrent"
    )

    // Matched against the HOST only
    private val skipHost = listOf(
        "googleapis", "google-analytics", "googletagmanager", "gstatic",
        "facebook", "twitter", "w3.org", "schema.org", "wordpress.org",
        "cdnjs.cloudflare.com", "whatsapp", "t.me", "telegram", "instagram",
        "cloudflareinsights", "acscdn.com", "doubleclick", "googlesyndication",
        "histats.com", "juicyads", "exoclick", "popads", "propellerads", "imdb.com"
    )

    private val knownHosts = listOf(
        "hglink", "streamhg", "cavanhabg", "dumbalag", "streamwish", "hlswish", "swish",
        "minochinos", "filelions", "vidhide", "streamvin", "dood", "d000", "ds2play",
        "lulu", "mixdrop", "streamtape", "voe", "upstream", "vtube", "filemoon", "vidmoly",
        "streamlare", "slmaxed", "vcdnlare", "uperbox", "easysyncr"
    )

    // Media URLs containing these words are probably ads / previews. They are NOT dropped,
    // only tagged "(unverified)" so you can still choose them in the sources list.
    private val badMedia = listOf(
        "/ads/", "/ad/", "advert", "banner", "promo", "preview", "trailer", "teaser", "sample"
    )

    private val embedHint =
        Regex("ajax|embed|player|server|stream|api|source|watch|episode|iframe", RegexOption.IGNORE_CASE)

    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val sourceTagRegex =
        Regex("""<(?:source|video)[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val jwFileRegex =
        Regex("""["']?(?:file|src|source|url)["']?\s*:\s*["'](https?://[^"'\s<>]+)["']""", RegexOption.IGNORE_CASE)

    private fun collectUrls(text: String, out: MutableSet<String>) {
        val clean = text.replace("\\/", "/").replace("&amp;", "&")
        urlRegex.findAll(clean).forEach {
            out.add(it.value.trimEnd(',', ';', '\\', '.', ')', ']', '"', '\''))
        }
    }

    private fun tryBase64(s: String): String? {
        if (s.length < 16 || !s.matches(base64Chars)) return null
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            String(Base64.decode(fixed, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    // If a button link is a wrapper like /go?url=<encoded or base64 target>, pull the target out
    private fun unwrapParams(u: String, out: MutableSet<String>) {
        val q = u.substringAfter('?', "")
        if (q.isBlank()) return
        for (pair in q.split('&')) {
            val v = pair.substringAfter('=', "")
            if (v.length < 8) continue
            val dec = try {
                URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) {
                v
            }
            if (dec.startsWith("http")) {
                out.add(dec.replace(whitespace, ""))
            } else {
                tryBase64(dec)?.let { d ->
                    if (d.contains("http")) collectUrls(d, out)
                }
            }
        }
    }

    private fun absolute(raw: String, origin: String): String? {
        val r = raw.replace(whitespace, "")
        return when {
            r.startsWith("http") -> r
            r.startsWith("//") -> "https:$r"
            r.startsWith("/") -> origin + r
            else -> null
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("//").substringBefore("/").substringBefore("?").lowercase()

    private fun isUseful(u: String): Boolean {
        if (u.startsWith("magnet")) return false
        val path = u.substringBefore('?').lowercase()
        if (skipExt.any { path.endsWith(it) }) return false
        val h = hostOf(u)
        if (skipHost.any { h.contains(it) }) return false
        return true
    }

    private fun isKnownHost(u: String): Boolean {
        val h = hostOf(u)
        return knownHosts.any { h.contains(it) }
    }

    private fun originOf(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${hostOf(url)}"
        }
    }

    // Other addresses of the same video (same host family only)
    private fun alternates(link: String): List<String> {
        val clean = link.substringBefore('#').substringBefore('?').trimEnd('/')
        val id = clean.substringAfterLast('/')
            .removePrefix("embed-").removeSuffix(".html")
        if (id.isBlank()) return emptyList()
        val h = hostOf(link)
        val origin = originOf(link)

        val list = mutableListOf<String>()
        when {
            h.contains("hglink") || h.contains("streamhg") -> list.addAll(
                listOf(
                    "https://hglink.to/e/$id",
                    "https://cavanhabg.com/e/$id",
                    "https://dumbalag.com/e/$id",
                    "https://streamwish.to/e/$id",
                    "https://hlswish.com/e/$id"
                )
            )
            h.contains("minochinos") || h.contains("lions") || h.contains("vidhide") -> list.addAll(
                listOf(
                    "https://minochinos.com/e/$id",
                    "https://filelions.to/v/$id",
                    "https://vidhide.com/v/$id",
                    "https://vidhidepro.com/v/$id"
                )
            )
            h.contains("streamvin") -> list.addAll(
                listOf(
                    "https://streamvin.com/e/$id",
                    "https://streamvin.com/embed-$id.html"
                )
            )
            h.contains("dood") || h.contains("d000") || h.contains("ds2play") -> list.addAll(
                listOf(
                    "https://dood.to/e/$id",
                    "https://d000d.com/e/$id"
                )
            )
            h.contains("lulu") -> list.addAll(
                listOf(
                    "https://lulustream.com/e/$id",
                    "https://luluvdo.com/e/$id"
                )
            )
            h.contains("streamlare") || h.contains("slmaxed") || h.contains("vcdnlare") -> list.addAll(
                listOf(
                    "https://streamlare.com/e/$id",
                    "https://slmaxed.com/e/$id",
                    "https://streamlare.com/v/$id"
                )
            )
        }
        list.addAll(listOf("$origin/e/$id", "$origin/v/$id", "$origin/embed-$id.html"))
        return list.distinct().filter { it != link }
    }

    // ---------- p.a.c.k.e.r unpacker (radix 2..62, ALL blocks on the page) ----------

    private val packedRegex =
        Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val wordRegex = Regex("""\b\w+\b""")

    private fun decodeBase(token: String, radix: Int): Int? {
        var value = 0L
        for (ch in token) {
            val c = if (radix <= 36) ch.lowercaseChar() else ch
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 36
                else -> return null
            }
            if (d >= radix) return null
            value = value * radix + d
            if (value > Int.MAX_VALUE) return null
        }
        return value.toInt()
    }

    private fun unpackAllJs(text: String): String {
        val sb = StringBuilder()
        for (m in packedRegex.findAll(text)) {
            val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val radix = m.groupValues[2].toIntOrNull() ?: continue
            if (radix < 2 || radix > 62) continue
            val words = m.groupValues[4].split("|")
            val unpacked = wordRegex.replace(payload) { r ->
                val idx = decodeBase(r.value, radix)
                val w = if (idx != null && idx < words.size) words[idx] else ""
                if (w.isEmpty()) r.value else w
            }
            sb.append(unpacked).append("\n")
        }
        return sb.toString()
    }

    private val iframeRegex =
        Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val windowLocRegex =
        Regex("""(?:window\.location(?:\.href|\.replace)?|location\.href)\s*[=(]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)

    // Extracts .m3u8 / .mp4 streams with proper Origin + Referer headers (prevents 403).
    // Everything found is delivered; doubtful ones are tagged "(unverified)".
    private suspend fun scanMedia(
        text: String,
        pageUrl: String,
        label: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val candidates = LinkedHashMap<String, Boolean>()   // url -> trusted

        mediaRegex.findAll(text).forEach { m ->
            val u = m.value
            candidates.putIfAbsent(u, u.contains(".m3u8", true))
        }
        sourceTagRegex.findAll(text).forEach { m ->
            val u = m.groupValues[1]
            if (u.startsWith("http")) candidates[u] = true
            else if (u.startsWith("//")) candidates["https:$u"] = true
        }
        jwFileRegex.findAll(text).forEach { m ->
            val u = m.groupValues[1]
            if (u.contains(".m3u8", true) || u.contains(".mp4", true) ||
                u.contains("master.txt", true) || u.contains("/hls/", true)
            ) {
                candidates[u] = true
            }
        }

        val hostLabel = hostOf(pageUrl).removePrefix("www.")
        val shownName = label ?: hostLabel
        val origin = originOf(pageUrl)
        val refererRoot = "$origin/"

        if (candidates.isNotEmpty()) {
            Log.d(tag, "media candidates from $pageUrl : ${candidates.keys.take(3)}")
        }

        for ((rawUrl, trusted) in candidates) {
            val m = rawUrl.trimEnd('\\', '"', '\'')
            if (!isUseful(m)) continue
            val isM3u8 = m.contains(".m3u8", true) || m.contains("master.txt", true) || m.contains("/hls/", true)
            val doubtful = !trusted || badMedia.any { m.contains(it, true) }
            val suffix = if (doubtful) " (unverified)" else ""
            callback(
                newExtractorLink(name, "$name [$shownName]$suffix", m) {
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = refererRoot
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to refererRoot,
                        "Origin" to origin,
                        "Accept" to "*/*"
                    )
                }
            )
            found = true
        }
        return found
    }

    // Deep custom fallback: fetch page, unpack JS, follow JS redirects and up to 2 levels of iframes
    private suspend fun fallbackExtract(
        link: String,
        referer: String,
        label: String?,
        depth: Int = 0,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 2) return false
        var ok = false
        try {
            val res = app.get(
                link,
                referer = referer,
                headers = mapOf("User-Agent" to userAgent),
                timeout = 15L
            )
            val effectiveUrl = res.url.ifBlank { link }
            val text = res.text.replace("\\/", "/").replace("&amp;", "&")
            val unpacked = unpackAllJs(text)
            val combined = "$text\n$unpacked"

            if (scanMedia(combined, effectiveUrl, label, callback)) {
                ok = true
            }

            if (!ok) {
                val childUrls = linkedSetOf<String>()
                iframeRegex.findAll(combined).forEach { childUrls.add(it.groupValues[1]) }
                windowLocRegex.findAll(combined).forEach { childUrls.add(it.groupValues[1]) }

                for (rawChild in childUrls.take(4)) {
                    val src = when {
                        rawChild.startsWith("//") -> "https:$rawChild"
                        rawChild.startsWith("http") -> rawChild
                        rawChild.startsWith("/") -> "${originOf(effectiveUrl)}$rawChild"
                        else -> continue
                    }
                    if (!isUseful(src) || src == effectiveUrl) continue
                    if (fallbackExtract(src, effectiveUrl, label, depth + 1, callback)) {
                        ok = true
                    }
                }
            }

            if (!ok && depth == 0) {
                Log.d(tag, "no media in $link : " + text.take(220).replace(whitespace, " "))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "fallback failed for $link : ${e.javaClass.simpleName}")
        }
        return ok
    }

    // Evaluates ONE host independently (all methods, nothing skipped)
    private suspend fun extractOne(
        link: String,
        referer: String,
        label: String?,
        deep: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val count = AtomicInteger(0)
        val counted: (ExtractorLink) -> Unit = { l ->
            count.incrementAndGet()
            callback(l)
        }
        val deadline = System.currentTimeMillis() + hostTimeoutMs
        fun timeUp(): Boolean = System.currentTimeMillis() > deadline
        val alts = if (deep) alternates(link).take(6) else emptyList()

        return try {
            // 1) built-in extractor on the raw URL
            loadExtractor(link, referer, subtitleCallback, counted)

            // 2) built-in extractors on alternate domains / embed paths
            if (deep && count.get() == 0) {
                for (alt in alts) {
                    if (timeUp()) break
                    loadExtractor(alt, referer, subtitleCallback, counted)
                    if (count.get() > 0) break
                }
            }

            // 3) custom fallback on the raw URL
            if (count.get() == 0 && !timeUp()) {
                fallbackExtract(link, referer, label, 0, counted)
            }

            // 4) custom fallback on alternates
            if (deep && count.get() == 0) {
                for (alt in alts) {
                    if (timeUp()) break
                    if (fallbackExtract(alt, referer, label, 0, counted)) break
                }
            }

            // 5) last chance: built-in extractor with the site as referer
            if (count.get() == 0 && !timeUp()) {
                loadExtractor(link, mainUrl, subtitleCallback, counted)
            }

            Log.d(tag, "host=${hostOf(link)} label=$label deep=$deep links=${count.get()}")
            count.get() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "extract failed for $link : ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cached = linkCache[data]
        if (cached != null && System.currentTimeMillis() - cached.time < cacheMs && cached.links.isNotEmpty()) {
            cached.subs.forEach { subtitleCallback(it) }
            cached.links.forEach { callback(it) }
            Log.d(tag, "served ${cached.links.size} links from memory")
            return true
        }

        val gotLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val gotSubs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val seenUrls = ConcurrentHashMap.newKeySet<String>()

        val cb: (ExtractorLink) -> Unit = { l ->
            if (seenUrls.add(l.url)) {
                gotLinks.add(l)
                callback(l)
            }
        }
        val scb: (SubtitleFile) -> Unit = { s ->
            gotSubs.add(s)
            subtitleCallback(s)
        }

        // Open the movie page, up to 3 tries
        var res: NiceResponse? = null
        for (i in 1..3) {
            try {
                val r = app.get(
                    data,
                    referer = mainUrl,
                    headers = mapOf("User-Agent" to userAgent),
                    timeout = 30L
                )
                res = r
                if (r.code == 200 &&
                    (r.document.select("a.stream-link-btn").isNotEmpty() || r.text.contains("var locations"))
                ) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "page try$i failed: ${e.javaClass.simpleName}")
            }
        }

        val page = res ?: return false
        if (page.code != 200) {
            Log.w(tag, "page status=${page.code} for $data (blocked or moved?)")
            return false
        }
        val doc = page.document
        val pageUrl = page.url.ifBlank { data }

        Log.d(tag, "loadLinks requested=$data opened=$pageUrl title=${doc.title()}")
        if (doc.selectFirst(".no-links-fallback") != null) {
            Log.w(tag, "page itself says: no streaming links available for this title")
        }

        val siteHosts = setOf(
            hostOf(mainUrl).removePrefix("www."),
            hostOf(pageUrl).removePrefix("www.")
        )
        fun isInternal(u: String): Boolean = hostOf(u).removePrefix("www.") in siteHosts
        val origin = originOf(pageUrl)

        // Movie post area only: sidebars / widgets / footer / nav removed
        val base: Element = doc.selectFirst(".entry-content") ?: doc.selectFirst("#main") ?: doc
        val contentScope: Element = base.clone()
        contentScope.select(
            "aside, nav, footer, #sidebar, .sidebar, [id*=sidebar], [class*=sidebar], .widget, #secondary"
        ).remove()

        val priorityButtons = linkedSetOf<String>()
        val rawFound = linkedSetOf<String>()
        val labels = ConcurrentHashMap<String, String>()   // url -> button name

        // 0a) Named server buttons
        doc.select("a.stream-link-btn").filter { !it.inSidebar() }.forEach { el ->
            val u = absolute(el.attr("href"), origin) ?: return@forEach
            val lbl = el.text().trim()
            priorityButtons.add(u)
            if (lbl.isNotBlank()) labels[u] = lbl

            val unwrapped = linkedSetOf<String>()
            unwrapParams(u, unwrapped)
            unwrapped.forEach { t ->
                rawFound.add(t)
                if (lbl.isNotBlank()) labels[t] = lbl
            }
        }

        // 0c) The page's own player list: var locations = [...]  -> Player 1, Player 2 ...
        val locationsRegex = Regex("""var\s+locations\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        doc.select("script").forEach { s ->
            val m = locationsRegex.find(s.data()) ?: return@forEach
            val inOrder = linkedSetOf<String>()
            collectUrls(m.groupValues[1], inOrder)
            inOrder.forEachIndexed { idx, raw ->
                val u = raw.replace(whitespace, "")
                if (isUseful(u)) {
                    rawFound.add(u)
                    priorityButtons.add(u)
                    labels.putIfAbsent(u, "Player ${idx + 1}")
                }
            }
        }

        // 0b) Direct iframes / outbound links in the post content
        contentScope.select("iframe, a[target=_blank]").forEach { el ->
            val u = absolute(el.attr("href").ifBlank { el.attr("src") }, origin) ?: return@forEach
            priorityButtons.add(u)
        }

        // 1) Attributes inside the post content
        contentScope.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val v = attr.value.trim()
                when {
                    v.startsWith("http") -> rawFound.add(v)
                    v.startsWith("//") -> rawFound.add("https:$v")
                    v.contains("http") -> collectUrls(v, rawFound)
                    else -> tryBase64(v)?.let { d ->
                        if (d.contains("http")) collectUrls(d, rawFound)
                    }
                }
            }
        }

        // 2) Scripts inside the post content
        contentScope.select("script").forEach { s ->
            collectUrls(s.data(), rawFound)
            base64InScript.findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, rawFound)
                }
            }
        }

        // 2b) Scripts / iframes outside the post box: ONLY known video hosts are accepted
        doc.select("script").forEach { s ->
            val tmp = linkedSetOf<String>()
            collectUrls(s.data(), tmp)
            base64InScript.findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, tmp)
                }
            }
            tmp.filter { isKnownHost(it) }.forEach { rawFound.add(it) }
        }
        doc.select("iframe[src]").forEach { el ->
            val u = absolute(el.attr("src"), origin) ?: return@forEach
            if (isKnownHost(u) && !el.inSidebar()) rawFound.add(u)
        }

        // 3) Clean whitespace, drop prefix-truncated copies (labelled links are always kept)
        val cleaned = (priorityButtons + rawFound).map { it.replace(whitespace, "") }.toSet()
        val found = cleaned.filter { u ->
            labels.containsKey(u) || cleaned.none { o -> o != u && o.startsWith(u) }
        }

        Log.d(tag, "page=$pageUrl status=${page.code} found=${found.size} buttons=${labels.values.toList()}")

        val useful = found.filter { isUseful(it) }
        val internal = useful.filter { isInternal(it) }

        val external = linkedSetOf<String>()
        priorityButtons.filter { isUseful(it) && !isInternal(it) }.forEach { external.add(it) }
        useful.filter { !isInternal(it) }.forEach { external.add(it) }

        // 4) Follow same-site embed / redirect / ajax endpoints in parallel
        val followList = (
            internal.filter { embedHint.containsMatchIn(it) } +
                priorityButtons.filter { isInternal(it) }
            )
            .filter { !it.contains("/category/") && !it.endsWith(".html") && it != data }
            .distinct()
            .take(12)

        val extra: List<Pair<String, List<String>>> = followList
            .amap { u ->
                try {
                    val r = app.get(
                        u,
                        referer = data,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        timeout = 12L
                    )
                    val sub = linkedSetOf<String>()
                    collectUrls(r.text, sub)
                    val out = sub.map { it.replace(whitespace, "") }
                        .filter { isUseful(it) && !isInternal(it) }
                        .toMutableList()
                    val landed = r.url
                    if (landed.startsWith("http") && !isInternal(landed) && isUseful(landed)) {
                        out.add(landed)
                    }
                    u to out.toList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    u to emptyList<String>()
                }
            }
        extra.forEach { (from, list) ->
            val lbl = labels[from]
            list.forEach { t ->
                external.add(t)
                if (lbl != null && !labels.containsKey(t)) labels[t] = lbl
            }
        }

        // Order: labelled buttons / players first, then known video hosts, then a few unknown ones
        val buttonLinks = external.filter { labels.containsKey(it) }
        val known = external.filter { isKnownHost(it) && !labels.containsKey(it) }
        val unknown = external.filter { !isKnownHost(it) && !labels.containsKey(it) }.take(maxUnknownHosts)
        val ordered = (buttonLinks + known + unknown).distinct().take(maxLinks)

        Log.d(tag, "external=${external.size} buttons=${buttonLinks.size} known=${known.size} tried=${ordered.size} hosts=${ordered.map { hostOf(it) }.distinct()}")

        // 5) Try ALL hosts in parallel
        val results = ordered.amap { link ->
            val label = labels[link]
            val deep = isKnownHost(link) || label != null
            extractOne(link, data, label, deep, scb, cb)
        }
        val ok = results.any { it } || gotLinks.isNotEmpty()
        Log.d(tag, "final ok=$ok links=${gotLinks.size}")

        if (gotLinks.isNotEmpty()) {
            if (linkCache.size > 50) linkCache.clear()
            linkCache[data] = CachedLinks(System.currentTimeMillis(), gotLinks.toList(), gotSubs.toList())
        }
        return ok
    }
}
