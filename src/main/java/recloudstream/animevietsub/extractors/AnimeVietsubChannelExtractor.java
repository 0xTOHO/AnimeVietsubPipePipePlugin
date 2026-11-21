package recloudstream.animevietsub.extractors;

import org.jsoup.nodes.Document;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
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
import recloudstream.animevietsub.AnimeVietsubClient.EpisodeInfo;

public final class AnimeVietsubChannelExtractor extends ChannelExtractor {

    private final AnimeVietsubClient client;
    private String baseUrl;
    private String showUrl;
    private String showTitle = "AnimeVietsub";
    private String posterUrl = "";
    private String bannerUrl = "";
    private String description = "";
    private List<EpisodeInfo> episodes = Collections.emptyList();

    public AnimeVietsubChannelExtractor(final StreamingService service,
                                        final ListLinkHandler linkHandler,
                                        final AnimeVietsubClient client) {
        super(service, linkHandler);
        this.client = client;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        baseUrl = client.resolveBaseUrl(downloader);
        final String normalized = client.normalizeSiteUrl(getUrl(), baseUrl);
        showUrl = normalized.replace("/xem-phim.html", "");
        if (!showUrl.endsWith("/")) {
            showUrl = showUrl + "/";
        }
        final String watchUrl = AnimeVietsubClient.stripTrailingSlash(showUrl) + "/xem-phim.html";

        final Document infoDoc = client.fetchWithBypass(downloader, showUrl, baseUrl);
        showTitle = AnimeVietsubClient.extractTitle(infoDoc);
        posterUrl = AnimeVietsubClient.extractPosterUrl(infoDoc, baseUrl);
        bannerUrl = AnimeVietsubClient.extractBannerUrl(infoDoc, baseUrl, posterUrl);
        description = AnimeVietsubClient.extractDescription(infoDoc);

        try {
            final Document watchDoc = client.fetchWithBypass(downloader, watchUrl, showUrl);
            episodes = AnimeVietsubClient.parseEpisodes(watchDoc, baseUrl);
        } catch (final Exception ignored) {
            episodes = AnimeVietsubClient.parseEpisodes(infoDoc, baseUrl);
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return showTitle;
    }

    @Override
    public String getAvatarUrl() throws ParsingException {
        return posterUrl;
    }

    @Override
    public String getBannerUrl() throws ParsingException {
        return bannerUrl;
    }

    @Override
    public String getDescription() throws ParsingException {
        return description;
    }

    @Override
    public long getSubscriberCount() throws ParsingException {
        return episodes.size();
    }

    @Override
    public boolean isVerified() throws ParsingException {
        return true;
    }

    @Nonnull
    @Override
    public List<ListLinkHandler> getTabs() throws ParsingException {
        return Collections.singletonList(new ListLinkHandler(
                showUrl,
                showUrl,
                showUrl,
                Collections.singletonList(new FilterItem(
                        Filter.ITEM_IDENTIFIER_UNKNOWN,
                        ChannelTabs.VIDEOS
                )),
                null
        ));
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (final EpisodeInfo ep : episodes) {
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
                    return showTitle;
                }

                @Override
                public String getUploaderUrl() {
                    return showUrl;
                }

                @Nullable
                @Override
                public String getTextualUploadDate() {
                    return null;
                }

                @Nullable
                @Override
                public DateWrapper getUploadDate() {
                    return null;
                }

                @Override
                public String getName() {
                    return showTitle + " - " + ep.name;
                }

                @Override
                public String getUrl() {
                    return ep.url;
                }

                @Override
                public String getThumbnailUrl() {
                    return posterUrl;
                }
            });
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return getInitialPage();
    }
}
