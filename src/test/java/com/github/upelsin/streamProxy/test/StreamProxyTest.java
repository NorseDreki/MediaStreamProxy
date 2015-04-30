package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.test.mocks.MockForkedStream;
import com.github.upelsin.streamProxy.test.rules.MockWebServerRule;
import com.github.upelsin.streamProxy.test.rules.StreamProxyRule;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Rule;
import org.junit.Test;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Created by upelsin on 13.04.2015.
 */
public class StreamProxyTest {

    public static final int NUM_CONCURRENT_REQUESTS = 5;

    public static final String MOCK_RESPONSE_BODY = "Hello";

/*    @Rule
    public Timeout globalTimeout = new Timeout(4000);*/

    @Rule
    public StreamProxyRule proxy = new StreamProxyRule();

    @Rule
    public MockWebServerRule server = new MockWebServerRule();


    @Test
    public void should_serve_request() throws Exception {
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());
    }

    @Test
    public void should_stop_in_timely_manner_after_serving_request() throws Exception {
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);
    }

    private void assertSuccessfulRequestFor(String serverUrl, String body) {
        String proxiedUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), serverUrl);

        try {
            URL url = new URL(proxiedUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            InputStream in = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
            assertEquals(body, reader.readLine());

        } catch (IOException e) {
            throw new RuntimeException("Unable to execute request for " + proxiedUrl, e);
        }
    }

    @Test
    public void should_propagate_request_headers() throws Exception {
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));

        String serverUrl = server.getUrl("/").toString();
        String proxiedUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), serverUrl);

        URL url = new URL(proxiedUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        //TODO properly emulate request and response
        connection.setRequestProperty("Content-Type", "audio/mpeg");
        connection.setRequestProperty("Accept-Ranges", "bytes");
        connection.setRequestProperty("Content-Length", "4");
        connection.setRequestProperty("Content-Range", "bytes 0-3/4");
        connection.setRequestProperty("Status", "206");

        InputStream in = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
        assertEquals(MOCK_RESPONSE_BODY, reader.readLine());

        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());
        assertEquals("bytes", request.getHeader("Accept-Ranges"));
        assertEquals("bytes 0-3/4", request.getHeader("Content-Range"));
    }

    @Test
    public void should_serve_concurrent_requests() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(NUM_CONCURRENT_REQUESTS);

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        }

        final String serverUrl = server.getUrl("/").toString();

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    await(startLatch);
                    assertSuccessfulRequestFor(serverUrl, MOCK_RESPONSE_BODY);
                    finishLatch.countDown();
                }
            }).start();
        }
        startLatch.countDown();
        await(finishLatch);

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            RecordedRequest request = server.takeRequest();
            assertEquals("GET / HTTP/1.1", request.getRequestLine());
        }
    }

    @Test
    public void should_signal_success_to_output_stream() {

    }

    @Test
    public void should_signal_failure_to_output_stream() throws IOException, InterruptedException {
        MockResponse response = new MockResponse().setBody(loadMp3().buffer());
        server.enqueue(response);

        String serverUrl = server.getUrl("/").toString();

        final String proxiedUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), serverUrl);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(proxiedUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    InputStream in = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    String s = reader.readLine();

                    assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
                    //assertEquals(body, reader.readLine());

                } catch (IOException e) {
                    throw new RuntimeException("Unable to execute request for " + proxiedUrl, e);
                }

            }
        }).start();

        Thread.sleep(3000);
        //proxy.shutdown();


        //verify(sideStream).abort();
    }

    @Test
    public void should_pass_query_parameters() {

    }

    @Test
    public void should_write_response_to_output_stream() throws Exception {
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());

        //verify(outStream).write(any(byte[].class), any(Integer.class), any(Integer.class));
        /*byte[] bytes = sideStream.toByteArray();
        String outStreamResult = new String(bytes, "UTF8");

        assertThat(outStreamResult, is(equalTo(MOCK_RESPONSE_BODY)));*/
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public BufferedSource loadMp3() throws FileNotFoundException {
        File resourcesDirectory = new File("src/test/resources/");
        File resource = new File(resourcesDirectory, "track.mp3");

        return Okio.buffer(Okio.source(resource));

    }
}
