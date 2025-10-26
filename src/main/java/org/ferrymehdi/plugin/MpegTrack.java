package org.ferrymehdi.plugin;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public abstract class MpegTrack extends Mp3Track {
    public MpegTrack(AudioTrackInfo trackInfo, AbstractFerryHttpSource manager) {
        super(trackInfo, manager);
    }

    @Override
    protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
        return new MpegAudioTrack(trackInfo, stream);
    }
}
