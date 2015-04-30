package com.github.upelsin.streamProxy.test.mocks;

import com.github.upelsin.streamProxy.ForkedStream;
import com.github.upelsin.streamProxy.ForkedStreamFactory;

import java.util.Properties;

/**
 * Created by upelsin on 30.04.2015.
 */
public class MockForkedStreamFactory implements ForkedStreamFactory {

    @Override
    public ForkedStream createForkedStream(Properties props) {
        return new MockForkedStream();
    }
}