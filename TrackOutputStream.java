package com.squirrel.android.httpProxy;

import com.squareup.otto.Bus;
import com.squirrel.android.domain.model.ArtistTrack;
import com.squirrel.android.domain.repositories.IDatabase;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by Alexey Dmitriev <mr.alex.dmitriev@gmail.com> on 25.02.2015.
 */
public class TrackOutputStream extends BufferedOutputStream {

    private final IDatabase trackDatabase;
    private final Bus eventBus;
    private final ArtistTrack artistTrack;
    private final File file;
    private final String artist;
    private final String track;
    private int limiter;
    private int totalBytes;

    public TrackOutputStream(File file,
                             String artist,
                             String track,
                             IDatabase trackDatabase,
                             Bus eventBus) throws FileNotFoundException {

        super(new FileOutputStream(file));
        this.file = file;
        this.artist = artist;
        this.track = track;
        this.artistTrack = new ArtistTrack(artist, track);
        this.trackDatabase = trackDatabase;
        this.eventBus = eventBus;
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        super.write(buffer, offset, count);

        if (limiter % 10 == 0) {
            TrackCachedEvent event = new TrackCachedEvent(totalBytes);
            //eventBus.post(event);
        } else {
            totalBytes += count;
        }
        limiter++;
    }

    @Override
    public void close() throws IOException {
        super.close();
        trackDatabase.put("cachedTrack##" + artistTrack.getKey(), file.getAbsolutePath());

        TrackCachedEvent event = new TrackCachedEvent(artistTrack, file.getAbsolutePath());
        eventBus.post(event);
    }
}
