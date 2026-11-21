package recloudstream.animevietsub;

import static java.util.Collections.singletonList;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.plugin.PluginPreferences;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import recloudstream.animevietsub.extractors.AnimeVietsubChannelExtractor;
import recloudstream.animevietsub.extractors.AnimeVietsubChannelTabExtractor;
import recloudstream.animevietsub.extractors.AnimeVietsubKioskExtractor;
import recloudstream.animevietsub.extractors.AnimeVietsubSearchExtractor;
import recloudstream.animevietsub.extractors.AnimeVietsubStreamExtractor;
import recloudstream.animevietsub.linkhandlers.AnimeVietsubChannelLinkHandlerFactory;
import recloudstream.animevietsub.linkhandlers.AnimeVietsubKioskLinkHandlerFactory;
import recloudstream.animevietsub.linkhandlers.AnimeVietsubSearchQueryHandlerFactory;
import recloudstream.animevietsub.linkhandlers.AnimeVietsubStreamLinkHandlerFactory;

public final class AnimeVietsubService extends StreamingService {

    private final AnimeVietsubClient client;

    public AnimeVietsubService(final int id, final PluginPreferences preferences) {
        super(id, "AnimeVietsub", singletonList(VIDEO));
        this.client = new AnimeVietsubClient(preferences);
    }

    @Override
    public String getBaseUrl() {
        return client.getConfiguredUrl();
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return new AnimeVietsubStreamLinkHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return new AnimeVietsubChannelLinkHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getChannelTabLHFactory() {
        return new AnimeVietsubChannelLinkHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getPlaylistLHFactory() {
        return null;
    }

    @Override
    public SearchQueryHandlerFactory getSearchQHFactory() {
        return new AnimeVietsubSearchQueryHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return null;
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler queryHandler) {
        return new AnimeVietsubSearchExtractor(this, queryHandler, client);
    }

    @Override
    public SuggestionExtractor getSuggestionExtractor() {
        return null;
    }

    @Override
    public SubscriptionExtractor getSubscriptionExtractor() {
        return null;
    }

    @Override
    public KioskList getKioskList() throws ExtractionException {
        final KioskList list = new KioskList(this);
        final AnimeVietsubKioskLinkHandlerFactory kioskHandler =
                new AnimeVietsubKioskLinkHandlerFactory(client);
        try {
            list.addKioskEntry(
                    (streamingService, url, kioskId) -> new AnimeVietsubKioskExtractor(
                            streamingService,
                            kioskHandler.fromUrl(url),
                            kioskId,
                            client
                    ),
                    kioskHandler,
                    AnimeVietsubKioskLinkHandlerFactory.KIOSK_NEW
            );
            list.addKioskEntry(
                    (streamingService, url, kioskId) -> new AnimeVietsubKioskExtractor(
                            streamingService,
                            kioskHandler.fromUrl(url),
                            kioskId,
                            client
                    ),
                    kioskHandler,
                    AnimeVietsubKioskLinkHandlerFactory.KIOSK_HOT_DAY
            );
            list.addKioskEntry(
                    (streamingService, url, kioskId) -> new AnimeVietsubKioskExtractor(
                            streamingService,
                            kioskHandler.fromUrl(url),
                            kioskId,
                            client
                    ),
                    kioskHandler,
                    AnimeVietsubKioskLinkHandlerFactory.KIOSK_UPCOMING
            );
            list.setDefaultKiosk(AnimeVietsubKioskLinkHandlerFactory.KIOSK_NEW);
        } catch (final Exception e) {
            throw new ExtractionException("Failed to initialize AnimeVietsub kiosks", e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler linkHandler)
            throws ExtractionException {
        return new AnimeVietsubChannelExtractor(this, linkHandler, client);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler linkHandler)
            throws ExtractionException {
        return new AnimeVietsubChannelTabExtractor(this, linkHandler, client);
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler linkHandler)
            throws ExtractionException {
        return null;
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler linkHandler)
            throws ExtractionException {
        return new AnimeVietsubStreamExtractor(this, linkHandler, client);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler linkHandler)
            throws ExtractionException {
        return null;
    }
}
