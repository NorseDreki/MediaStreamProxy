package com.github.upelsin.streamProxy;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;

/**
 * Created by upelsin on 15.04.2015.
 */
public interface SideStream extends Closeable, Flushable {

    void abort();

    void write(byte b[], int off, int len) throws IOException;
}
