package org.ferrymehdi.plugin.sources.speech;

import org.ferrymehdi.plugin.AbstractFerryHttpSource;
import org.ferrymehdi.plugin.Mp3Track;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class SpeechAudioTrack extends Mp3Track {
    SpeechAudioTrack(AudioTrackInfo trackInfo, AbstractFerryHttpSource manager) {
        super(trackInfo, manager);
    }

    @Override
    public String getPlaybackUrl() {
        return this.trackInfo.uri;
    }

    @Override
    public AudioTrack makeShallowClone() {
        return new SpeechAudioTrack(trackInfo, getSourceManager());
    }
}
