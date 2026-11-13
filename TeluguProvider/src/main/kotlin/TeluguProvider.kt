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
import java.net.URI
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
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty() && page < 25)
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

    private val showReport = false

    private suspend fun serverReport(doc: Document): String {
        if (!showReport) return ""
        val hosts = doc.select("a.stream-link-btn")
            .map { it.attr("href").replace(Regex("\\s+"), "") }
            .filter { it.startsWith("http") }
            .map { it.substringAfter("//").substringBefore("/") }
            .distinct()
            .take(8)
        if (hosts.isEmpty()) {
            return "[Debug] No server buttons found on this page."
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

    private val maxLinks = 40                // High cap so no valid host is ever dropped
    private val cacheMs = 5 * 60 * 1000L     // Reuse found links for 5 minutes

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
    private val skipHost = listOf(
        "googleapis", "google-analytics", "googletagmanager", "gstatic",
        "facebook", "twitter", "w3.org", "schema.org", "wordpress.org",
        "cdnjs.cloudflare.com", "whatsapp", "//t.me", "telegram", "instagram",
        "cloudflareinsights", "acscdn.com", "doubleclick", "googlesyndication",
        "histats.com", "juicyads", "exoclick", "popads", "propellerads", "imdb.com"
    )
    private val embedHint =
        Regex("ajax|embed|player|server|stream|api|source|watch|episode|iframe", RegexOption.IGNORE_CASE)

    // Matches direct .m3u8 / .mp4 URLs as well as JWPlayer/VideoJS file:"..." definitions
    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
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

    private fun isUseful(u: String): Boolean {
        if (u.startsWith("magnet")) return false
        val path = u.substringBefore('?').lowercase()
        if (skipExt.any { path.endsWith(it) }) return false
        if (skipHost.any { u.contains(it, true) }) return false
        return true
    }

    private fun hostOf(url: String): String =
        url.substringAfter("//").substringBefore("/").lowercase()

    private fun originOf(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://${hostOf(url)}"
        }
    }

    // Converts landing URLs into /e/ embed URLs and known mirror domains so all servers resolve
    private fun alternates(link: String): List<String> {
        val clean = link.trimEnd('/')
        val id = clean.substringAfterLast('/').substringBefore('?')
            .removePrefix("e/").removePrefix("v/").removePrefix("d/").removePrefix("f/")
            .removePrefix("embed-").removeSuffix(".html")
        if (id.isBlank()) return emptyList()
        val h = hostOf(link)
        val origin = originOf(link)

        val list = mutableListOf(
            "$origin/e/$id",
            "$origin/v/$id",
            "$origin/embed-$id.html"
        )
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
                    "https://vidhidepro.com/v/$id",
                    "https://streamwish.to/e/$id"
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
        }
        return list.distinct().filter { it != link }
    }

    // Unpacks ALL eval(function(p,a,c,k,e,d)...) blocks on a page (not just the first one)
    private val packedRegex =
        Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val wordRegex = Regex("""\b\w+\b""")

    private fun unpackAllJs(text: String): String {
        val sb = StringBuilder()
        for (m in packedRegex.findAll(text)) {
            val payload = m.groupValues[1].replace("\\'", "'")
            val radix = m.groupValues[2].toIntOrNull() ?: continue
            if (radix < 2 || radix > 36) continue
            val words = m.groupValues[4].split("|")
            val unpacked = wordRegex.replace(payload) { r ->
                val idx = r.value.toIntOrNull(radix)
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

    // Extracts .m3u8 / .mp4 streams and attaches proper Origin + Referer headers to prevent 403 Forbidden
    private suspend fun scanMedia(
        text: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val candidates = linkedSetOf<String>()

        mediaRegex.findAll(text).forEach { candidates.add(it.value) }
        jwFileRegex.findAll(text).forEach { m ->
            val u = m.groupValues[1]
            if (u.contains(".m3u8", true) || u.contains(".mp4", true) || u.contains("master.txt", true) || u.contains("/hls/", true)) {
                candidates.add(u)
            }
        }

        val hostLabel = hostOf(pageUrl).removePrefix("www.")
        val origin = originOf(pageUrl)
        val refererRoot = "$origin/"

        for (rawUrl in candidates) {
            val m = rawUrl.trimEnd('\\', '"', '\'')
            if (!isUseful(m)) continue
            val isM3u8 = m.contains(".m3u8", true) || m.contains("master.txt", true) || m.contains("/hls/", true)
            callback(
                newExtractorLink(name, "$name [$hostLabel]", m) {
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

    // Deep custom fallback: fetches page, unpacks all JS, follows JS redirects & up to 2 levels of iframes
    private suspend fun fallbackExtract(
        link: String,
        referer: String,
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
            val text = res.text.replace("\\/", "/")
            val unpacked = unpackAllJs(text)
            val combined = "$text\n$unpacked"

            if (scanMedia(combined, effectiveUrl, callback)) {
                ok = true
            }

            // Follow nested iframes or JS location redirects if no media was directly on this layer
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
                    if (fallbackExtract(src, effectiveUrl, depth + 1, callback)) {
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

    // Evaluates ONE host completely and independently (no global cutoff)
    private suspend fun extractOne(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val count = AtomicInteger(0)
        val counted: (ExtractorLink) -> Unit = { l ->
            count.incrementAndGet()
            callback(l)
        }
        return try {
            // Step 1: Try CloudStream's built-in extractor on the raw URL
            loadExtractor(link, referer, subtitleCallback, counted)

            // Step 2: Try alternate canonical domains & /e/ embed paths on built-in extractors
            if (count.get() == 0) {
                for (alt in alternates(link)) {
                    loadExtractor(alt, referer, subtitleCallback, counted)
                    if (count.get() > 0) break
                }
            }

            // Step 3: Deep custom fallback on the raw URL
            if (count.get() == 0) {
                fallbackExtract(link, referer, 0, counted)
            }

            // Step 4: Deep custom fallback on /e/ embed paths and alternate domains
            if (count.get() == 0) {
                for (alt in alternates(link)) {
                    if (fallbackExtract(alt, referer, 0, counted)) break
                }
            }

            // Step 5: Last chance built-in extractor with mainUrl as referer
            if (count.get() == 0) {
                loadExtractor(link, mainUrl, subtitleCallback, counted)
            }

            Log.d(tag, "host=${hostOf(link)} links=${count.get()}")
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
                if (r.code == 200 && (r.text.contains("stream-link-btn") || r.text.contains("iframe"))) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "page try$i failed: ${e.javaClass.simpleName}")
            }
        }

        val page = res ?: return false
        val doc = page.document

        // Scope strictly to the movie post area so sidebar/related movies are never picked up
        val contentScope: Element =
            doc.selectFirst(".entry-content") ?: doc.selectFirst("#main") ?: doc

        val priorityButtons = linkedSetOf<String>()
        val rawFound = linkedSetOf<String>()

        // 0) Explicit stream-link-btn links + direct iframes inside the movie post content
        contentScope.select("a.stream-link-btn, iframe, a[target=_blank]").forEach { el ->
            val raw = (el.attr("href").ifBlank { el.attr("src") }).replace(whitespace, "")
            when {
                raw.startsWith("http") -> priorityButtons.add(raw)
                raw.startsWith("//") -> priorityButtons.add("https:$raw")
                raw.startsWith("/") -> priorityButtons.add(mainUrl + raw)
            }
        }

        // 1) Attributes inside contentScope only (prevents sidebar/footer movies from leaking in)
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

        // 2) Scripts inside contentScope only
        contentScope.select("script").forEach { s ->
            collectUrls(s.data(), rawFound)
            base64InScript.findAll(s.data()).forEach { m ->
                tryBase64(m.groupValues[1])?.let { d ->
                    if (d.contains("http")) collectUrls(d, rawFound)
                }
            }
        }

        // 3) Clean whitespace & drop prefix-truncated copies
        val cleaned = (priorityButtons + rawFound).map { it.replace(whitespace, "") }.toSet()
        val found = cleaned.filter { u ->
            cleaned.none { o -> o != u && o.startsWith(u) }
        }

        Log.d(tag, "page=${page.url} status=${page.code} found=${found.size}")

        val useful = found.filter { isUseful(it) }
        val internal = useful.filter { it.startsWith(mainUrl) }

        // Keep priority buttons at the front of `external`
        val external = linkedSetOf<String>()
        priorityButtons.filter { isUseful(it) && !it.startsWith(mainUrl) }.forEach { external.add(it) }
        useful.filter { !it.startsWith(mainUrl) }.forEach { external.add(it) }

        // 4) Follow same-site embed / ajax endpoints in parallel
        val followList = (
            internal.filter { embedHint.containsMatchIn(it) } +
                priorityButtons.filter { it.startsWith(mainUrl) }
            )
            .filter { !it.contains("/category/") && !it.endsWith(".html") && it != data }
            .distinct()
            .take(12)

        val extra: List<List<String>> = followList
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
                        .filter { isUseful(it) && !it.startsWith(mainUrl) }
                        .toMutableList()
                    val landed = r.url
                    if (landed.startsWith("http") && !landed.startsWith(mainUrl) && isUseful(landed)) {
                        out.add(landed)
                    }
                    out.toList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList<String>()
                }
            }
        extra.forEach { list -> list.forEach { external.add(it) } }

        val hosts = external.map { hostOf(it) }.distinct()
        Log.d(tag, "external=${external.size} hosts=$hosts")

        // 5) Try ALL external hosts in parallel — every host runs its full fallback chain
        val results = external.take(maxLinks).toList().amap { link ->
            extractOne(link, data, scb, cb)
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
