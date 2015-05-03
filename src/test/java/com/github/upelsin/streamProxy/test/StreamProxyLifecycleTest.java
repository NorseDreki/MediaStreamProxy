package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.ProxyNotStartedException;
import com.github.upelsin.streamProxy.StreamProxy;
import com.github.upelsin.streamProxy.Utils;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStreamFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.net.ServerSocket;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

/**
 * Tests for {@link StreamProxy}'s lifecycle methods.
 * <p>
 * Include: valid startup and shutdown, obtaining port, etc.
 * <p>
 * Created by upelsin on 30.04.2015.
 */
public class StreamProxyLifecycleTest {

    public static final int DEFAULT_PORT = 22333;

    public static final int NUM_CONCURRENT_REQUESTS = 5;

    private StreamProxy proxy;

    @Rule
    public Timeout globalTimeout = new Timeout(1000);

    @Before
    public void setUp() {
        proxy = new StreamProxy(new MockForkedStreamFactory());
    }

    @Test
    public void should_start() {
        proxy.start();

        assertTrue(isProxyListeningAt(proxy.getPort()));
        proxy.shutdown();
    }

    @Test
    public void should_start_at_specified_port() {
        proxy.start(DEFAULT_PORT);

        assertTrue(isProxyListeningAt(DEFAULT_PORT));
        assertThat(proxy.getPort(), is(equalTo(DEFAULT_PORT)));
        proxy.shutdown();
    }

    @Test
    public void should_start_then_stop() {
        proxy.start();
        int port = proxy.getPort();

        proxy.shutdown();
        assertFalse(isProxyListeningAt(port));
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_stopped_before_starting() {
        proxy.shutdown();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_getting_port_before_started() {
        proxy.getPort();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_run_called_directly_before_started() {
        proxy.run();
    }

    @Test
    public void should_throw_when_port_has_already_been_taken() {
        try {
            proxy.start(DEFAULT_PORT);
            proxy.start(DEFAULT_PORT);
            fail();
        } catch (ProxyNotStartedException e) {
            // expected
        } finally {
            proxy.shutdown();
        }
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

    private boolean isProxyListeningAt(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            // server socket is in use
        } finally {
            Utils.closeQuietly(ss);
        }

        return true;
    }
}
