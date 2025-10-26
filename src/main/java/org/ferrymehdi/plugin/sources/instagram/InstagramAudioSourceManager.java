package org.ferrymehdi.plugin.sources.instagram;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.*;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class InstagramAudioSourceManager implements AudioSourceManager, HttpConfigurable {

    private static final Logger log = LoggerFactory.getLogger(InstagramAudioSourceManager.class);

    private static final String SOURCE_NAME = "instagram";
    private static final String INSTAGRAM_DOMAIN = "instagram.com";
    private static final String URL_REGEX = "^(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|reel)/([a-zA-Z0-9_-]+)/?.*$";
    private static final Pattern INSTAGRAM_URL_PATTERN = Pattern.compile(URL_REGEX);
    private static final String SEARCH_PREFIX_REGEX = "^(issearch|igsearch):.*";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Safari/605.1.15"
    );
    private static final Random random = new Random();

    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";
    private static final String ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";

    private static final Pattern SHARED_DATA_SCRIPT_PATTERN = Pattern.compile("<script type=\"text/javascript\">window\\._sharedData\\s?=\\s?(\\{.*?});</script>", Pattern.DOTALL);
    private static final Pattern ADDITIONAL_DATA_SCRIPT_PATTERN = Pattern.compile("<script type=\"text/javascript\">window\\.__additionalDataLoaded\\('.*?',(\\{.*?})\\);</script>", Pattern.DOTALL);

    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);
    private static final long CACHE_MAX_SIZE = 200;

    private final HttpInterfaceManager httpInterfaceManager;
    private final ObjectMapper objectMapper;
    private final Cache<String, AudioItem> trackCache;

    public InstagramAudioSourceManager() {
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
        this.objectMapper = new ObjectMapper();
        this.trackCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_DURATION)
                .build();
        log.info("MYTllc Enhanced InstagramAudioSourceManager (v1.2.0) initialized. Cache enabled ({}m TTL, {} max size).", CACHE_DURATION.toMinutes(), CACHE_MAX_SIZE); // Updated Log Message
    }


    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (reference.identifier == null || !(reference.identifier.contains(INSTAGRAM_DOMAIN) || reference.identifier.matches(SEARCH_PREFIX_REGEX))) {
             return null; // Not an IG URL or search query we handle
        }

        if (reference.identifier.matches(SEARCH_PREFIX_REGEX)) {
             log.warn("Instagram search ({}) is not supported by MYTllc plugin. Only direct post/reel URLs work.", reference.identifier); // Updated Log Message
             return AudioReference.NO_TRACK;
        }

        Matcher urlMatcher = INSTAGRAM_URL_PATTERN.matcher(reference.identifier);
        if (!urlMatcher.matches()) {
            log.debug("Identifier {} did not match Instagram URL pattern.", reference.identifier);
            return null;
        }

        String postUrl = reference.identifier.split("\\?")[0];

        AudioItem cachedItem = trackCache.getIfPresent(postUrl);
        if (cachedItem != null) {
             log.debug("Cache hit for Instagram URL: {}", postUrl);
             if (cachedItem instanceof AudioTrack) {
                 return ((AudioTrack) cachedItem).makeClone();
             }
             return cachedItem;
        }
        log.debug("Cache miss for Instagram URL: {}", postUrl);

        try {
            AudioItem loadedItem = loadTrackFromUrl(postUrl);
            if (loadedItem != null && !(loadedItem instanceof BasicAudioPlaylist && ((BasicAudioPlaylist)loadedItem).isSearchResult())) {
                trackCache.put(postUrl, loadedItem);
            }
            return loadedItem;
        } catch (IOException e) {
            log.error("Network error loading Instagram track (MYTllc): {}", postUrl, e); // Updated Log Message
            throw new FriendlyException("Failed to retrieve Instagram video details due to network issue.", FriendlyException.Severity.SUSPICIOUS, e);
        } catch (ScrapingException e) {
            log.warn("Scraping failed for Instagram track (MYTllc): {} - Reason: {}", postUrl, e.getMessage()); // Updated Log Message
             trackCache.put(postUrl, AudioReference.NO_TRACK);
            throw new FriendlyException("Could not extract video information. Instagram's structure may have changed.", FriendlyException.Severity.SUSPICIOUS, e);
        } catch (Exception e) {
            log.error("Unexpected error loading Instagram track (MYTllc): {}", postUrl, e); // Updated Log Message
            throw new FriendlyException("An unexpected error occurred while loading the Instagram video.", FriendlyException.Severity.FAULT, e);
        }
    }

    private AudioItem loadTrackFromUrl(String postUrl) throws IOException, ScrapingException {
        String userAgent = USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));
        log.debug("Fetching {} using User-Agent: {}", postUrl, userAgent);

        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            HttpGet request = new HttpGet(postUrl);
            request.setHeader("User-Agent", userAgent);
            request.setHeader("Accept", ACCEPT_HEADER);
            request.setHeader("Accept-Language", ACCEPT_LANGUAGE);

            try (CloseableHttpResponse response = httpInterface.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String reason = response.getStatusLine().getReasonPhrase();

                 if (!HttpUtils.isSuccess(statusCode)) {
                     HttpUtils.consumeResponse(response);
                     log.warn("Instagram request failed (MYTllc): {} {} for URL: {}", statusCode, reason, postUrl); // Updated Log Message
                      if (statusCode == 404) return AudioReference.NO_TRACK;
                      if (statusCode == 429) throw new FriendlyException("Instagram rate limit exceeded. Try again later.", FriendlyException.Severity.COMMON, null);
                      if (statusCode == 403) throw new FriendlyException("Access denied by Instagram (403). May require login or different approach.", FriendlyException.Severity.SUSPICIOUS, null);
                      throw new FriendlyException("Instagram rejected the request: " + statusCode + " " + reason, FriendlyException.Severity.SUSPICIOUS, null);
                 }

                String htmlContent = HttpUtils.extractContent(response, StandardCharsets.UTF_8);
                HttpUtils.consumeResponse(response);

                 if (htmlContent == null || htmlContent.isEmpty()) {
                     throw new ScrapingException("Received empty page content from " + postUrl);
                 }

                return parseHtmlAndExtractTrack(htmlContent, postUrl);

            }
        }
    }

    private AudioItem parseHtmlAndExtractTrack(String htmlContent, String postUrl) throws ScrapingException {
        VideoDetails details = extractVideoDetails(htmlContent, postUrl);

        if (details.videoUrl == null || details.videoUrl.isEmpty()) {
            throw new ScrapingException("Could not find a valid video URL after attempting all parsing methods for " + postUrl);
        }

        try {
            new URI(details.videoUrl);
        } catch (URISyntaxException e) {
            throw new ScrapingException("Extracted video URL is malformed: " + details.videoUrl, e);
        }

        // AudioTrackInfo trackInfo = new AudioTrackInfo(
        //     details.title,
        //     details.author,
        //     details.durationMillis,
        //     postUrl,
        //     details.isStream,
        //     postUrl,
        //     details.thumbnailUrl
        // );
        AudioTrackInfo trackInfo = new AudioTrackInfo(
            details.title,
            details.author,
            details.durationMillis,
            postUrl,
            details.isStream,
            details.videoUrl,
            details.thumbnailUrl,
             null);
        log.info("Successfully extracted Instagram track via {} (MYTllc): Title='{}', Author='{}', URL='{}'", details.extractionMethod, details.title, details.author, details.videoUrl); // Updated Log Message
        return new InstagramAudioTrack(trackInfo, this, details.videoUrl);
    }


    private VideoDetails extractVideoDetails(String htmlContent, String originalUrl) {
        VideoDetails details = new VideoDetails();
        details.durationMillis = 0;
        details.isStream = true;
        details.extractionMethod = "Unknown";

        if (tryExtractFromJson(htmlContent, details)) {
             details.extractionMethod = "JSON";
             if (details.isComplete()) return details;
             log.debug("Partial data found via JSON for {}", originalUrl);
        } else {
            log.debug("JSON extraction failed or yielded no useful data for {}", originalUrl);
        }


        if (tryExtractWithJsoup(htmlContent, details)) {
             if (details.extractionMethod.equals("Unknown")) details.extractionMethod = "JSoup";
             if (details.isComplete()) return details;
             log.debug("Partial data found via JSoup for {}", originalUrl);
        } else {
            log.debug("JSoup extraction failed or yielded no new useful data for {}", originalUrl);
        }

        if (details.extractionMethod.equals("Unknown")) {
             log.warn("Falling back to basic regex matching (MYTllc) for {}, JSON/Jsoup methods failed.", originalUrl); // Updated Log Message
             if(tryExtractWithRegex(htmlContent, details)){
                 details.extractionMethod = "Regex";
             }
        }

        if (details.title == null || details.title.isEmpty()) details.title = "Instagram Video";
        if (details.author == null || details.author.isEmpty()) details.author = "Unknown Artist";

        return details;
    }

    // --- JSON Parsing Methods ---
    // ... (Keep methods: tryExtractFromJson, findJson, findJsonLd, parseSharedData, parseAdditionalData, parseJsonLd) ...
     private boolean tryExtractFromJson(String htmlContent, VideoDetails details) {
        boolean foundData = false;
        try {
            String sharedDataJson = findJson(htmlContent, SHARED_DATA_SCRIPT_PATTERN);
            if (sharedDataJson != null) {
                parseSharedData(sharedDataJson, details);
                if (details.isPartiallyComplete()) foundData = true;
                if (details.isComplete()) return true;
            }

            String additionalDataJson = findJson(htmlContent, ADDITIONAL_DATA_SCRIPT_PATTERN);
            if (additionalDataJson != null) {
                parseAdditionalData(additionalDataJson, details);
                 if (details.isPartiallyComplete()) foundData = true;
                 if (details.isComplete()) return true;
            }

             String ldJson = findJsonLd(htmlContent);
             if (ldJson != null) {
                 parseJsonLd(ldJson, details);
                  if (details.isPartiallyComplete()) foundData = true;
                  if (details.isComplete()) return true;
             }

        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON data from Instagram page", e);
        } catch (Exception e) {
             log.warn("Error during JSON extraction", e);
        }
        return foundData;
    }

     private String findJson(String htmlContent, Pattern pattern) {
         Matcher matcher = pattern.matcher(htmlContent);
         if (matcher.find()) {
             return matcher.group(1);
         }
         return null;
     }

    private String findJsonLd(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            Elements scripts = doc.select("script[type=application/ld+json]");
            for (Element script : scripts) {
                String scriptContent = script.html();
                 if (scriptContent.contains("\"@type\":\"VideoObject\"") || scriptContent.contains("video_url") || scriptContent.contains("contentUrl")) {
                    return scriptContent;
                }
            }
        } catch (Exception e) {
            log.debug("Error searching for JSON-LD script", e);
        }
        return null;
    }

    private void parseSharedData(String jsonString, VideoDetails details) throws JsonProcessingException {
         JsonNode root = objectMapper.readTree(jsonString);
         JsonNode postPage = traverseSafe(root, "entry_data", "PostPage");
         if (postPage == null || !postPage.isArray() || postPage.isEmpty()) return;

         JsonNode media = traverseSafe(postPage.get(0), "graphql", "shortcode_media");
         if (media == null) media = traverseSafe(postPage.get(0), "media"); // Fallback structure

         if (media == null) return;

         if (details.videoUrl == null) details.videoUrl = getTextSafe(media, "video_url");
         if (details.thumbnailUrl == null) details.thumbnailUrl = getTextSafe(media, "display_url");
         if (details.title == null) details.title = extractTitleFromCaption(getTextSafe(traverseSafe(media, "edge_media_to_caption", "edges", "0", "node"), "text"));
         if (details.author == null) details.author = getTextSafe(traverseSafe(media, "owner"), "username");

         JsonNode dimensions = traverseSafe(media, "dimensions");
         if (dimensions != null && details.title == null) details.title = "Instagram Video"; // Placeholder if no caption

         Double duration = getDoubleSafe(media, "video_duration");
         if (duration != null && details.durationMillis == 0) {
             details.durationMillis = (long) (duration * 1000.0);
             details.isStream = false;
         }
    }

     private void parseAdditionalData(String jsonString, VideoDetails details) throws JsonProcessingException {
         JsonNode root = objectMapper.readTree(jsonString);
         JsonNode media = traverseSafe(root, "graphql", "shortcode_media");
          if (media == null) media = traverseSafe(root, "shortcode_media"); // Alternative structure

         if (media == null) return;

         if (details.videoUrl == null) details.videoUrl = getTextSafe(media, "video_url");
         if (details.thumbnailUrl == null) details.thumbnailUrl = getTextSafe(media, "display_url");
         if (details.title == null) details.title = extractTitleFromCaption(getTextSafe(traverseSafe(media, "edge_media_to_caption", "edges", "0", "node"), "text"));
         if (details.author == null) details.author = getTextSafe(traverseSafe(media, "owner"), "username");

         Double duration = getDoubleSafe(media, "video_duration");
         if (duration != null && details.durationMillis == 0) {
             details.durationMillis = (long) (duration * 1000.0);
             details.isStream = false;
         }
     }

     private void parseJsonLd(String jsonString, VideoDetails details) throws JsonProcessingException {
         JsonNode root = objectMapper.readTree(jsonString);
         if (!"VideoObject".equals(getTextSafe(root, "@type"))) return;

         if (details.videoUrl == null) details.videoUrl = getTextSafe(root, "contentUrl");
        // if (details.thumbnailUrl == null) details.thumbnailUrl = getTextSafe(traverseSafe(root, "thumbnailUrl"), (String[]) null);
         if (details.thumbnailUrl == null) details.thumbnailUrl = getTextSafe(root, "thumbnailUrl");

         if (details.title == null) details.title = getTextSafe(root, "name");
          if (details.title == null) details.title = extractTitleFromCaption(getTextSafe(root,"caption"));

         if (details.author == null) details.author = getTextSafe(traverseSafe(root, "author"), "name");

         String durationStr = getTextSafe(root, "duration");
         if (durationStr != null && details.durationMillis == 0) {
             try {
                 details.durationMillis = javax.xml.datatype.DatatypeFactory.newInstance().newDuration(durationStr).getTimeInMillis(new java.util.Date(0));
                 details.isStream = false;
             } catch (Exception e) {
                 log.debug("Failed to parse ISO 8601 duration from JSON-LD: {}", durationStr, e);
             }
         }
     }

    // --- JSoup Parsing Methods ---
    // ... (Keep methods: tryExtractWithJsoup, extractMetaContent, extractAttribute, extractAuthorFromOgTitle) ...
    private boolean tryExtractWithJsoup(String htmlContent, VideoDetails details) {
        boolean foundData = false;
        try {
            Document doc = Jsoup.parse(htmlContent);

            String videoUrl = null;
            if (details.videoUrl == null) videoUrl = extractMetaContent(doc, "og:video", "og:video:secure_url");
            if (videoUrl == null && details.videoUrl == null) videoUrl = extractAttribute(doc.select("video").first(), "src");
            if (videoUrl != null) { details.videoUrl = videoUrl; foundData = true; }


            String title = null;
            if (details.title == null) title = extractMetaContent(doc, "og:title");
            if(title != null) { details.title = title; foundData = true; }

            String thumb = null;
            if (details.thumbnailUrl == null) thumb = extractMetaContent(doc, "og:image", "og:image:secure_url");
            if (thumb != null) { details.thumbnailUrl = thumb; foundData = true; }


            String author = null;
            if (details.author == null) author = extractAuthorFromOgTitle(details.title);
             if (author == null && details.author == null) author = extractMetaContent(doc, "og:description");
            if (author != null) { details.author = author; foundData = true; }


            String durationStr = extractMetaContent(doc, "og:video:duration", "video:duration");
            if(durationStr != null && details.durationMillis == 0){
                try{
                    details.durationMillis = Long.parseLong(durationStr) * 1000;
                    details.isStream = false;
                    foundData = true;
                } catch(NumberFormatException e){
                    log.debug("Failed to parse duration from meta tag via JSoup: {}", durationStr);
                }
            }

            return foundData;

        } catch (Exception e) {
            log.warn("Error during JSoup parsing", e);
            return false;
        }
    }

     private String extractMetaContent(Document doc, String... propertyNames) {
         for (String propertyName : propertyNames) {
             Element meta = doc.selectFirst("meta[property=" + propertyName + "]");
             if (meta != null) {
                 String content = meta.attr("content");
                 if (content != null && !content.trim().isEmpty()) {
                     return content.trim();
                 }
             }
              meta = doc.selectFirst("meta[name=" + propertyName + "]");
             if (meta != null) {
                 String content = meta.attr("content");
                 if (content != null && !content.trim().isEmpty()) {
                     return content.trim();
                 }
             }
         }
         return null;
     }

     private String extractAttribute(Element element, String attributeKey) {
         if (element != null) {
             String attr = element.attr(attributeKey);
             return (attr != null && !attr.trim().isEmpty()) ? attr.trim() : null;
         }
         return null;
     }

     private String extractAuthorFromOgTitle(String ogTitle) {
        if (ogTitle == null || ogTitle.isEmpty()) return null;
        Matcher matcher = Pattern.compile("^(.+?)(?:\\s+on Instagram:.*|\\s*\\(@[^)]+\\))").matcher(ogTitle);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (!ogTitle.contains(" ") && ogTitle.length() < 30) {
             return ogTitle;
        }
        return null;
    }


    // --- Regex Parsing Methods ---
    // ... (Keep methods: tryExtractWithRegex, findMatch) ...
     private boolean tryExtractWithRegex(String htmlContent, VideoDetails details) {
        boolean foundData = false;
        String temp;

        if (details.videoUrl == null) {
            temp = findMatch(Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\""), htmlContent);
            if (temp != null) { details.videoUrl = temp; foundData = true;}
        }
        if (details.videoUrl == null) {
             temp = findMatch(Pattern.compile("<meta\\s+property=\"og:video(?:.*?)\"\\s+content=\"([^\"]+)\""), htmlContent);
             if (temp != null) { details.videoUrl = temp; foundData = true;}
        }
         if (details.videoUrl == null) {
            temp = findMatch(Pattern.compile("<video.*?src=\"([^\"]+)\".*?>"), htmlContent);
            if (temp != null) { details.videoUrl = temp; foundData = true;}
        }


        if (details.title == null) {
            temp = findMatch(Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\""), htmlContent);
            if (temp != null) { details.title = temp; foundData = true;}
        }

        if (details.author == null && details.title != null) {
             temp = extractAuthorFromOgTitle(details.title);
             if (temp != null) { details.author = temp; foundData = true;}
        }
         if (details.author == null) {
             temp = findMatch(Pattern.compile("\"owner\"\\s*:\\s*\\{\\s*\"username\"\\s*:\\s*\"([^\"]+)\""), htmlContent);
             if (temp != null) { details.author = temp; foundData = true;}
         }

        if (details.thumbnailUrl == null) {
             temp = findMatch(Pattern.compile("<meta\\s+property=\"og:image(?:.*?)\"\\s+content=\"([^\"]+)\""), htmlContent);
             if (temp != null) { details.thumbnailUrl = temp; foundData = true;}
        }
        if (details.thumbnailUrl == null) {
             temp = findMatch(Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\""), htmlContent);
             if (temp != null) { details.thumbnailUrl = temp; foundData = true;}
        }

         if(details.durationMillis == 0) {
            String durationStr = findMatch(Pattern.compile("\"video_duration\"\\s*:\\s*([\\d\\.]+)\\s*[,}]"), htmlContent);
            if(durationStr != null) {
                try {
                     details.durationMillis = (long) (Double.parseDouble(durationStr) * 1000.0);
                     details.isStream = false;
                     foundData = true;
                } catch (NumberFormatException e){
                    log.debug("Regex failed to parse duration: {}", durationStr);
                }
            }
        }
        return foundData;
    }

     private String findMatch(Pattern pattern, String content) {
         Matcher matcher = pattern.matcher(content);
         if (matcher.find()) {
              String match = matcher.groupCount() >= 1 ? matcher.group(1) : null;
              return (match != null) ? match.replace("\\/", "/") : null;
         }
         return null;
     }

    // --- Utility Methods ---
    // ... (Keep methods: extractTitleFromCaption, isTrackEncodable, encodeTrack, decodeTrack, shutdown, configureRequests, configureBuilder) ...
     private String extractTitleFromCaption(String caption) {
        if (caption == null || caption.isEmpty()) return null;
        String[] lines = caption.split("\\r?\\n", 2);
        String firstLine = lines[0].trim();
        return firstLine.length() > 150 ? firstLine.substring(0, 147) + "..." : firstLine;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        InstagramAudioTrack igTrack = (InstagramAudioTrack) track;
        output.writeUTF(igTrack.getStreamUrl());
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        String streamUrl = input.readUTF();
        return new InstagramAudioTrack(trackInfo, this, streamUrl);
    }

    @Override
    public void shutdown() {
        log.info("Shutting down MYTllc Enhanced InstagramAudioSourceManager."); // Updated Log Message
        trackCache.invalidateAll();
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }

    // --- Helper Classes ---
    // ... (Keep classes: VideoDetails, InstagramAudioTrack, ScrapingException, HttpUtils, JSON Helpers) ...
    private static class VideoDetails {
        String videoUrl;
        String title;
        String author;
        String thumbnailUrl;
        long durationMillis = 0;
        boolean isStream = true;
        String extractionMethod = "Unknown";

        boolean isComplete() {
            return videoUrl != null && !videoUrl.isEmpty()
                && title != null && !title.isEmpty()
                && author != null && !author.isEmpty()
                && thumbnailUrl != null && !thumbnailUrl.isEmpty();
        }

         boolean isPartiallyComplete() {
            return (videoUrl != null && !videoUrl.isEmpty())
                || (title != null && !title.isEmpty())
                || (author != null && !author.isEmpty())
                || (thumbnailUrl != null && !thumbnailUrl.isEmpty());
        }
    }



    private static class ScrapingException extends Exception {
        public ScrapingException(String message) {
            super(message);
        }
        public ScrapingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class HttpUtils {
         public static boolean isSuccess(int code) {
             return code >= 200 && code < 300;
         }

         public static void consumeResponse(CloseableHttpResponse response) {
             if(response != null && response.getEntity() != null) {
                 EntityUtils.consumeQuietly(response.getEntity());
             }
         }

         public static String extractContent(CloseableHttpResponse response, java.nio.charset.Charset charset) throws IOException {
            if (response == null || response.getEntity() == null) return null;
            return EntityUtils.toString(response.getEntity(), charset);
         }
    }

    private static JsonNode traverseSafe(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) {
            if (current == null || current.isMissingNode()) return null;
            if (current.isArray()) {
                try {
                    int index = Integer.parseInt(field);
                    if (index < 0 || index >= current.size()) return null;
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (current.isObject()) {
                current = current.get(field);
            } else {
                return null;
            }
        }
        return (current == null || current.isMissingNode() || current.isNull()) ? null : current;
    }

    private static String getTextSafe(JsonNode node, String... fields) {
         JsonNode target = traverseSafe(node, fields);
         return (target != null && target.isTextual()) ? target.asText() : null;
     }

    private static Double getDoubleSafe(JsonNode node, String... fields) {
         JsonNode target = traverseSafe(node, fields);
         return (target != null && target.isNumber()) ? target.asDouble() : null;
     }

     private static Stream<JsonNode> streamJsonArray(JsonNode node){
         return (node != null && node.isArray()) ? StreamSupport.stream(node.spliterator(), false) : Stream.empty();
     }

     public HttpInterface getHttpInterface() {
        return this.httpInterfaceManager.getInterface();
     }
}
