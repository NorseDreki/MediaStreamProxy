package com.github.upelsin.streamProxy;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;


public class StreamProxy implements Runnable {

    private Logger logger = Logger.getLogger(StreamProxy.class.getName());

    private ServerSocket serverSocket;

    private Thread serverThread;

    private ExecutorService executor;

    private IOutputStreamFactory streamFactory;

    private Set<Socket> clientSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private OkHttpClient client;

    public StreamProxy(IOutputStreamFactory streamFactory) {
        this.streamFactory = streamFactory;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(0);
        } catch (IOException e) {
            throw new ProxyNotStartedException(e);
        }

        client = new OkHttpClient();

        ExceptionHandlingThreadFactory threadFactory =
                new ExceptionHandlingThreadFactory(new LoggingExceptionHandler(logger));

        executor = Executors.newCachedThreadPool(threadFactory);

        serverThread = threadFactory.newThread(this);
        serverThread.start();
    }

    public void shutdown() {
        if (serverThread == null) {
            throw new IllegalStateException("Cannot shutdown proxy, it has not been started");
        }

        executor.shutdownNow();

        while (clientSockets.iterator().hasNext()) {
            Socket socket = clientSockets.iterator().next();
            closeQuietly(socket);
        }

        serverThread.interrupt();
        closeQuietly(serverSocket);
        joinUninterruptibly(serverThread);

        serverThread = null;
    }

    @Override
    public void run() {
        if (serverThread == null) {
            throw new IllegalStateException("Proxy must be started first");
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                final Socket clientSocket = serverSocket.accept();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            readFrom(clientSocket);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

            } catch (IOException e) {
                logger.log(Level.WARNING, "Exception while processing connection from client", e);
            }
        }
    }

    private Object readFrom(Socket clientSocket) throws IOException {
        BufferedSource source = Okio.buffer(Okio.source(clientSocket));
        //String requestLine = source.readUtf8LineStrict();

        String requestLine;
        try {
            requestLine = source.readUtf8LineStrict();
        } catch (IOException streamIsClosed) {
            return null; // no request because we closed the stream
        }
        if (requestLine.length() == 0) {
            return null; // no request because the stream is exhausted
        }

        Headers.Builder headers = new Headers.Builder();
        String header;
        while ((header = source.readUtf8LineStrict()).length() != 0) {
            headers.add(header);
        }

        StringTokenizer st = new StringTokenizer(requestLine);
        String method = st.nextToken();
        String uri = st.nextToken();
        String realUri = uri.substring(1);


        Request request1 = new Request.Builder()
                .url(realUri)
                .headers(headers.build())
                .build();

        Response response = client.newCall(request1).execute();
        if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

        writeHttpResponse(clientSocket, response);

        return null;
    }

    private void writeHttpResponse(Socket clientSocket, Response response)
            throws IOException {

        BufferedSink sink = Okio.buffer(Okio.sink(clientSocket));

        String statusLine = String.format("%s %d %s\r\n", response.protocol().toString().toUpperCase(Locale.US), response.code(), response.message());
        sink.writeUtf8(statusLine);

        Headers headers = response.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {
            sink.writeUtf8(headers.name(i));
            sink.writeUtf8(": ");
            sink.writeUtf8(headers.value(i));
            sink.writeUtf8("\r\n");
        }
        sink.writeUtf8("\r\n");
        sink.flush();

        BufferedSource source = response.body().source();

        OutputStream fork = streamFactory.createOutputStream(new Properties());

        byte[] buffer = new byte[65536];
        while (!Thread.currentThread().isInterrupted()) {
            int read = source.read(buffer);
            if (read == -1) {
                break;
            }

            sink.write(buffer, 0, read);
            sink.flush();

            fork.write(buffer, 0, read);
            fork.flush();
        }

        source.close();
        sink.close();
        fork.close();
    }

    public int getPort() {
        if (serverThread == null) {
            throw new IllegalStateException("Proxy must be started before obtaining port number");
        }

        return serverSocket.getLocalPort();
    }

    public void joinUninterruptibly(Thread toJoin) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    toJoin.join();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    public void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    private static class ExceptionHandlingThreadFactory implements ThreadFactory {
        private static final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private final Thread.UncaughtExceptionHandler handler;

        public ExceptionHandlingThreadFactory(Thread.UncaughtExceptionHandler handler) {
            this.handler = handler;
        }

        @Override
        public Thread newThread(Runnable run) {
            Thread thread = defaultFactory.newThread(run);
            thread.setUncaughtExceptionHandler(handler);
            return thread;
        }
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
