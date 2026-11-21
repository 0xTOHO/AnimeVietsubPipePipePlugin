package recloudstream.animevietsub.linkhandlers;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

import recloudstream.animevietsub.AnimeVietsubClient;

public final class AnimeVietsubSearchQueryHandlerFactory extends SearchQueryHandlerFactory {

    @Override
    public String getUrl(final String query,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return AnimeVietsubClient.FALLBACK_DOMAIN
                + "/tim-kiem/"
                + AnimeVietsubClient.encodeUri(query)
                + "/";
    }
}
