package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.IOutputStreamFactory;
import com.github.upelsin.streamProxy.StreamProxy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by upelsin on 13.04.2015.
 */
public class StreamProxyTest {

    private StreamProxy proxy;

    @Mock
    private IOutputStreamFactory streamFactory;

    @Before
    public void setUp() {
        proxy = new StreamProxy(streamFactory);
    }

    @Test
    public void should_start_proxy() {
        proxy.start();
        assertTrue(isProxyListeningAt(proxy.getPort()));
    }

    @Test
    public void should_start_then_stop_proxy() {
        proxy.start();
        int port = proxy.getPort();

        proxy.stop();
        assertFalse(isProxyListeningAt(port));
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_stopping_proxy_before_starting() {
        proxy.stop();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_getting_port_before_starting() {
        proxy.getPort();
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_when_run_called_directly_before_starting() {
        proxy.run();
    }

    @Test
    public void should_start_stop_several_times_in_a_row() {
        for (int i = 0; i < 5; i++) {
            proxy.start();
            int port = proxy.getPort();
            assertTrue(isProxyListeningAt(port));

            proxy.stop();
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
}
