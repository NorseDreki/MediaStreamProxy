package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.StreamProxy;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.Okio;
import okio.Source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.fail;

/**
 * Created by upelsin on 04.05.2015.
 */
public class StreamProxyTestUtils {

    public static HttpURLConnection createUrlConnection(MockWebServer server, StreamProxy proxy, String queryParams) {
        try {
            String serverUrl = server.getUrl("/" + queryParams).toString();
            String proxiedUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), serverUrl);
            URL url = new URL(proxiedUrl);
            return (HttpURLConnection) url.openConnection();

        } catch (IOException e) {
            fail();
        }
        return null;
    }

    public static HttpURLConnection createUrlConnection(MockWebServer server, StreamProxy proxy) {
        return createUrlConnection(server, proxy, "");
    }

    public static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();

        return buffer.toByteArray();
    }

    public static void readThenDrop(InputStream is) throws IOException {
        byte[] data = new byte[16384];
        is.read(data, 0, data.length);
        is.read(data, 0, data.length);
        is.read(data, 0, data.length);
        is.close();
    }

    public static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static Buffer loadSampleMp3() {
        Buffer result = new Buffer();
        try {
            File resource = new File("src/test/resources/track.mp3");
            Source source = Okio.source(resource);
            result.writeAll(source);

        } catch (IOException e) {
            throw new RuntimeException("Unable to load sample MP3 from resources. Is it there in folder?");
        }

        return result;
    }
}
