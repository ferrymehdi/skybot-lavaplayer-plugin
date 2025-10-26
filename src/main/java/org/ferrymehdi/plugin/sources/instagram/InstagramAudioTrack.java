package org.ferrymehdi.plugin.sources.instagram;

import java.util.Objects;
import java.net.URI;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

public class InstagramAudioTrack extends DelegatedAudioTrack {
    private final String streamUrl;
    private final InstagramAudioSourceManager sourceManager;

    public InstagramAudioTrack(AudioTrackInfo trackInfo, InstagramAudioSourceManager sourceManager, String streamUrl) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.streamUrl = Objects.requireNonNull(streamUrl, "Stream URL cannot be null for InstagramAudioTrack");
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    @Override
    public String getIdentifier() {
        return this.trackInfo.identifier;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        URI streamUri = new URI(this.streamUrl);
        try (var stream = new PersistentHttpStream(this.sourceManager.getHttpInterface(), streamUri, this.trackInfo.length)) {
            processDelegate(new Mp3AudioTrack(this.trackInfo, stream), localExecutor);
        }
    }

    @Override
    public AudioTrack makeClone() {
        return new InstagramAudioTrack(this.trackInfo, this.sourceManager, this.streamUrl);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}

