package com.squirrel.android.httpProxy;

import android.os.Environment;

import com.squareup.otto.Bus;
import com.squirrel.android.domain.repositories.IDatabase;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Created by Alexey Dmitriev <mr.alex.dmitriev@gmail.com> on 25.02.2015.
 */
public class TrackOutputStreamFactory implements IOutputStreamFactory {

    public static final String RECORDINGS_FOLDER = "Squirrel";
    private final IDatabase trackDatabase;
    private final Bus eventBus;

    private String artistTrack;
    private final File path;
    private BufferedOutputStream out;
    private File recording;

    public TrackOutputStreamFactory(IDatabase trackDatabase,
                                    Bus eventBus) {
        this.trackDatabase = trackDatabase;
        this.eventBus = eventBus;
        File sdcard = Environment.getExternalStorageDirectory();
        path = new File(sdcard, RECORDINGS_FOLDER);
        path.mkdirs();

    }

    @Override
    public OutputStream createOutputStream(Properties props) {
        String artist = props.getProperty("artist");
        String track = props.getProperty("track");
        recording = new File(path, String.valueOf(System.currentTimeMillis()) + ".mp3");

        try {
            TrackOutputStream result = new TrackOutputStream(
                    recording,
                    artist,
                    track,
                    trackDatabase,
                    eventBus
            );

            return result;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            throw new Error("Must have created track output stream");
        }
    }
}
