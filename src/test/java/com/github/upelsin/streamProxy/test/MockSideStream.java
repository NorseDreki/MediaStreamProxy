package com.github.upelsin.streamProxy.test;

import com.github.upelsin.streamProxy.SideStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created by upelsin on 16.04.2015.
 */
public class MockSideStream implements SideStream {

    private final ByteArrayOutputStream delegate;

    public MockSideStream() {
        delegate = new ByteArrayOutputStream();
    }

    @Override
    public void abort() {

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
}
