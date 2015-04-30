package com.github.upelsin.streamProxy.test.rules;

import com.github.upelsin.streamProxy.StreamProxy;
import com.github.upelsin.streamProxy.test.mocks.MockForkedStreamFactory;
import org.junit.rules.ExternalResource;

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
