package recloudstream.animevietsub;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.plugin.PluginPreferences;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core HTTP, cookie bypass, JSoup parsing, and CDN stream decryption client
 * ported from CloudStream's AnimeVietsubProvider.
 */
public final class AnimeVietsubClient {

    public static final String DEFAULT_ENTRY_URL = "https://bit.ly/animevietsubtv";
    public static final String FALLBACK_DOMAIN = "https://animevietsub.bid";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static final Pattern REDIRECT_PATTERN =
            Pattern.compile("window\\.location\\.href\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern YEAR_PATTERN =
            Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("\\d+");
    private static final Pattern EP_NUM_PATTERN =
            Pattern.compile("\\d+([.,]\\d+)?");
    private static final Pattern STYLE_BG_PATTERN =
            Pattern.compile("url\\s*\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)");
    private static final Pattern AVS_TOKEN_DECL_PATTERN =
            Pattern.compile("const\\s+avsToken\\s*=\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\s*;");
    private static final Pattern AVS_TOKEN_CHUNK_PATTERN =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern AVS_TOKEN_SIMPLE_PATTERN =
            Pattern.compile("const\\s+avsToken\\s*=\\s*\"([^\"]+)");
    private static final Pattern PLAYER_HASH_PATTERN =
            Pattern.compile("/player/([0-9a-f]+)");
    private static final Pattern ORIGIN_PATTERN =
            Pattern.compile("^(https?://[^/]+)");
    private static final Pattern T_PARAM_PATTERN =
            Pattern.compile("[?&]_t=([^&\\s]+)");
    private static final Pattern HLS_ENCRYPTED_URL_PATTERN =
            Pattern.compile("/hls/[0-9a-f]{24}\\.ts\\?e=");
    private static final Pattern HLS_SEGMENT_PATTERN =
            Pattern.compile("/hls/([0-9a-f]{24})\\.ts");
    private static final Pattern PLAYER_DATA_PATTERN =
            Pattern.compile("window\\.PLAYER_DATA\\s*=\\s*(\\{.*?\\});");
    private static final Pattern EPISODE_ID_PATTERN =
            Pattern.compile("(?:episodeID\\s*=\\s*parseInt\\(['\"]|_epID\\s*=\\s*|/tap-[^/]+-)(\\d+)");
    private static final Pattern KEY_URI_PATTERN =
            Pattern.compile("(#EXT-X-KEY:[^\\n]*?URI=\")(https?://[^\"]+)(\")");

    private static final long DOC_CACHE_TTL_MS = 60_000L;
    private static final int DOC_CACHE_MAX_SIZE = 16;

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "avs-io");
        t.setDaemon(true);
        return t;
    });

    private static final class CachedDoc {
        final Document doc;
        final long timestampMs;

        CachedDoc(final Document doc, final long timestampMs) {
            this.doc = doc;
            this.timestampMs = timestampMs;
        }
    }

    private final PluginPreferences preferences;
    private final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private final Map<String, CachedDoc> docCache = new LinkedHashMap<String, CachedDoc>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, CachedDoc> eldest) {
            return size() > DOC_CACHE_MAX_SIZE;
        }
    };
    private volatile String activeDomain;
    private volatile String globalCookies = "";

    public AnimeVietsubClient(final PluginPreferences preferences) {
        this.preferences = preferences;
    }

    public String getConfiguredUrl() {
        final String pref = preferences != null ? preferences.getString("base_url") : null;
        if (pref == null || pref.trim().isEmpty()) {
            return DEFAULT_ENTRY_URL;
        }
        return pref.trim();
    }

    public String resolveBaseUrl(final Downloader downloader) {
        final String configured = getConfiguredUrl();
        if (!configured.contains("bit.ly") && configured.startsWith("http")) {
            return stripTrailingSlash(configured);
        }
        if (activeDomain != null && !activeDomain.isEmpty()) {
            return activeDomain;
        }
        try {
            final Response resp = downloader.get(configured, getBypassHeaders(null));
            final String latestUrl = resp.latestUrl();
            if (latestUrl != null && latestUrl.startsWith("http") && !latestUrl.contains("bit.ly")) {
                final URL u = new URL(latestUrl);
                activeDomain = u.getProtocol() + "://" + u.getHost();
                mergeResponseCookies(resp);
                return activeDomain;
            }
        } catch (final Exception ignored) {
        }
        activeDomain = FALLBACK_DOMAIN;
        return activeDomain;
    }

    public Map<String, List<String>> getBypassHeaders(final String referer) {
        final Map<String, List<String>> headers = new LinkedHashMap<>(8);
        headers.put("Accept", Collections.singletonList(
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"));
        headers.put("Accept-Language", Collections.singletonList("vi,en-US;q=0.9,en;q=0.8"));
        headers.put("Upgrade-Insecure-Requests", Collections.singletonList("1"));
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", Collections.singletonList(referer));
        }
        final String cookies = globalCookies;
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", Collections.singletonList(cookies));
        }
        return headers;
    }

    private void mergeResponseCookies(final Response response) {
        if (response == null || response.responseHeaders() == null) {
            return;
        }
        boolean changed = false;
        for (final Map.Entry<String, List<String>> entry : response.responseHeaders().entrySet()) {
            if ("set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                for (final String raw : entry.getValue()) {
                    final int semi = raw.indexOf(';');
                    final String clean = semi >= 0 ? raw.substring(0, semi) : raw;
                    final int eq = clean.indexOf('=');
                    if (eq > 0) {
                        final String k = clean.substring(0, eq).trim();
                        final String v = clean.substring(eq + 1).trim();
                        final String prev = cookieJar.put(k, v);
                        if (!v.equals(prev)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        if (!changed) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> e : cookieJar.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        globalCookies = sb.toString();
    }

    public static ExecutorService getIoPool() {
        return IO_POOL;
    }

    public Document fetchWithBypass(final Downloader downloader,
                                    final String initialUrl,
                                    final String referer) throws IOException, ExtractionException {
        final long now = System.currentTimeMillis();
        synchronized (docCache) {
            final CachedDoc cached = docCache.get(initialUrl);
            if (cached != null && (now - cached.timestampMs) < DOC_CACHE_TTL_MS) {
                return cached.doc;
            }
        }

        String currentUrl = initialUrl;
        Response resp = downloader.get(currentUrl, getBypassHeaders(referer));
        mergeResponseCookies(resp);
        String html = resp.responseBody();
        if (resp.latestUrl() != null && resp.latestUrl().startsWith("http")) {
            currentUrl = resp.latestUrl();
            try {
                final URL u = new URL(currentUrl);
                if (!u.getHost().contains("bit.ly")) {
                    activeDomain = u.getProtocol() + "://" + u.getHost();
                }
            } catch (final Exception ignored) {
            }
        }

        int attempts = 0;
        while (html != null && html.contains("window.location.href") && attempts < 3) {
            attempts++;
            final Matcher m = REDIRECT_PATTERN.matcher(html);
            if (!m.find()) {
                break;
            }
            final String redirectTarget = m.group(1);
            if (redirectTarget == null || redirectTarget.isEmpty()) {
                break;
            }
            if (redirectTarget.startsWith("http")) {
                currentUrl = redirectTarget;
            } else {
                currentUrl = new URL(new URL(currentUrl), redirectTarget).toString();
            }
            resp = downloader.get(currentUrl, getBypassHeaders(referer));
            mergeResponseCookies(resp);
            html = resp.responseBody();
        }
        final Document parsed = Jsoup.parse(html != null ? html : "", currentUrl);
        synchronized (docCache) {
            docCache.put(initialUrl, new CachedDoc(parsed, System.currentTimeMillis()));
            if (!currentUrl.equals(initialUrl)) {
                docCache.put(currentUrl, new CachedDoc(parsed, System.currentTimeMillis()));
            }
        }
        return parsed;
    }

    public String normalizeSiteUrl(final String url, final String baseUrl) {
        if (url == null || url.isEmpty()) {
            return baseUrl;
        }
        if (!url.startsWith("http")) {
            return fixUrl(url, baseUrl);
        }
        try {
            final URL parsed = new URL(url);
            final URL base = new URL(baseUrl);
            final String pathAndQuery = parsed.getFile();
            return base.getProtocol() + "://" + base.getHost() + pathAndQuery;
        } catch (final Exception e) {
            return url;
        }
    }

    public static String fixUrl(final String url, final String baseUrl) {
        if (url == null) {
            return null;
        }
        final String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        try {
            return new URL(new URL(baseUrl), trimmed).toString();
        } catch (final Exception e) {
            return trimmed;
        }
    }

    public static String stripTrailingSlash(final String s) {
        if (s != null && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String encodeUri(final String s) {
        if (s == null) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String parseStyleBackground(final String style) {
        if (style == null) {
            return null;
        }
        final Matcher m = STYLE_BG_PATTERN.matcher(style);
        return m.find() ? m.group(1) : null;
    }

    public List<SearchItem> search(final Downloader downloader, final String query)
            throws IOException, ExtractionException {
        final String baseUrl = resolveBaseUrl(downloader);
        final String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }

        final List<SearchItem> suggest = searchViaSuggest(downloader, baseUrl, trimmed);
        if (!suggest.isEmpty()) {
            return suggest;
        }

        final String searchUrl = baseUrl + "/tim-kiem/" + encodeUri(trimmed) + "/";
        final Document doc = fetchWithBypass(downloader, searchUrl, baseUrl);
        return parseMovieListItems(doc, baseUrl);
    }

    private List<SearchItem> searchViaSuggest(final Downloader downloader,
                                              final String baseUrl,
                                              final String query) {
        try {
            final Map<String, List<String>> headers = getBypassHeaders(baseUrl);
            headers.put("Content-Type", Collections.singletonList("application/x-www-form-urlencoded; charset=UTF-8"));
            headers.put("X-Requested-With", Collections.singletonList("XMLHttpRequest"));
            headers.put("Accept", Collections.singletonList("*/*"));
            headers.put("Origin", Collections.singletonList(baseUrl));

            final String bodyStr = "ajaxSearch=1&keysearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            final Response resp = downloader.post(
                    baseUrl + "/ajax/suggest",
                    headers,
                    bodyStr.getBytes(StandardCharsets.UTF_8)
            );
            mergeResponseCookies(resp);
            final Document doc = Jsoup.parse(resp.responseBody(), baseUrl);
            final Elements lis = doc.select("li");
            final List<SearchItem> results = new ArrayList<>();
            final Map<String, Boolean> seen = new HashMap<>();
            for (final Element li : lis) {
                final Element a = li.selectFirst("a");
                if (a == null) {
                    continue;
                }
                final String href = fixUrl(a.attr("href"), baseUrl);
                if (href == null || seen.containsKey(href)) {
                    continue;
                }
                final Element titleEl = li.selectFirst(".ss-title");
                final String title = titleEl != null ? titleEl.text().trim() : a.text().trim();
                if (title.isEmpty()) {
                    continue;
                }
                String poster = null;
                final Element thumb = li.selectFirst(".thumb");
                if (thumb != null) {
                    poster = fixUrl(parseStyleBackground(thumb.attr("style")), baseUrl);
                }
                if (poster == null) {
                    final Element img = li.selectFirst("img");
                    if (img != null) {
                        poster = fixUrl(img.attr("src"), baseUrl);
                    }
                }
                final Element statusEl = li.selectFirst(".ss-info p");
                final String statusText = statusEl != null ? statusEl.text().trim() : "";
                seen.put(href, Boolean.TRUE);
                results.add(new SearchItem(title, href, poster, statusText));
            }
            return results;
        } catch (final Exception e) {
            return Collections.emptyList();
        }
    }

    public List<SearchItem> parseMovieListItems(final Document doc, final String baseUrl) {
        final List<SearchItem> results = new ArrayList<>();
        final Elements elements = doc.select("ul.bxh-movie-phimletv li.group, ul.MovieList li.TPostMv");
        for (final Element el : elements) {
            final SearchItem item = parseSingleMovieElement(el, baseUrl);
            if (item != null) {
                results.add(item);
            }
        }
        return results;
    }

    public SearchItem parseSingleMovieElement(final Element element, final String baseUrl) {
        if (element.hasClass("group")) {
            final Element titleEl = element.selectFirst("h3.title-item a");
            if (titleEl == null) {
                return null;
            }
            final String title = titleEl.text().trim();
            final String href = fixUrl(titleEl.attr("href"), baseUrl);
            if (href == null || title.isEmpty()) {
                return null;
            }
            String poster = null;
            final Element img1 = element.selectFirst("a.thumb img");
            if (img1 != null && !img1.attr("src").trim().isEmpty()) {
                poster = img1.attr("src").trim();
            } else {
                final Element img2 = element.selectFirst("img");
                if (img2 != null) {
                    poster = img2.attr("data-src").trim();
                    if (poster.isEmpty()) {
                        poster = img2.attr("src").trim();
                    }
                }
            }
            return new SearchItem(title, href, fixUrl(poster, baseUrl), "");
        } else {
            final Element a = element.selectFirst("a");
            final Element titleEl = element.selectFirst("h2.Title, .Title, h1.Title");
            if (a == null || titleEl == null) {
                return null;
            }
            final String href = fixUrl(a.attr("href"), baseUrl);
            final String title = titleEl.text().trim();
            if (href == null || title.isEmpty()) {
                return null;
            }
            String poster = null;
            final Element img = element.selectFirst("div.Image img, div.TPost img, img");
            if (img != null) {
                poster = img.attr("data-src").trim();
                if (poster.isEmpty()) {
                    poster = img.attr("src").trim();
                }
            }
            final Element epEl = element.selectFirst("span.mli-eps, span.mli-quality, span.label");
            final String epText = epEl != null ? epEl.text().trim() : "";
            return new SearchItem(title, href, fixUrl(poster, baseUrl), epText);
        }
    }

    public static String extractPosterUrl(final Document doc, final String baseUrl) {
        final Element img = doc.selectFirst("div.TPost.Single div.Image figure.Objf img");
        if (img != null) {
            final String src = img.attr("src").trim();
            if (!src.isEmpty()) {
                return fixUrl(src, baseUrl);
            }
        }
        for (final Element meta : doc.select("meta[property=og:image], meta[itemprop=image]")) {
            final String content = meta.attr("content").trim();
            if (!content.isEmpty()) {
                return fixUrl(content, baseUrl);
            }
        }
        return "";
    }

    public static String extractBannerUrl(final Document doc, final String baseUrl, final String fallbackPoster) {
        final Element bannerEl = doc.selectFirst("div.TPostBg.Objf img.TPostBg");
        if (bannerEl != null) {
            final String src = bannerEl.attr("src").trim();
            if (!src.isEmpty() && !src.toLowerCase(Locale.ROOT).contains("default/images/bg")) {
                return fixUrl(src, baseUrl);
            }
        }
        return fallbackPoster;
    }

    public static String extractTitle(final Document doc) {
        final Element h1 = doc.selectFirst("h1.Title, h1, .Title, header h1");
        if (h1 != null && !h1.text().trim().isEmpty()) {
            return h1.text().trim();
        }
        final Element meta = doc.selectFirst("meta[property=og:title], meta[name=title]");
        if (meta != null) {
            String content = meta.attr("content");
            final int pipeIdx = content.indexOf('|');
            if (pipeIdx >= 0) {
                content = content.substring(0, pipeIdx);
            }
            final int tapIdx = content.indexOf(" Tập");
            if (tapIdx >= 0) {
                content = content.substring(0, tapIdx);
            }
            return content.trim();
        }
        return "AnimeVietsub";
    }

    public static String extractDescription(final Document doc) {
        final Element descEl = doc.selectFirst("div.Description");
        return descEl != null ? descEl.text().trim() : "";
    }

    public static List<EpisodeInfo> parseEpisodes(final Document watchDoc, final String baseUrl) {
        final Map<String, List<ServerData>> episodesMap = new LinkedHashMap<>();
        final Elements serverGroups = watchDoc.select("div.server-group, li.backup-server");
        if (!serverGroups.isEmpty()) {
            for (final Element group : serverGroups) {
                final Element nameEl = group.selectFirst("h3.server-name, h3.server-title, .server-name");
                final String groupName = (nameEl != null && !nameEl.text().trim().isEmpty())
                        ? nameEl.text().trim()
                        : "VIP";
                final Elements epLinks = group.select("ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a");
                for (final Element epEl : epLinks) {
                    addEpisodeElement(episodesMap, groupName, epEl, baseUrl);
                }
            }
        } else {
            final Elements epLinks = watchDoc.select("ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a");
            for (final Element epEl : epLinks) {
                addEpisodeElement(episodesMap, "VIP", epEl, baseUrl);
            }
        }

        final List<EpisodeInfo> list = new ArrayList<>();
        for (final Map.Entry<String, List<ServerData>> entry : episodesMap.entrySet()) {
            final String epName = entry.getKey();
            final List<ServerData> servers = entry.getValue();
            if (servers.isEmpty()) {
                continue;
            }
            String epHref = servers.get(0).href;
            if (epHref == null || epHref.isEmpty()) {
                epHref = watchDoc.location();
            }
            float sortOrder = Float.MAX_VALUE;
            final Matcher m = EP_NUM_PATTERN.matcher(epName);
            String lastMatch = null;
            while (m.find()) {
                lastMatch = m.group();
            }
            if (lastMatch != null) {
                try {
                    sortOrder = Float.parseFloat(lastMatch.replace(',', '.'));
                } catch (final NumberFormatException ignored) {
                }
            }
            list.add(new EpisodeInfo(epName, epHref, servers, sortOrder));
        }
        list.sort((a, b) -> Float.compare(a.sortOrder, b.sortOrder));
        return list;
    }

    private static void addEpisodeElement(final Map<String, List<ServerData>> episodesMap,
                                          final String groupName,
                                          final Element epEl,
                                          final String baseUrl) {
        String epNum = epEl.attr("title").trim();
        if (epNum.isEmpty()) {
            epNum = epEl.text().trim();
        }
        final String hash = epEl.attr("data-hash").trim();
        final String id = epEl.attr("data-id").trim();
        final String href = fixUrl(epEl.attr("href"), baseUrl);
        if (!hash.isEmpty()) {
            episodesMap.computeIfAbsent(epNum, k -> new ArrayList<>())
                    .add(new ServerData(groupName, hash, id, href != null ? href : ""));
        }
    }

    /**
     * Resolves playable video streams for a given episode URL or active episode on a watch page.
     */
    public List<ResolvedStream> resolveStreamsForWatchUrl(final Downloader downloader,
                                                          final String targetUrl)
            throws IOException, ExtractionException {
        final String baseUrl = resolveBaseUrl(downloader);
        final String normalizedUrl = normalizeSiteUrl(targetUrl, baseUrl);
        final Document doc = fetchWithBypass(downloader, normalizedUrl, baseUrl);
        return resolveStreamsForWatchDoc(downloader, normalizedUrl, doc, baseUrl);
    }

    /**
     * Resolves playable video streams using an already-fetched watch Document to avoid redundant HTTP requests.
     */
    public List<ResolvedStream> resolveStreamsForWatchDoc(final Downloader downloader,
                                                          final String normalizedUrl,
                                                          final Document preloadedDoc,
                                                          final String baseUrl)
            throws IOException, ExtractionException {
        Document doc = preloadedDoc != null ? preloadedDoc : fetchWithBypass(downloader, normalizedUrl, baseUrl);
        final List<ResolvedStream> resolvedStreams = new ArrayList<>();

        // 1. Check inline window.PLAYER_DATA first if present on the fetched watch page
        final String docHtml = doc.html();
        final Matcher pdMatcher = PLAYER_DATA_PATTERN.matcher(docHtml);
        if (pdMatcher.find()) {
            try {
                final JsonObject pdJson = JsonParser.object().from(pdMatcher.group(1));
                final ResolvedStream inlineStream = parsePlayerResponseJson(downloader, baseUrl, "VIP", pdJson);
                if (inlineStream != null) {
                    resolvedStreams.add(inlineStream);
                    return resolvedStreams;
                }
            } catch (final Exception ignored) {
            }
        }

        // 2. Match the active episode element by URL path, filmInfo.episodeID, or .active class
        Elements activeEls = doc.select("a.btn-episode.active, a.episode-link.active, .list-episode a.active");
        if (activeEls.isEmpty()) {
            final Matcher epIdMatcher = EPISODE_ID_PATTERN.matcher(docHtml + " " + normalizedUrl);
            if (epIdMatcher.find()) {
                final String epId = epIdMatcher.group(1);
                activeEls = doc.select("a.episode-link[data-id='" + epId + "'], .list-episode a[data-id='" + epId + "']");
            }
        }
        if (activeEls.isEmpty()) {
            for (final Element a : doc.select("ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a")) {
                final String href = a.attr("href");
                if (!href.isEmpty() && normalizedUrl.endsWith(href)) {
                    activeEls.add(a);
                }
            }
        }
        if (activeEls.isEmpty()) {
            final Element firstEp = doc.selectFirst("ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a");
            if (firstEp != null) {
                activeEls = new Elements(firstEp);
            } else if (!normalizedUrl.endsWith("/xem-phim.html")) {
                final String watchUrl = stripTrailingSlash(normalizedUrl) + "/xem-phim.html";
                doc = fetchWithBypass(downloader, watchUrl, baseUrl);
                final String watchHtml = doc.html();
                final Matcher watchPdMatcher = PLAYER_DATA_PATTERN.matcher(watchHtml);
                if (watchPdMatcher.find()) {
                    try {
                        final JsonObject pdJson = JsonParser.object().from(watchPdMatcher.group(1));
                        final ResolvedStream inlineStream = parsePlayerResponseJson(downloader, baseUrl, "VIP", pdJson);
                        if (inlineStream != null) {
                            resolvedStreams.add(inlineStream);
                            return resolvedStreams;
                        }
                    } catch (final Exception ignored) {
                    }
                }
                activeEls = doc.select("a.btn-episode.active, a.episode-link.active, .list-episode a.active");
                if (activeEls.isEmpty()) {
                    final Element firstWatchEp = doc.selectFirst("ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a");
                    if (firstWatchEp != null) {
                        activeEls = new Elements(firstWatchEp);
                    }
                }
            }
        }

        final List<ServerData> activeServers = new ArrayList<>();
        for (final Element el : activeEls) {
            final String hash = el.attr("data-hash").trim();
            final String id = el.attr("data-id").trim();
            final Element group = el.closest(".server-group");
            String groupName = "VIP";
            if (group != null) {
                final Element nameEl = group.selectFirst("h3.server-name, h3.server-title, .server-name");
                if (nameEl != null && !nameEl.text().trim().isEmpty()) {
                    groupName = nameEl.text().trim();
                }
            }
            if (!hash.isEmpty()) {
                activeServers.add(new ServerData(groupName, hash, id, normalizedUrl));
            }
        }

        if (activeServers.size() == 1) {
            try {
                final ResolvedStream rs = fetchPlayerLink(downloader, baseUrl, activeServers.get(0));
                if (rs != null) {
                    resolvedStreams.add(rs);
                }
            } catch (final Exception ignored) {
            }
        } else if (activeServers.size() > 1) {
            final List<CompletableFuture<ResolvedStream>> futures = new ArrayList<>(activeServers.size());
            for (final ServerData server : activeServers) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchPlayerLink(downloader, baseUrl, server);
                    } catch (final Exception ignored) {
                        return null;
                    }
                }, IO_POOL));
            }
            for (final CompletableFuture<ResolvedStream> f : futures) {
                try {
                    final ResolvedStream rs = f.join();
                    if (rs != null) {
                        resolvedStreams.add(rs);
                    }
                } catch (final Exception ignored) {
                }
            }
        }
        return resolvedStreams;
    }

    private ResolvedStream fetchPlayerLink(final Downloader downloader,
                                           final String baseUrl,
                                           final ServerData server) throws Exception {
        final Map<String, List<String>> headers = getBypassHeaders(baseUrl);
        headers.put("Content-Type", Collections.singletonList("application/x-www-form-urlencoded; charset=UTF-8"));
        headers.put("X-Requested-With", Collections.singletonList("XMLHttpRequest"));

        final String postBody = "link=" + URLEncoder.encode(server.hash, StandardCharsets.UTF_8)
                + "&id=" + URLEncoder.encode(server.id, StandardCharsets.UTF_8);
        final Response resp = downloader.post(
                baseUrl + "/ajax/player",
                headers,
                postBody.getBytes(StandardCharsets.UTF_8)
        );
        mergeResponseCookies(resp);

        final JsonObject json = JsonParser.object().from(resp.responseBody());
        return parsePlayerResponseJson(downloader, baseUrl, server.groupName, json);
    }

    private ResolvedStream parsePlayerResponseJson(final Downloader downloader,
                                                   final String baseUrl,
                                                   final String groupName,
                                                   final JsonObject json) {
        final Object linkObj = json.get("link");
        final String playTech = json.getString("playTech", "");

        if (linkObj instanceof JsonArray) {
            final JsonArray arr = (JsonArray) linkObj;
            for (int i = 0; i < arr.size(); i++) {
                final Object item = arr.get(i);
                if (item instanceof JsonObject) {
                    final JsonObject itemObj = (JsonObject) item;
                    final String file = itemObj.getString("file", "");
                    final String label = itemObj.getString("label", "720p");
                    final String type = itemObj.getString("type", "");
                    if (file.startsWith("http")) {
                        final boolean isHls = file.contains(".m3u8") || "hls".equalsIgnoreCase(type);
                        final Map<String, String> reqHeaders = new LinkedHashMap<>();
                        reqHeaders.put("User-Agent", USER_AGENT);
                        reqHeaders.put("Referer", baseUrl + "/");
                        return new ResolvedStream(
                                groupName,
                                file,
                                true,
                                null,
                                label,
                                isHls,
                                reqHeaders
                        );
                    }
                }
            }
        }

        final String link = json.getString("link", "");
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        if (link.contains("googleapiscdn.com")) {
            final PlaylistResult playlist = decryptGoogleApisCdn(downloader, link);
            if (playlist != null) {
                final Map<String, String> reqHeaders = new LinkedHashMap<>();
                reqHeaders.put("User-Agent", USER_AGENT);
                reqHeaders.put("Referer", "https://stream.googleapiscdn.com/");
                reqHeaders.put("Origin", "https://stream.googleapiscdn.com");
                final String dataUri = "data:application/vnd.apple.mpegurl;base64,"
                        + Base64.getEncoder().encodeToString(playlist.text.getBytes(StandardCharsets.UTF_8));
                return new ResolvedStream(
                        groupName,
                        dataUri,
                        true,
                        playlist.url,
                        "1080p",
                        true,
                        reqHeaders
                );
            }
        } else if (!"iframe".equals(playTech) && link.startsWith("http")) {
            final boolean isHls = link.contains(".m3u8");
            final Map<String, String> reqHeaders = new LinkedHashMap<>();
            reqHeaders.put("User-Agent", USER_AGENT);
            reqHeaders.put("Referer", baseUrl + "/");
            return new ResolvedStream(
                    groupName,
                    link,
                    true,
                    null,
                    "720p",
                    isHls,
                    reqHeaders
            );
        }
        return null;
    }

    public PlaylistResult decryptGoogleApisCdn(final Downloader downloader, final String playerUrl) {
        final Matcher hashMatcher = PLAYER_HASH_PATTERN.matcher(playerUrl);
        final String videoHash = hashMatcher.find() ? hashMatcher.group(1) : null;
        final Matcher originMatcher = ORIGIN_PATTERN.matcher(playerUrl);
        final String origin = originMatcher.find() ? originMatcher.group(1) : null;
        if (videoHash == null || origin == null) {
            return null;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            String avsToken = null;
            try {
                final Response resp = downloader.get(playerUrl, getBypassHeaders(playerUrl));
                mergeResponseCookies(resp);
                avsToken = extractAvsToken(resp.responseBody());
            } catch (final Exception ignored) {
            }
            if (avsToken != null && !avsToken.isEmpty()) {
                final String playlistUrl = origin + "/playlist/" + videoHash
                        + "/playlist.m3u8?token=" + URLEncoder.encode(avsToken, StandardCharsets.UTF_8);
                try {
                    final String decryptedM3u8 = fetchDecryptPlaylist(downloader, playlistUrl, avsToken, playerUrl);
                    if (decryptedM3u8 != null && !decryptedM3u8.trim().isEmpty()) {
                        final String inlinedKeysM3u8 = inlineExtXKeys(downloader, decryptedM3u8, playerUrl);
                        return new PlaylistResult(playlistUrl, inlinedKeysM3u8);
                    }
                } catch (final Exception ignored) {
                }
            }
        }
        return null;
    }

    private String inlineExtXKeys(final Downloader downloader,
                                  final String m3u8Text,
                                  final String referer) {
        final Matcher collectMatcher = KEY_URI_PATTERN.matcher(m3u8Text);
        final List<String> uniqueUrls = new ArrayList<>();
        final Map<String, Boolean> seen = new LinkedHashMap<>();
        while (collectMatcher.find()) {
            final String keyUrl = collectMatcher.group(2);
            if (!seen.containsKey(keyUrl)) {
                seen.put(keyUrl, Boolean.TRUE);
                uniqueUrls.add(keyUrl);
            }
        }
        if (uniqueUrls.isEmpty()) {
            return m3u8Text;
        }

        final Map<String, String> keyDataUriCache = new ConcurrentHashMap<>(uniqueUrls.size());
        if (uniqueUrls.size() == 1) {
            final String url = uniqueUrls.get(0);
            final String dataUri = fetchKeyAsDataUri(downloader, url, referer);
            if (dataUri != null) {
                keyDataUriCache.put(url, dataUri);
            }
        } else {
            final List<CompletableFuture<Void>> futures = new ArrayList<>(uniqueUrls.size());
            for (final String url : uniqueUrls) {
                futures.add(CompletableFuture.runAsync(() -> {
                    final String dataUri = fetchKeyAsDataUri(downloader, url, referer);
                    if (dataUri != null) {
                        keyDataUriCache.put(url, dataUri);
                    }
                }, IO_POOL));
            }
            for (final CompletableFuture<Void> f : futures) {
                try {
                    f.join();
                } catch (final Exception ignored) {
                }
            }
        }

        if (keyDataUriCache.isEmpty()) {
            return m3u8Text;
        }
        final Matcher replaceMatcher = KEY_URI_PATTERN.matcher(m3u8Text);
        final StringBuilder sb = new StringBuilder(m3u8Text.length());
        while (replaceMatcher.find()) {
            final String prefix = replaceMatcher.group(1);
            final String keyUrl = replaceMatcher.group(2);
            final String suffix = replaceMatcher.group(3);
            final String replacementUri = keyDataUriCache.get(keyUrl);
            if (replacementUri != null) {
                replaceMatcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + replacementUri + suffix));
            } else {
                replaceMatcher.appendReplacement(sb, Matcher.quoteReplacement(replaceMatcher.group(0)));
            }
        }
        replaceMatcher.appendTail(sb);
        return sb.toString();
    }

    private String fetchKeyAsDataUri(final Downloader downloader,
                                     final String keyUrl,
                                     final String referer) {
        try {
            final Response keyResp = downloader.get(keyUrl, getBypassHeaders(referer));
            mergeResponseCookies(keyResp);
            byte[] keyBytes = keyResp.rawResponseBody();
            if ((keyBytes == null || keyBytes.length == 0) && keyResp.responseBody() != null) {
                keyBytes = keyResp.responseBody().getBytes(StandardCharsets.ISO_8859_1);
            }
            if (keyBytes != null && keyBytes.length == 16) {
                return "data:application/octet-stream;base64,"
                        + Base64.getEncoder().encodeToString(keyBytes);
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    private String fetchDecryptPlaylist(final Downloader downloader,
                                        final String playlistUrl,
                                        final String avsToken,
                                        final String referer) throws IOException, ExtractionException {
        final Response m3u8Response = downloader.get(playlistUrl, getBypassHeaders(referer));
        mergeResponseCookies(m3u8Response);
        final String m3u8Text = m3u8Response.responseBody();
        return processEncryptedM3u8(m3u8Text, m3u8Response.responseHeaders(), avsToken);
    }

    private String extractAvsToken(final String html) {
        if (html == null) {
            return null;
        }
        final Matcher declMatcher = AVS_TOKEN_DECL_PATTERN.matcher(html);
        if (declMatcher.find()) {
            final String decl = declMatcher.group(1);
            if (decl != null) {
                final Matcher chunkMatcher = AVS_TOKEN_CHUNK_PATTERN.matcher(decl);
                final StringBuilder sb = new StringBuilder();
                while (chunkMatcher.find()) {
                    sb.append(chunkMatcher.group(1));
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        }
        final Matcher simpleMatcher = AVS_TOKEN_SIMPLE_PATTERN.matcher(html);
        if (simpleMatcher.find()) {
            final String token = simpleMatcher.group(1);
            if (token != null && !token.trim().isEmpty()) {
                return token;
            }
        }
        return null;
    }

    private static String getHeaderFirst(final Map<String, List<String>> headers, final String name) {
        if (headers == null) {
            return null;
        }
        for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private String processEncryptedM3u8(final String m3u8Text,
                                        final Map<String, List<String>> headers,
                                        final String avsToken) {
        try {
            final String[] jwtParts = avsToken.split("\\.");
            if (jwtParts.length < 2) {
                return null;
            }
            final String payloadJson = new String(b64UrlDecode(jwtParts[1]), StandardCharsets.UTF_8);
            final JsonObject jwtObj = JsonParser.object().from(payloadJson);
            final String jti = jwtObj.getString("jti", "");
            if (jti.isEmpty()) {
                return null;
            }
            final StringBuilder jtiOddSb = new StringBuilder();
            for (int i = 0; i < jti.length(); i++) {
                if (i % 2 == 1) {
                    jtiOddSb.append(jti.charAt(i));
                }
            }
            final String jtiOdd = jtiOddSb.toString();

            String envHeader = getHeaderFirst(headers, "x-envelope");
            if (envHeader == null) {
                envHeader = getHeaderFirst(headers, "x-avs-envelope");
            }
            if (envHeader == null) {
                envHeader = getHeaderFirst(headers, "x-stream-envelope");
            }

            String cn = "";
            String sk = "";
            String ts = "0";
            String uid = "anon";

            if (envHeader != null && !envHeader.isEmpty()) {
                final JsonObject envJson = parseEnvelope(envHeader);
                if (envJson != null) {
                    cn = envJson.getString("cn", "");
                    sk = envJson.getString("sk", "");
                    ts = envJson.getString("ts", "0");
                    uid = envJson.getString("uid", "anon");
                }
            }
            if (cn.isEmpty()) {
                final String h = getHeaderFirst(headers, "x-edge-tag");
                cn = h != null ? h : "";
            }
            if (sk.isEmpty()) {
                final String h = getHeaderFirst(headers, "x-cache-node");
                sk = h != null ? h : "";
            }
            if ("0".equals(ts)) {
                final String h = getHeaderFirst(headers, "x-request-trace");
                ts = h != null ? h : "0";
            }
            if ("anon".equals(uid)) {
                final String pd = getHeaderFirst(headers, "x-proxy-digest");
                if (pd != null) {
                    try {
                        uid = URLDecoder.decode(pd, StandardCharsets.UTF_8);
                    } catch (final Exception ignored) {
                    }
                }
            }
            if (cn.isEmpty() || sk.isEmpty()) {
                return null;
            }

            final String[] lines = m3u8Text.split("\n");
            final List<String> tValues = new ArrayList<>();
            final List<String> headerLines = new ArrayList<>();
            for (final String line : lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    if (!line.startsWith("#EXTINF:") && !line.startsWith("#EXT-X-ENDLIST") && !line.startsWith("#EXT-X-KEY")) {
                        headerLines.add(line);
                    }
                } else {
                    final Matcher tm = T_PARAM_PATTERN.matcher(line);
                    if (tm.find()) {
                        tValues.add(tm.group(1));
                    }
                }
            }
            if (tValues.isEmpty()) {
                return null;
            }

            final StringBuilder concatSb = new StringBuilder();
            for (final String tv : tValues) {
                concatSb.append(tv);
            }
            final String concatenated = concatSb.toString();
            final byte[] cnBytes = b64UrlDecode(cn);
            final byte[] iv = Arrays.copyOfRange(cnBytes, 0, Math.min(12, cnBytes.length));

            final String[] candidates = new String[]{
                    stringUnshuffle(concatenated, sk),
                    concatenated
            };
            final String[] hmacFormats = new String[]{
                    uid + ":" + ts + ":" + sk + ":0",
                    uid + ":" + ts + ":" + sk
            };

            for (final String unshuffled : candidates) {
                for (final String hmacData : hmacFormats) {
                    try {
                        final byte[] encryptedBlob = b64UrlDecode(unshuffled);
                        final Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(cnBytes, "HmacSHA256"));
                        final byte[] gcmKeyBytes = mac.doFinal(hmacData.getBytes(StandardCharsets.UTF_8));
                        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(gcmKeyBytes, "AES"), new GCMParameterSpec(128, iv));
                        final byte[] rawResult = cipher.doFinal(encryptedBlob);
                        String m3u8Body = new String(rawResult, StandardCharsets.UTF_8);
                        if (!m3u8Body.contains("#EXTINF") && !m3u8Body.contains("/hls/")) {
                            final byte[] descrambled = descramble(rawResult, sk, ts);
                            m3u8Body = new String(descrambled, StandardCharsets.UTF_8);
                        }
                        String fullM3u8Text = String.join("\n", headerLines) + "\n" + m3u8Body;
                        if (!fullM3u8Text.contains("#EXT-X-ENDLIST")) {
                            fullM3u8Text = fullM3u8Text + "\n#EXT-X-ENDLIST";
                        }
                        if (HLS_ENCRYPTED_URL_PATTERN.matcher(fullM3u8Text).find()) {
                            return decryptM3u8SegmentUrls(fullM3u8Text, jtiOdd);
                        }
                        if (m3u8Body.contains("#EXTINF")) {
                            return fullM3u8Text;
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    private String decryptM3u8SegmentUrls(final String intermediateM3u8, final String jtiOdd) {
        final String[] lines = intermediateM3u8.split("\n");
        final List<String> cleanLines = new ArrayList<>(lines.length);

        Mac mac = null;
        Cipher cipher = null;
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jtiOdd.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
        } catch (final Exception ignored) {
        }
        final Map<String, SecretKeySpec> keyCache = new HashMap<>();
        final byte[] counter = new byte[16];

        for (final String rawLine : lines) {
            if (rawLine.contains("urn:avs:shield")) {
                continue;
            }
            final String line = rawLine.trim();
            if (line.startsWith("#") || line.isEmpty()) {
                cleanLines.add(rawLine);
                continue;
            }
            final Matcher m = HLS_SEGMENT_PATTERN.matcher(line);
            if (!m.find()) {
                cleanLines.add(rawLine);
                continue;
            }
            String resolvedLine = null;
            if (mac != null && cipher != null) {
                try {
                    final String fileId = m.group(1);
                    String eParam = null;
                    int iParam = 0;
                    final int qIdx = line.indexOf('?');
                    if (qIdx >= 0) {
                        final String query = line.substring(qIdx + 1);
                        int start = 0;
                        while (start < query.length()) {
                            final int amp = query.indexOf('&', start);
                            final int end = amp >= 0 ? amp : query.length();
                            final int eq = query.indexOf('=', start);
                            if (eq > start && eq < end) {
                                final String key = query.substring(start, eq);
                                if ("e".equals(key)) {
                                    eParam = query.substring(eq + 1, end);
                                } else if ("i".equals(key)) {
                                    try {
                                        iParam = Integer.parseInt(query.substring(eq + 1, end));
                                    } catch (final NumberFormatException ignored) {
                                    }
                                }
                            }
                            start = end + 1;
                        }
                    }
                    if (eParam != null) {
                        SecretKeySpec keySpec = keyCache.get(fileId);
                        if (keySpec == null) {
                            final byte[] aesKey = mac.doFinal(("url-cipher|" + fileId).getBytes(StandardCharsets.UTF_8));
                            keySpec = new SecretKeySpec(aesKey, "AES");
                            keyCache.put(fileId, keySpec);
                        }
                        counter[12] = (byte) ((iParam >> 24) & 255);
                        counter[13] = (byte) ((iParam >> 16) & 255);
                        counter[14] = (byte) ((iParam >> 8) & 255);
                        counter[15] = (byte) (iParam & 255);
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(counter));
                        final byte[] decrypted = cipher.doFinal(b64UrlDecode(eParam));
                        final String decryptedUrl = new String(decrypted, StandardCharsets.UTF_8);
                        if (decryptedUrl.startsWith("http")) {
                            resolvedLine = decryptedUrl;
                        }
                    }
                } catch (final Exception ignored) {
                }
            }
            if (resolvedLine != null) {
                cleanLines.add(resolvedLine);
            }
        }
        return String.join("\n", cleanLines);
    }

    private static byte[] b64UrlDecode(final String str) {
        String s = str.replace('-', '+').replace('_', '/');
        while (s.length() % 4 != 0) {
            s = s + "=";
        }
        return Base64.getDecoder().decode(s);
    }

    private static int lcgNext(final int state) {
        return (int) (((((long) state) * 1664525L) + 1013904223L) & 0xFFFFFFFFL);
    }

    private static int deriveSeed(final String sk) {
        final String sub = sk.substring(0, Math.min(8, sk.length()));
        int idx = 0;
        while (idx < sub.length()) {
            final char c = sub.charAt(idx);
            final boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHex) {
                break;
            }
            idx++;
        }
        final String hexPrefix = sub.substring(0, idx);
        if (!hexPrefix.isEmpty()) {
            return (int) Long.parseLong(hexPrefix, 16);
        }
        return 0;
    }

    private static String stringUnshuffle(final String str, final String seed) {
        final char[] chars = str.toCharArray();
        final int len = chars.length;
        int state = deriveSeed(seed);
        final int[] swapA = new int[Math.max(0, len - 1)];
        final int[] swapB = new int[Math.max(0, len - 1)];
        int count = 0;
        for (int i = len - 1; i > 0; i--) {
            state = lcgNext(state);
            final int j = (int) ((((long) state) & 0xFFFFFFFFL) % (((long) i) + 1L));
            swapA[count] = i;
            swapB[count] = j;
            count++;
        }
        for (int k = count - 1; k >= 0; k--) {
            final int a = swapA[k];
            final int b = swapB[k];
            final char tmp = chars[a];
            chars[a] = chars[b];
            chars[b] = tmp;
        }
        return new String(chars);
    }

    private static byte[] descramble(final byte[] data, final String permKey, final String permSalt) {
        final int len = data.length;
        if (len == 0) {
            return new byte[0];
        }
        final String seed = permKey + "|" + permSalt;
        long hash = 2166136261L;
        for (int i = 0; i < seed.length(); i++) {
            hash = (16777619L * ((((long) seed.charAt(i)) ^ hash) & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        }
        final int[] prngState = new int[]{(int) hash == 0 ? 1 : (int) hash};

        final int[] perm = new int[len];
        for (int i = 0; i < len; i++) {
            perm[i] = i;
        }
        for (int i = len - 1; i > 0; i--) {
            final int j = (int) (nextPrng(prngState) % (((long) i) + 1L));
            final int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        final byte[] output = new byte[len];
        long xorState = 0;
        for (int i = 0; i < len; i++) {
            if ((i & 3) == 0) {
                xorState = nextPrng(prngState);
            }
            output[perm[i]] = (byte) (data[i] ^ ((int) ((xorState >>> ((i & 3) * 8)) & 255L)));
        }
        return output;
    }

    private static long nextPrng(final int[] state) {
        int s = state[0];
        s ^= (s << 13);
        s ^= (s >>> 17);
        s ^= (s << 5);
        state[0] = s;
        return ((long) s) & 0xFFFFFFFFL;
    }

    private static JsonObject parseEnvelope(final String envB64) {
        try {
            final byte[] bytes = b64UrlDecode(envB64);
            if (bytes.length < 11 || bytes[0] != 85 || bytes[1] != 83 || bytes[2] != 68 || bytes[3] != 75 || bytes[4] != 1) {
                return null;
            }
            final int payloadLen = ((bytes[5] & 255) << 8) | (bytes[6] & 255);
            if (bytes.length < payloadLen + 11) {
                return null;
            }
            final byte[] payload = Arrays.copyOfRange(bytes, 7, 7 + payloadLen);
            final String str = new String(payload, StandardCharsets.ISO_8859_1);
            final String decoded = URLDecoder.decode(str, StandardCharsets.UTF_8);
            return JsonParser.object().from(decoded);
        } catch (final Exception e) {
            return null;
        }
    }

    public static final class SearchItem {
        public final String title;
        public final String url;
        public final String posterUrl;
        public final String episodeText;

        public SearchItem(final String title, final String url, final String posterUrl, final String episodeText) {
            this.title = title;
            this.url = url;
            this.posterUrl = posterUrl != null ? posterUrl : "";
            this.episodeText = episodeText != null ? episodeText : "";
        }
    }

    public static final class ServerData {
        public final String groupName;
        public final String hash;
        public final String id;
        public final String href;

        public ServerData(final String groupName, final String hash, final String id, final String href) {
            this.groupName = groupName;
            this.hash = hash;
            this.id = id;
            this.href = href;
        }
    }

    public static final class EpisodeInfo {
        public final String name;
        public final String url;
        public final List<ServerData> servers;
        public final float sortOrder;

        public EpisodeInfo(final String name, final String url, final List<ServerData> servers, final float sortOrder) {
            this.name = name;
            this.url = url;
            this.servers = servers;
            this.sortOrder = sortOrder;
        }
    }

    public static final class PlaylistResult {
        public final String url;
        public final String text;

        public PlaylistResult(final String url, final String text) {
            this.url = url;
            this.text = text;
        }
    }

    public static final class ResolvedStream {
        public final String serverName;
        public final String content;
        public final boolean isUrl;
        public final String manifestUrl;
        public final String resolution;
        public final boolean isHls;
        public final Map<String, String> headers;

        public ResolvedStream(final String serverName,
                              final String content,
                              final boolean isUrl,
                              final String manifestUrl,
                              final String resolution,
                              final boolean isHls,
                              final Map<String, String> headers) {
            this.serverName = serverName;
            this.content = content;
            this.isUrl = isUrl;
            this.manifestUrl = manifestUrl;
            this.resolution = resolution;
            this.isHls = isHls;
            this.headers = headers;
        }
    }
}
