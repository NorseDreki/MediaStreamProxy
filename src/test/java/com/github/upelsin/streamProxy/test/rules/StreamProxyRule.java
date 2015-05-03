package com.github.upelsin.streamProxy.test.rules;

import com.github.upelsin.streamProxy.ForkedStreamFactory;
import com.github.upelsin.streamProxy.StreamProxy;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStreamFactory;
import org.junit.rules.ExternalResource;

import static org.mockito.Mockito.spy;

/**
 * Created by upelsin on 30.04.2015.
 */
public class StreamProxyRule extends ExternalResource {

    private final StreamProxy proxy = new StreamProxy(spy(new MockForkedStreamFactory()));

    private boolean started;

    @Override
    protected void before() {
        proxy.start();
        started = true;
    }

    @Override
    protected void after() {
        if (started) proxy.shutdown();
        started = false;
    }

    public int getPort() {
        return proxy.getPort();
    }

    public ForkedStreamFactory getForkedStreamFactory() {
        return proxy.getForkedStreamFactory();
    }

    public void shutdown() {
        proxy.shutdown();
        started = false;
    }
}
