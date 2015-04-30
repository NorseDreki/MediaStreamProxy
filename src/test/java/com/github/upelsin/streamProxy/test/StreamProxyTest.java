package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.test.rules.MockWebServerRule;
import com.github.upelsin.streamProxy.test.rules.StreamProxyRule;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import org.junit.Rule;
import org.junit.Test;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Created by upelsin on 13.04.2015.
 */
public class StreamProxyTest {

    public static final int NUM_CONCURRENT_REQUESTS = 5;

    public static final String MOCK_RESPONSE_BODY = "Hello";

    private static final Buffer RESPONSE_BODY_MP3 = loadSampleMp3();

/*    @Rule
    public Timeout globalTimeout = new Timeout(4000);*/

    @Rule
    public StreamProxyRule proxy = new StreamProxyRule();

    @Rule
    public MockWebServerRule server = new MockWebServerRule();


    private RecordedRequest assertSuccessfulRequestFor(HttpURLConnection connection, byte[] expectedBody) {
        try {
            byte[] responseBody = readInputStream(connection.getInputStream());
            RecordedRequest request = server.takeRequest();

            assertEquals("GET / HTTP/1.1", request.getRequestLine());
            assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
            assertArrayEquals(expectedBody, responseBody);
            return request;

        } catch (IOException | InterruptedException e) {
            fail();
            return null;
        }
    }

    @Test
    public void should_serve_request() throws Exception {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));
        assertSuccessfulRequestFor(createUrlConnection(), RESPONSE_BODY_MP3.readByteArray());
    }

    @Test
    public void should_serve_throttled_request() throws Exception {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3).throttleBody(65536, 1, TimeUnit.SECONDS));
        assertSuccessfulRequestFor(createUrlConnection(), RESPONSE_BODY_MP3.readByteArray());
    }


    @Test
    public void should_propagate_request_headers() throws Exception {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));

        HttpURLConnection connection = createUrlConnection();
        connection.setRequestProperty("Content-Type", "audio/mpeg");
        connection.setRequestProperty("Accept-Ranges", "bytes");
        connection.setRequestProperty("Content-Length", "4");
        connection.setRequestProperty("Content-Range", "bytes 0-3/4");

        RecordedRequest request = assertSuccessfulRequestFor(connection, RESPONSE_BODY_MP3.readByteArray());
        assertEquals("audio/mpeg", request.getHeader("Content-Type"));
        assertEquals("bytes", request.getHeader("Accept-Ranges"));
//        assertEquals("4", request.getHeader("Content-Length"));
        assertEquals("bytes 0-3/4", request.getHeader("Content-Range"));
    }

    @Test
    public void should_serve_concurrent_requests() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(NUM_CONCURRENT_REQUESTS);
        final byte[] bodyMp3Bytes = loadSampleMp3().readByteArray();

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            // load sample each time due to defensive copy of underlying buffer
            server.enqueue(new MockResponse().setBody(loadSampleMp3()));
        }

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    await(startLatch);
                    assertSuccessfulRequestFor(createUrlConnection(), bodyMp3Bytes);
                    finishLatch.countDown();
                }
            }).start();
        }
        startLatch.countDown();
        await(finishLatch);
    }

    @Test
    public void should_signal_success_to_output_stream() {

    }

    @Test
    public void should_signal_failure_to_output_stream() throws IOException, InterruptedException {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3).throttleBody(65536, 3000, TimeUnit.MILLISECONDS));

        new Thread(new Runnable() {
            @Override
            public void run() {
                assertSuccessfulRequestFor(createUrlConnection(), RESPONSE_BODY_MP3.readByteArray());
            }
        }).start();

        Thread.sleep(1000);
        //proxy.shutdown();


        //verify(sideStream).abort();
    }

    @Test
    public void should_pass_query_parameters() {

    }

    @Test
    public void should_write_response_to_output_stream() throws Exception {
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));

        assertSuccessfulRequestFor(createUrlConnection(), MOCK_RESPONSE_BODY.getBytes());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());

        //verify(outStream).write(any(byte[].class), any(Integer.class), any(Integer.class));
        /*byte[] bytes = sideStream.toByteArray();
        String outStreamResult = new String(bytes, "UTF8");

        assertThat(outStreamResult, is(equalTo(MOCK_RESPONSE_BODY)));*/
    }

    private HttpURLConnection createUrlConnection() {
        try {
            String serverUrl = server.getUrl("/").toString();
            String proxiedUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), serverUrl);
            URL url = new URL(proxiedUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            return connection;

        } catch (IOException e) {
            fail();
            return null;
        }
    }

    private byte[] readInputStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();

        return buffer.toByteArray();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Buffer loadSampleMp3() {
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
