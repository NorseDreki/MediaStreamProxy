package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.ProxyNotStartedException;
import com.github.upelsin.streamProxy.ForkedStreamFactory;
import com.github.upelsin.streamProxy.StreamProxy;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStream;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
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
    public static final int DEFAULT_PORT = 22333;
    private StreamProxy proxy;

/*    @Rule
    public Timeout globalTimeout = new Timeout(4000);*/

    @Mock
    private ForkedStreamFactory mockStreamFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        proxy = new StreamProxy(mockStreamFactory);
    }

    @Test
    public void should_start() {
        proxy.start();
        assertTrue(isProxyListeningAt(proxy.getPort()));
    }

    @Test
    public void should_start_then_stop() {
        proxy.start();
        int port = proxy.getPort();

        proxy.shutdown();
        assertFalse(isProxyListeningAt(port));
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_stopping_before_starting() {
        proxy.shutdown();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_getting_port_before_starting() {
        proxy.getPort();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_run_called_directly_before_starting() {
        proxy.run();
    }

    @Test(expected = ProxyNotStartedException.class)
    public void should_throw_when_port_already_taken() {
        proxy.start(DEFAULT_PORT);
        proxy.start(DEFAULT_PORT);
    }

    @Test
    public void should_start_and_stop_several_times_in_a_row() {
        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            proxy.start();
            int port = proxy.getPort();
            assertTrue(isProxyListeningAt(port));

            proxy.shutdown();
            assertFalse(isProxyListeningAt(port));
        }
    }

    @Test
    public void should_serve_request() throws Exception {
        proxy.start();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        server.start();

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());

        server.shutdown();
        proxy.shutdown();
    }

    @Test
    public void should_stop_in_timely_manner_after_serving_request() throws Exception {
        proxy.start();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        server.start();

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);

        server.shutdown();
        proxy.shutdown();
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
        proxy.start();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        server.start();

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

        server.shutdown();
        proxy.shutdown();
    }

    @Test
    public void should_serve_concurrent_requests() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(NUM_CONCURRENT_REQUESTS);
        MockWebServer server = new MockWebServer();

        MockForkedStream sideStream = new MockForkedStream();
        //all threads sharing
        given(mockStreamFactory.createForkedStream(any(Properties.class))).willReturn(sideStream);

        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        }

        proxy.start();
        server.start();
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

        server.shutdown();
        proxy.shutdown();
    }

    @Test
    public void should_signal_success_to_output_stream() {

    }

    @Test
    public void should_signal_failure_to_output_stream() throws IOException, InterruptedException {
        MockForkedStream sideStream = spy(new MockForkedStream());
        given(mockStreamFactory.createForkedStream(any(Properties.class))).willReturn(sideStream);

        proxy.start();
        MockWebServer server = new MockWebServer();
        MockResponse response = new MockResponse().setBody(loadMp3().buffer());
        server.enqueue(response);
        server.start();

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
        proxy.shutdown();


        verify(sideStream).abort();
    }

    @Test
    public void should_pass_query_parameters() {

    }

    @Test
    public void should_write_response_to_output_stream() throws Exception {
        MockForkedStream sideStream = new MockForkedStream();
        given(mockStreamFactory.createForkedStream(any(Properties.class))).willReturn(sideStream);

        proxy.start();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(MOCK_RESPONSE_BODY));
        server.start();

        assertSuccessfulRequestFor(server.getUrl("/").toString(), MOCK_RESPONSE_BODY);
        RecordedRequest request = server.takeRequest();
        assertEquals("GET / HTTP/1.1", request.getRequestLine());

        //verify(outStream).write(any(byte[].class), any(Integer.class), any(Integer.class));
        byte[] bytes = sideStream.toByteArray();
        String outStreamResult = new String(bytes, "UTF8");

        assertThat(outStreamResult, is(equalTo(MOCK_RESPONSE_BODY)));

        server.shutdown();
        proxy.shutdown();
    }


    private boolean isProxyListeningAt(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            System.out.println("");
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                /* should not be thrown */
                }
            }
        }

        return true;
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
