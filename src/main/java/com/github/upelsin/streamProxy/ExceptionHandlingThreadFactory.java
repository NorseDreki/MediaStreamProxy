package com.github.upelsin.streamProxy;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code ThreadFactory} which handles {@code Thread}'s uncaught exceptions.
 * <p>
 * Use factory methods to get different handling flavors.
 * <p>
 * Available handling policies:
 * <li>
 *     Log uncaught exception to a {@link java.util.logging.Logger}.
 * </li>
  * Created by upelsin on 30.04.2015.
 */
public class ExceptionHandlingThreadFactory implements ThreadFactory {

    private static final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

    public static ThreadFactory loggingExceptionThreadFactory() {
        return new ExceptionHandlingThreadFactory(
                new LoggingExceptionHandler(Logger.getLogger(ExceptionHandlingThreadFactory.class.getName()))
        );
    }


    private final Thread.UncaughtExceptionHandler handler;

    private ExceptionHandlingThreadFactory(Thread.UncaughtExceptionHandler handler) {
        this.handler = handler;
    }

    @Override
    public Thread newThread(Runnable run) {
        Thread thread = defaultFactory.newThread(run);
        thread.setUncaughtExceptionHandler(handler);
        return thread;
    }

    private static class LoggingExceptionHandler implements Thread.UncaughtExceptionHandler {
        private final Logger logger;

        public LoggingExceptionHandler(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable t) {
            logger.log(Level.SEVERE, "Uncaught exception for " + thread.getName(), t);
        }
    }
}
