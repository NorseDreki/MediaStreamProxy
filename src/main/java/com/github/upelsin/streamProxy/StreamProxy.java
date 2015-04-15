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
                        serveClientRequest(clientSocket);
                    }
                });

            } catch (RuntimeException e) { // protect while(){} from any runtime exception
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);

            } catch (IOException e) {
                logger.log(Level.WARNING, "Exception while accepting connection from client", e);
            }
        }
    }

    private void serveClientRequest(Socket clientSocket) {
        try {
            BufferedSource source = Okio.buffer(Okio.source(clientSocket));

            String requestLine = source.readUtf8LineStrict();
            StringTokenizer st = new StringTokenizer(requestLine);
            String method = st.nextToken();
            String uri = st.nextToken();
            String realUri = uri.substring(1);


            Headers.Builder headers = buildHeaders(source);
            Request request = new Request.Builder()
                    .url(realUri)
                    .headers(headers.build())
                    .build();
            Response response = client.newCall(request).execute();

            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            OutputStream fork = streamFactory.createOutputStream(new Properties());
            try {
                writeResponse(clientSocket, response, fork);
            } catch (IOException e) {
                //fork.abort();
                throw e;
            } finally {
                if (Thread.currentThread().isInterrupted()) {
                    // might be called twice, but that's fine
                    //fork.abort();
                }
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "Exception while serving client request", e);
        }
    }

    private Headers.Builder buildHeaders(BufferedSource source) throws IOException {
        Headers.Builder headers = new Headers.Builder();
        String header;
        while ((header = source.readUtf8LineStrict()).length() != 0) {
            headers.add(header);
        }
        return headers;
    }

    private void writeResponse(Socket clientSocket, Response response, OutputStream fork)
            throws IOException {

        BufferedSink sink = Okio.buffer(Okio.sink(clientSocket));
        BufferedSource source = response.body().source();

        try {
            writeStatusLine(response, sink);
            writeHeaders(response, sink);
            sink.flush();

            byte[] buffer = new byte[16384];
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

        } finally {
            //TODO close quietly
            source.close();
            sink.close();
            fork.close();
        }
    }

    private void writeHeaders(Response response, BufferedSink sink) throws IOException {
        Headers headers = response.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {
            sink.writeUtf8(headers.name(i));
            sink.writeUtf8(": ");
            sink.writeUtf8(headers.value(i));
            sink.writeUtf8("\r\n");
        }
        sink.writeUtf8("\r\n");
    }

    private void writeStatusLine(Response response, BufferedSink sink) throws IOException {
        String protocol = response.protocol().toString().toUpperCase(Locale.US);
        String statusLine = String.format("%s %d %s\r\n", protocol, response.code(), response.message());
        sink.writeUtf8(statusLine);
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
