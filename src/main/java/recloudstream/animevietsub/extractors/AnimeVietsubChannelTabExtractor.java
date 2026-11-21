package recloudstream.animevietsub.extractors;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import recloudstream.animevietsub.AnimeVietsubClient;

public final class AnimeVietsubChannelTabExtractor extends ChannelTabExtractor {

    private final AnimeVietsubChannelExtractor channelExtractor;

    public AnimeVietsubChannelTabExtractor(final StreamingService service,
                                           final ListLinkHandler linkHandler,
                                           final AnimeVietsubClient client) {
        super(service, linkHandler);
        this.channelExtractor = new AnimeVietsubChannelExtractor(service, linkHandler, client);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        channelExtractor.onFetchPage(downloader);
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        final InfoItemsPage<StreamInfoItem> page = channelExtractor.getInitialPage();
        final List<InfoItem> items = new ArrayList<>(page.getItems());
        return new InfoItemsPage<>(items, page.getNextPage(), Collections.emptyList());
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return getInitialPage();
    }
}
