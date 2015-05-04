package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.test.mocks.MockForkedStream;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStreamFactory;
import com.github.upelsin.streamProxy.test.rules.MockWebServerRule;
import com.github.upelsin.streamProxy.test.rules.StreamProxyRule;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.*;
import java.net.HttpURLConnection;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.upelsin.streamProxy.test.StreamProxyTestUtils.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

/**
 * Created by upelsin on 13.04.2015.
 */
public class StreamProxyTest {

    private static final int NUM_CONCURRENT_REQUESTS = 5;

    private static final Buffer RESPONSE_BODY_MP3 = loadSampleMp3();

    @Rule
    public Timeout globalTimeout = new Timeout(2000);

    @Rule
    public StreamProxyRule proxy = new StreamProxyRule();

    @Rule
    public MockWebServerRule server = new MockWebServerRule();


    @Test
    public void should_serve_request() throws Exception {
        HttpURLConnection conn = createUrlConnection(server.get(), proxy.get());

        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));

        assertSuccessfulRequestFor(conn, RESPONSE_BODY_MP3.readByteArray());
    }

    @Test
    public void should_serve_throttled_request() throws Exception {
        HttpURLConnection conn = createUrlConnection(server.get(), proxy.get());

        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3).throttleBody(65536, 1, TimeUnit.SECONDS));

        assertSuccessfulRequestFor(conn, RESPONSE_BODY_MP3.readByteArray());
    }

    @Test
    public void should_propagate_request_headers() throws Exception {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));

        HttpURLConnection conn = createUrlConnection(server.get(), proxy.get());
        conn.setRequestProperty("Content-Type", "audio/mpeg");
        conn.setRequestProperty("Accept-Ranges", "bytes");
        conn.setRequestProperty("Content-Length", "4");
        conn.setRequestProperty("Content-Range", "bytes 0-3/4");

        RecordedRequest request = assertSuccessfulRequestFor(conn, RESPONSE_BODY_MP3.readByteArray());
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
                    HttpURLConnection conn = createUrlConnection(server.get(), proxy.get());
                    await(startLatch);
                    assertSuccessfulRequestFor(conn, bodyMp3Bytes);
                    finishLatch.countDown();
                }
            }).start();
        }
        startLatch.countDown();
        await(finishLatch);
    }

    // mockwebserver fails to run this test in batch
    /*@Test
    public void should_signal_failure_to_forked_stream_when_client_disconnected() throws Exception {
        System.setProperty("http.keepAlive", "false"); // otherwise HttpURLConnection misbehaves
        MockForkedStream forkedStream = spy(new MockForkedStream());
        given(proxy.getForkedStreamFactory().createForkedStream(any(Properties.class))).willReturn(forkedStream);
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));

        HttpURLConnection conn = createUrlConnection();
        readThenDrop(conn.getInputStream());
        conn.disconnect();

        Thread.sleep(300);
        verify(forkedStream).abort();
    }*/

    @Test
    public void should_write_response_to_output_stream() throws Exception {
        HttpURLConnection conn = createUrlConnection(server.get(), proxy.get());
        MockForkedStream forkedStream = spy(new MockForkedStream(new Properties()));
        given(proxy.getForkedStreamFactory().createForkedStream(any(Properties.class))).willReturn(forkedStream);
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));

        readFully(conn.getInputStream());
        byte[] bytes = forkedStream.toByteArray();

        assertArrayEquals(RESPONSE_BODY_MP3.readByteArray(), bytes);
    }

    @Test
    public void should_pass_query_parameters_to_forked_stream_factory() throws Exception {
        server.enqueue(new MockResponse().setBody(RESPONSE_BODY_MP3));
        HttpURLConnection conn = createUrlConnection(server.get(), proxy.get(), "?param1=abc&param2=def");

        readFully(conn.getInputStream());
        Properties queryParams = ((MockForkedStreamFactory) proxy.getForkedStreamFactory()).getLatestQueryParams();

        assertThat(queryParams.getProperty("param1"), equalTo("abc"));
        assertThat(queryParams.getProperty("param2"), equalTo("def"));
    }

    private RecordedRequest assertSuccessfulRequestFor(HttpURLConnection connection, byte[] expectedBody) {
        try {
            byte[] responseBody = readFully(connection.getInputStream());
            RecordedRequest request = server.takeRequest();

            assertEquals("GET / HTTP/1.1", request.getRequestLine());
            assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
            assertArrayEquals(expectedBody, responseBody);
            return request;

        } catch (IOException | InterruptedException e) {
            fail();
        }
        return null;
    }
}
