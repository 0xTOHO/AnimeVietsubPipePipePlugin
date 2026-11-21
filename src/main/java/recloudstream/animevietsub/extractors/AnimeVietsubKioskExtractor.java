package recloudstream.animevietsub.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import recloudstream.animevietsub.AnimeVietsubClient;
import recloudstream.animevietsub.AnimeVietsubClient.SearchItem;
import recloudstream.animevietsub.linkhandlers.AnimeVietsubKioskLinkHandlerFactory;

public final class AnimeVietsubKioskExtractor extends KioskExtractor<StreamInfoItem> {

    private final AnimeVietsubClient client;
    private String baseUrl;
    private Document doc;
    private List<SearchItem> items = Collections.emptyList();
    private Page nextPage;

    public AnimeVietsubKioskExtractor(final StreamingService streamingService,
                                      final ListLinkHandler linkHandler,
                                      final String kioskId,
                                      final AnimeVietsubClient client) {
        super(streamingService, linkHandler, kioskId);
        this.client = client;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        fetchPageUrl(downloader, getUrl());
    }

    private void fetchPageUrl(final Downloader downloader, final String rawUrl)
            throws IOException, ExtractionException {
        baseUrl = client.resolveBaseUrl(downloader);
        final String targetUrl = client.normalizeSiteUrl(rawUrl, baseUrl);
        doc = client.fetchWithBypass(downloader, targetUrl, baseUrl);
        items = client.parseMovieListItems(doc, baseUrl);

        nextPage = null;
        if (!targetUrl.contains("bang-xep-hang")) {
            final Element nextEl = doc.selectFirst("div.wp-pagenavi a.next, div.wp-pagenavi span.current + a");
            if (nextEl != null) {
                final String nextHref = AnimeVietsubClient.fixUrl(nextEl.attr("href"), baseUrl);
                if (nextHref != null && !nextHref.isEmpty()) {
                    nextPage = new Page(nextHref);
                }
            }
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        final String id = getId();
        if (AnimeVietsubKioskLinkHandlerFactory.KIOSK_HOT_DAY.equals(id)) {
            return "Xem Nhiều Trong Ngày";
        }
        if (AnimeVietsubKioskLinkHandlerFactory.KIOSK_UPCOMING.equals(id)) {
            return "Anime Sắp Chiếu";
        }
        return "Mới Cập Nhật";
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (final SearchItem item : items) {
            final String directWatchUrl = AnimeVietsubClient.stripTrailingSlash(item.url) + "/xem-phim.html";
            collector.commit(new StreamInfoItemExtractor() {
                @Override
                public StreamType getStreamType() {
                    return StreamType.VIDEO_STREAM;
                }

                @Override
                public long getDuration() {
                    return -1;
                }

                @Override
                public long getViewCount() {
                    return -1;
                }

                @Override
                public String getUploaderName() {
                    return item.episodeText.isEmpty() ? "AnimeVietsub" : item.episodeText;
                }

                @Override
                public String getUploaderUrl() {
                    return item.url;
                }

                @Nullable
                @Override
                public String getTextualUploadDate() {
                    return item.episodeText;
                }

                @Nullable
                @Override
                public DateWrapper getUploadDate() {
                    return null;
                }

                @Override
                public String getName() {
                    return item.episodeText.isEmpty()
                            ? item.title
                            : item.title + " [" + item.episodeText + "]";
                }

                @Override
                public String getUrl() {
                    return directWatchUrl;
                }

                @Override
                public String getThumbnailUrl() {
                    return item.posterUrl;
                }
            });
        }
        return new InfoItemsPage<>(collector, nextPage);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        fetchPageUrl(getDownloader(), page.getUrl());
        return getInitialPage();
    }
}
