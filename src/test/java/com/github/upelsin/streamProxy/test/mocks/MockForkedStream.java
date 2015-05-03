package com.github.upelsin.streamProxy.test.mocks;

import com.github.upelsin.streamProxy.ForkedStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Created by upelsin on 16.04.2015.
 */
public class MockForkedStream implements ForkedStream {

    private Logger logger = Logger.getLogger(MockForkedStream.class.getName());

    private final ByteArrayOutputStream delegate;

    private boolean aborted;

    public MockForkedStream() {
        delegate = new ByteArrayOutputStream();
    }

    @Override
    public void abort() {
        aborted = true;
        logger.info("Called abort()");
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    public byte[] toByteArray() {
        return delegate.toByteArray();
    }

    public boolean isAborted() {
        return aborted;
    }
}
