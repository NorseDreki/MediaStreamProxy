package com.github.upelsin.streamProxy.test.rules;

import com.github.upelsin.streamProxy.StreamProxy;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStreamFactory;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import org.junit.rules.ExternalResource;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by upelsin on 30.04.2015.
 */
public class StreamProxyRule extends ExternalResource {

    private final StreamProxy proxy = new StreamProxy(new MockForkedStreamFactory());

    @Override
    protected void before() {
        proxy.start();
    }

    @Override
    protected void after() {
        proxy.shutdown();
    }

    public int getPort() {
        return proxy.getPort();
    }
}
