package com.squirrel.android.httpProxy;

import com.squirrel.android.domain.model.ArtistTrack;
import com.squirrel.android.domain.model.Track;

import org.jetbrains.annotations.Nullable;

/**
 * Created by Alexey Dmitriev <mr.alex.dmitriev@gmail.com> on 25.02.2015.
 */
public class TrackCachedEvent {
    private ArtistTrack artistTrack;
    private String localPath;

    public TrackCachedEvent(int totalBytes) {

    }

    public TrackCachedEvent(ArtistTrack artistTrack, String localPath) {
        this.artistTrack = artistTrack;
        this.localPath = localPath;
    }

    @Nullable
    public String matches(Track track) {
        if (track.equals(artistTrack)) {
            return localPath;
        } else {
            return null;
        }
    }
}
