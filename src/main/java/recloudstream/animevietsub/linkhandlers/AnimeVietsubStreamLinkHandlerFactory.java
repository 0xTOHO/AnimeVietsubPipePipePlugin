package recloudstream.animevietsub.linkhandlers;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;

import java.util.Locale;

public final class AnimeVietsubStreamLinkHandlerFactory extends LinkHandlerFactory {

    public static boolean isEpisodeUrl(final String url) {
        if (url == null) {
            return false;
        }
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("animevietsub") && !lower.contains("/phim/")) {
            return false;
        }
        return lower.matches(".*/phim/[^/]+/(tap-[^/]+\\.html|xem-phim\\.html).*");
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return url;
    }

    @Override
    public String getUrl(final String id) throws ParsingException {
        return id;
    }

    @Override
    public boolean onAcceptUrl(final String url) throws ParsingException {
        return isEpisodeUrl(url);
    }
}
