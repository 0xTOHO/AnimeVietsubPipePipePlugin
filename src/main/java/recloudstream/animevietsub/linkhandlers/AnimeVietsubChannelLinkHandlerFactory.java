package recloudstream.animevietsub.linkhandlers;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;
import java.util.Locale;

public final class AnimeVietsubChannelLinkHandlerFactory extends ListLinkHandlerFactory {

    public static boolean isShowUrl(final String url) {
        if (url == null) {
            return false;
        }
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("animevietsub") && !lower.contains("/phim/")) {
            return false;
        }
        if (AnimeVietsubStreamLinkHandlerFactory.isEpisodeUrl(url)) {
            return false;
        }
        return lower.contains("/phim/");
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return url;
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return id;
    }

    @Override
    public boolean onAcceptUrl(final String url) throws ParsingException {
        return isShowUrl(url);
    }
}
