package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.IOutputStreamFactory;
import com.github.upelsin.streamProxy.StreamProxy;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.net.ServerSocket;

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

    }

    private boolean isProxyListeningAt(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            return false;
        } catch (IOException e) {
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
