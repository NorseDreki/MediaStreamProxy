package com.github.upelsin.streamProxy;

import java.util.concurrent.ExecutionException;

/**
 * Created by upelsin on 16.04.2015.
 */
public class ProxyRequestNotSupportedException extends RuntimeException {

    public ProxyRequestNotSupportedException(String message) {
        super(message);
    }
}
