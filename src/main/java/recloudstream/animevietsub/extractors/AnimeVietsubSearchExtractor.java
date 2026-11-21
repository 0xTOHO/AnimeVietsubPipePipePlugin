package recloudstream.animevietsub.extractors;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import recloudstream.animevietsub.AnimeVietsubClient;
import recloudstream.animevietsub.AnimeVietsubClient.SearchItem;

public final class AnimeVietsubSearchExtractor extends SearchExtractor {

    private final AnimeVietsubClient client;
    private List<SearchItem> results = Collections.emptyList();

    public AnimeVietsubSearchExtractor(final StreamingService service,
                                       final SearchQueryHandler linkHandler,
                                       final AnimeVietsubClient client) {
        super(service, linkHandler);
        this.client = client;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        results = client.search(downloader, getSearchString());
    }

    @Override
    protected InfoItemsPage<InfoItem> getInitialPageInternal()
            throws IOException, ExtractionException {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        for (final SearchItem item : results) {
            collector.commit(new ChannelInfoItemExtractor() {
                @Override
                public String getDescription() {
                    return item.episodeText;
                }

                @Override
                public long getSubscriberCount() {
                    return -1;
                }

                @Override
                public long getStreamCount() {
                    return -1;
                }

                @Override
                public boolean isVerified() {
                    return false;
                }

                @Override
                public String getName() {
                    return item.episodeText.isEmpty()
                            ? item.title
                            : item.title + " (" + item.episodeText + ")";
                }

                @Override
                public String getUrl() {
                    return item.url;
                }

                @Override
                public String getThumbnailUrl() {
                    return item.posterUrl;
                }
            });

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
                    return item.title;
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
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    protected InfoItemsPage<InfoItem> getPageInternal(final Page page)
            throws IOException, ExtractionException {
        return getInitialPageInternal();
    }
}
