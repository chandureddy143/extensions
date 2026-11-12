package com.shekar.telugu

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.InetAddress
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

    // Movie cards look like: <li><div class="boxed film"><a title=".." href="..html"><img></a>
    // We read only the main area (not the sidebar), and fall back to title-only links.
    private fun Document.parseCards(): List<SearchResponse> {
        val scope: Element = selectFirst("#main") ?: this
        var links = scope.select("a[href$=.html]").filter { it.selectFirst("img") != null }
        if (links.isEmpty()) {
            links = scope.select("a[href$=.html]").filter { it.attr("title").isNotBlank() }
        }
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
        val doc = app.get(url, referer = mainUrl).document
        val home = doc.parseCards()
        Log.d(tag, "list url=$url cards=${home.size}")
        // page < 20 stops endless paging if the site repeats the last page
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty() && page < 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val patterns = listOf("$mainUrl/search_movies?s=$q", "$mainUrl/?s=$q")
        for (u in patterns) {
            val results = try {
                app.get(u, referer = mainUrl).document.parseCards()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            Log.d(tag, "search url=$u results=${results.size}")
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // ---------- details ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document

        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h2")?.text() ?: "Movie")
            .substringBefore(" | MovieRulz").trim()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("h4:containsOwn(Synopsis)")?.nextElementSibling()?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val report = try {
            serverReport(doc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = listOfNotNull(plot, report.ifBlank { null }).joinToString("\n\n")
            this.year = year
        }
    }

    // Shown at the bottom of the movie description inside the app:
    // which video servers this page lists, and whether your phone can find them.
    // Set to true only when you want to see it (it makes the movie page open slower).
    private val showReport = false

    private suspend fun serverReport(doc: Document): String {
        if (!showReport) return ""
        val hosts = doc.select("a.stream-link-btn")
            .map { it.attr("href").replace(Regex("\\s+"), "") }
            .filter { it.startsWith("http") }
            .map { it.substringAfter("//").substringBefore("/") }
            .distinct()
            .take(6)
        if (hosts.isEmpty()) {
            return "[Debug] No server buttons found on this page. The site layout may have changed."
        }
        val lines = hosts.amap { h ->
            val ok = try {
                InetAddress.getByName(h)
                true
            } catch (e: Exception) {
                false
            }
            if (ok) "OK: $h" else "NOT FOUND (blocked or dead): $h"
        }
        return "[Debug] Video servers:\n" + lines.joinToString("\n")
    }

    // ---------- link extraction ----------

    // Settings you can tweak
    private val maxLinks = 25                // max hosts tried per movie
    private val cacheMs = 5 * 60 * 1000L     // reuse found links for 5 minutes

    // Remembers the links found for a movie, so opening it again is instant
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
        ".css", ".js", ".ico", ".woff", ".woff2", ".ttf"
    )
    private val skipHost = listOf(
        "googleapis", "google-analytics", "googletagmanager", "gstatic",
        "facebook", "twitter", "w3.org", "schema.org",
        "cdnjs.cloudflare.com", "whatsapp", "//t.me", "telegram", "instagram",
        "cloudflareinsights", "acscdn.com"
    )
    private val embedHint =
        Regex("ajax|embed|player|server|stream|api|source|watch", RegexOption.IGNORE_CASE)

    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(m3u8|mp4)[^\s"'<>\\]*""")

    private fun collectUrls(text: String, out: MutableSet<String>) {
        val clean = text.replace("\\/", "/").replace("&amp;", "&")
        urlRegex.findAll(clean).forEach { out.add(it.value.trimEnd(',', ';', '\\', '.')) }
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

    private fun isUseful(u: String): Boolean {
        if (u.startsWith("magnet")) return false
        val path = u.substringBefore('?').lowercase()
        if (skipExt.any { path.endsWith(it) }) return false
        if (skipHost.any { u.contains(it, true) }) return false
        return true
    }

    // Other addresses of the same video, for hosts Cloudstream may not know
    private fun alternates(link: String): List<String> {
        val id = link.trimEnd('/').substringAfterLast('/').substringBefore('?')
        return when {
            link.contains("hglink.to") -> listOf(
                "https://streamwish.to/e/$id",
                "https://hlswish.com/e/$id"
            )
            link.contains("minochinos") -> listOf(
                "https://filelions.to/v/$id",
                "https://vidhide.com/v/$id"
            )
            else -> emptyList()
        }
    }

    // Many video hosts hide the video address inside "packed" JavaScript:
    // eval(function(p,a,c,k,e,d){...}('payload',base,count,'w1|w2|..'.split('|'),0,{}))
    // This opens it up so the address becomes readable.
    private val packedRegex =
        Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val wordRegex = Regex("""\b\w+\b""")

    private fun unpackJs(text: String): String? {
        val m = packedRegex.find(text) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        if (radix < 2 || radix > 36) return null
        val words = m.groupValues[4].split("|")
        return wordRegex.replace(payload) { r ->
            val idx = r.value.toIntOrNull(radix)
            val w = if (idx != null && idx < words.size) words[idx] else ""
            if (w.isEmpty()) r.value else w
        }
    }

    private val iframeRegex =
        Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // Looks for .m3u8 / .mp4 addresses inside a page's text and hands them to Cloudstream
    private suspend fun scanMedia(
        text: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val items = mediaRegex.findAll(text).map { it.value }.distinct().toList()
        for (m in items) {
            val isM3u8 = m.contains(".m3u8")
            callback(
                newExtractorLink(name, name, m) {
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    // Used when Cloudstream has no built-in extractor for a video host
    private suspend fun fallbackExtract(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        try {
            val text = app.get(link, referer = referer, timeout = 10L).text.replace("\\/", "/")
            ok = scanMedia(text + "\n" + (unpackJs(text) ?: ""), link, callback)

            // Many hosts wrap the real player in an iframe: look one level deeper
            if (!ok) {
                val frames = iframeRegex.findAll(text).map { it.groupValues[1] }.distinct().take(3).toList()
                for (f in frames) {
                    val src = when {
                        f.startsWith("//") -> "https:$f"
                        f.startsWith("http") -> f
                        else -> continue
                    }
                    try {
                        val t2 = app.get(src, referer = link, timeout = 10L).text.replace("\\/", "/")
                        if (scanMedia(t2 + "\n" + (unpackJs(t2) ?: ""), src, callback)) ok = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(tag, "iframe failed for $src : ${e.javaClass.simpleName}")
                    }
                }
            }

            if (!ok) {
                Log.d(tag, "no media in $link : " + text.take(300).replace("\n", " "))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "fallback failed for $link : ${e.javaClass.simpleName}")
        }
        return ok
    }

    // Try ONE host: built-in extractor, then fallback, then alternate addresses.
    // Errors on one dead host are caught so they cannot stop the others.
    private suspend fun extractOne(
        link: String,
        referer: String,
        total: AtomicInteger,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Count REAL video links. loadExtractor can say "true" for a known host even when
        // it gave us no video, so we only trust the number of links actually delivered.
        // "total" counts links from ALL hosts: once any host has delivered, the slower
        // backup methods are skipped so the movie starts sooner.
        val count = AtomicInteger(0)
        val counted: (ExtractorLink) -> Unit = { l ->
            count.incrementAndGet()
            total.incrementAndGet()
            callback(l)
        }
        return try {
            loadExtractor(link, referer, subtitleCallback, counted)
            if (count.get() == 0 && total.get() == 0) fallbackExtract(link, referer, counted)
            if (count.get() == 0 && total.get() == 0) {
                for (alt in alternates(link)) {
                    loadExtractor(alt, referer, subtitleCallback, counted)
                    if (count.get() > 0 || total.get() > 0) break
                }
            }
            val host = link.substringAfter("//").substringBefore("/")
            Log.d(tag, "host=$host links=${count.get()}")
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
        // Opened this movie in the last few minutes? Reuse the links, no waiting.
        val cached = linkCache[data]
        if (cached != null && System.currentTimeMillis() - cached.time < cacheMs) {
            cached.subs.forEach { subtitleCallback(it) }
            cached.links.forEach { callback(it) }
            Log.d(tag, "served ${cached.links.size} links from memory")
            return true
        }

        // Remember everything we deliver, so it can be reused next time
        val gotLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val gotSubs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val cb: (ExtractorLink) -> Unit = { l ->
            gotLinks.add(l)
            callback(l)
        }
        val scb: (SubtitleFile) -> Unit = { s ->
            gotSubs.add(s)
            subtitleCallback(s)
        }

        // Open the movie page, up to 3 tries
        var res: NiceResponse? = null
        for (i in 1..3) {
            try {
                val r = app.get(data, referer = mainUrl, timeout = 30L)
                res = r
                if (r.code == 200 && r.text.contains("stream-link-btn")) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "page try$i failed: ${e.javaClass.simpleName}")
            }
        }

        val page = res ?: return false
        val doc = page.document
        val rawFound = linkedSetOf<String>()

        // 0) the site's own server buttons (their links contain a line break inside)
        doc.select("a.stream-link-btn").forEach { a ->
            val h = a.attr("href").replace(whitespace, "")
            if (h.startsWith("http")) rawFound.add(h)
        }

        // 1) every attribute of every element
        doc.allElements.forEach { el ->
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

        // 2) raw HTML + inline scripts
        collectUrls(page.text, rawFound)
        doc.select("script").forEach { s ->
            collectUrls(s.data(), rawFound)
            base64InScript.findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, rawFound)
                }
            }
        }

        // 3) remove line breaks / spaces inside links, then drop cut-off copies
        val cleaned = rawFound.map { it.replace(whitespace, "") }.toSet()
        val found = cleaned.filter { u ->
            cleaned.none { o -> o != u && o.startsWith(u) }
        }

        Log.d(tag, "page=${page.url} status=${page.code} found=${found.size}")

        val useful = found.filter { isUseful(it) }
        val internal = useful.filter { it.startsWith(mainUrl) }
        val external = useful.filter { !it.startsWith(mainUrl) }.toMutableSet()

        // 4) follow same-site embed / ajax endpoints once
        val extra: List<List<String>> = internal
            .filter { embedHint.containsMatchIn(it) && !it.contains("/category/") && !it.endsWith(".html") }
            .take(8)
            .amap { u ->
                try {
                    val r = app.get(
                        u,
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = 10L
                    )
                    val sub = linkedSetOf<String>()
                    collectUrls(r.text, sub)
                    sub.map { it.replace(whitespace, "") }
                        .filter { isUseful(it) && !it.startsWith(mainUrl) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList<String>()
                }
            }
        extra.forEach { list -> list.forEach { external.add(it) } }

        val hosts = external.map { it.substringAfter("//").substringBefore("/") }.distinct()
        Log.d(tag, "external=${external.size} hosts=$hosts")

        // 5) try all hosts AT THE SAME TIME (amap = run in parallel)
        val total = AtomicInteger(0)
        val results = external.take(maxLinks).toList().amap { link ->
            extractOne(link, data, total, scb, cb)
        }
        val ok = results.any { it }
        Log.d(tag, "final ok=$ok links=${gotLinks.size}")

        if (ok && gotLinks.isNotEmpty()) {
            if (linkCache.size > 50) linkCache.clear()
            linkCache[data] = CachedLinks(System.currentTimeMillis(), gotLinks.toList(), gotSubs.toList())
        }
        return ok
    }
}
