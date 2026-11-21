package recloudstream.animevietsub.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemExtractor;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import recloudstream.animevietsub.AnimeVietsubClient;
import recloudstream.animevietsub.AnimeVietsubClient.EpisodeInfo;
import recloudstream.animevietsub.AnimeVietsubClient.ResolvedStream;

public final class AnimeVietsubStreamExtractor extends StreamExtractor {

    private final AnimeVietsubClient client;
    private Document watchDoc;
    private Document infoDoc;
    private String baseUrl;
    private String showUrl;
    private String showTitle = "AnimeVietsub";
    private String episodeTitle = "";
    private String posterUrl = "";
    private String descriptionText = "";
    private List<EpisodeInfo> episodes = Collections.emptyList();
    private List<ResolvedStream> resolvedStreams = Collections.emptyList();

    public AnimeVietsubStreamExtractor(final StreamingService service,
                                       final LinkHandler linkHandler,
                                       final AnimeVietsubClient client) {
        super(service, linkHandler);
        this.client = client;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        baseUrl = client.resolveBaseUrl(downloader);
        final String targetUrl = client.normalizeSiteUrl(getUrl(), baseUrl);
        watchDoc = client.fetchWithBypass(downloader, targetUrl, baseUrl);

        showUrl = deriveShowUrl(targetUrl);
        try {
            infoDoc = client.fetchWithBypass(downloader, showUrl, baseUrl);
        } catch (final Exception ignored) {
            infoDoc = watchDoc;
        }

        final Document metaDoc = infoDoc != null ? infoDoc : watchDoc;
        showTitle = AnimeVietsubClient.extractTitle(metaDoc);
        posterUrl = AnimeVietsubClient.extractPosterUrl(metaDoc, baseUrl);
        descriptionText = AnimeVietsubClient.extractDescription(metaDoc);

        final Element activeEp = watchDoc.selectFirst("a.btn-episode.active, a.episode-link.active, .list-episode a.active");
        if (activeEp != null) {
            String epName = activeEp.attr("title").trim();
            if (epName.isEmpty()) {
                epName = activeEp.text().trim();
            }
            episodeTitle = epName;
        }

        episodes = AnimeVietsubClient.parseEpisodes(watchDoc, baseUrl);
        resolvedStreams = client.resolveStreamsForWatchUrl(downloader, targetUrl);
    }

    private static String deriveShowUrl(final String episodeUrl) {
        final int phimIdx = episodeUrl.indexOf("/phim/");
        if (phimIdx >= 0) {
            final int afterPhim = phimIdx + "/phim/".length();
            final int nextSlash = episodeUrl.indexOf('/', afterPhim);
            if (nextSlash > 0) {
                return episodeUrl.substring(0, nextSlash + 1);
            }
        }
        return episodeUrl.replace("/xem-phim.html", "/");
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        if (episodeTitle != null && !episodeTitle.isEmpty()) {
            return showTitle + " - " + episodeTitle;
        }
        return showTitle;
    }

    @Nonnull
    @Override
    public String getThumbnailUrl() throws ParsingException {
        return posterUrl != null ? posterUrl : "";
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        return showUrl != null ? showUrl : baseUrl;
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        return showTitle != null ? showTitle : "AnimeVietsub";
    }

    @Nonnull
    @Override
    public String getUploaderAvatarUrl() throws ParsingException {
        return posterUrl != null ? posterUrl : "";
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        return new Description(descriptionText, Description.PLAIN_TEXT);
    }

    @Override
    public StreamType getStreamType() throws ParsingException {
        return StreamType.VIDEO_STREAM;
    }

    @Nonnull
    @Override
    public String getHlsUrl() throws ParsingException {
        for (final ResolvedStream rs : resolvedStreams) {
            if (rs.isHls) {
                return rs.url;
            }
        }
        return "";
    }

    @Override
    public List<AudioStream> getAudioStreams() throws IOException, ExtractionException {
        return Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoStreams() throws IOException, ExtractionException {
        final List<VideoStream> list = new ArrayList<>();
        for (final ResolvedStream rs : resolvedStreams) {
            list.add(new VideoStream.Builder()
                    .setId(rs.serverName != null ? rs.serverName : VideoStream.ID_UNKNOWN)
                    .setContent(rs.url, true)
                    .setIsVideoOnly(false)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setResolution(rs.resolution != null ? rs.resolution : "720p")
                    .setDeliveryMethod(rs.isHls ? DeliveryMethod.HLS : DeliveryMethod.PROGRESSIVE_HTTP)
                    .build());
        }
        return list;
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() throws IOException, ExtractionException {
        return Collections.emptyList();
    }

    @Nullable
    @Override
    public InfoItemsCollector<? extends InfoItem, ? extends InfoItemExtractor> getRelatedItems()
            throws IOException, ExtractionException {
        final List<StreamInfoItem> items = new ArrayList<>();
        for (final EpisodeInfo ep : episodes) {
            final StreamInfoItem item = new StreamInfoItem(
                    getServiceId(),
                    ep.url,
                    showTitle + " - " + ep.name,
                    StreamType.VIDEO_STREAM
            );
            item.setUploaderName(showTitle);
            item.setUploaderUrl(showUrl);
            item.setThumbnailUrl(posterUrl);
            items.add(item);
        }
        return new StreamInfoItemsCollector(getServiceId()) {
            @Override
            public List<StreamInfoItem> getItems() {
                return items;
            }
        };
    }
}
