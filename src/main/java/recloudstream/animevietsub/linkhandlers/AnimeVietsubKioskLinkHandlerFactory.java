package recloudstream.animevietsub.linkhandlers;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;
import java.util.Locale;

import recloudstream.animevietsub.AnimeVietsubClient;

public final class AnimeVietsubKioskLinkHandlerFactory extends ListLinkHandlerFactory {

    public static final String KIOSK_NEW = "anime-moi";
    public static final String KIOSK_HOT_DAY = "bxh-ngay";
    public static final String KIOSK_UPCOMING = "anime-sap-chieu";

    private final AnimeVietsubClient client;

    public AnimeVietsubKioskLinkHandlerFactory(final AnimeVietsubClient client) {
        this.client = client;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        if (url == null) {
            return KIOSK_NEW;
        }
        final String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("bang-xep-hang")) {
            return KIOSK_HOT_DAY;
        }
        if (lower.contains("anime-sap-chieu")) {
            return KIOSK_UPCOMING;
        }
        return KIOSK_NEW;
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        final String base = AnimeVietsubClient.FALLBACK_DOMAIN;
        if (KIOSK_HOT_DAY.equals(id)) {
            return base + "/bang-xep-hang/day.html";
        }
        if (KIOSK_UPCOMING.equals(id)) {
            return base + "/anime-sap-chieu/";
        }
        return base + "/anime-moi/";
    }

    @Override
    public boolean onAcceptUrl(final String url) throws ParsingException {
        if (url == null) {
            return false;
        }
        final String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("/anime-moi")
                || lower.contains("/anime-sap-chieu")
                || lower.contains("/bang-xep-hang/");
    }
}
