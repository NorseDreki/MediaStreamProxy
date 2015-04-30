package com.github.upelsin.streamProxy;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class StreamProxy implements Runnable {

    private Logger logger = Logger.getLogger(StreamProxy.class.getName());

    private ServerSocket serverSocket;

    private Thread serverThread;

    private ExecutorService executor;

    private ForkedStreamFactory streamFactory;

    private Set<Socket> clientSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private OkHttpClient client;

    public StreamProxy(ForkedStreamFactory streamFactory) {
        this.streamFactory = streamFactory;
    }

    public void start(int port) {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(port != 0);
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

    public void start() {
        start(0);
    }

    public void shutdown() {
        if (serverThread == null) {
            throw new IllegalStateException("Cannot shutdown proxy, it has not been started");
        }

        executor.shutdownNow();
        closeClientSockets();

        serverThread.interrupt();
        closeQuietly(serverSocket);
        joinUninterruptibly(serverThread);

        serverThread = null;
    }

    private void closeClientSockets() {
        for (Iterator<Socket> s = clientSockets.iterator(); s.hasNext(); ) {
            closeQuietly(s.next());
            s.remove();
        }
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

    private void serveClientRequest(final Socket clientSocket) {
        clientSockets.add(clientSocket);
        BufferedSource source = null;

        try {
            source = Okio.buffer(Okio.source(clientSocket));

            String url = validateClientRequest(source);
            Response response = executeRealRequest(source, url);

            if (Thread.currentThread().isInterrupted()) return;

            writeResponseStreams(clientSocket, response);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Exception while serving client request", e);

        } finally {
            closeQuietly(source);
            clientSockets.remove(clientSocket);
        }
    }

    private String validateClientRequest(BufferedSource source) throws IOException {
        String requestLine = source.readUtf8LineStrict();
        StringTokenizer st = new StringTokenizer(requestLine);

        String method = st.nextToken();
        if (!method.equalsIgnoreCase("GET")) {
            throw new ProxyRequestNotSupportedException("Unable to serve request, only GET is supported");
        }

        String url = st.nextToken();
        return url.substring(1); // skip leading "/"
    }

    private Headers.Builder buildHeaders(BufferedSource source) throws IOException {
        Headers.Builder headers = new Headers.Builder();
        String header;
        while ((header = source.readUtf8LineStrict()).length() != 0) {
            headers.add(header);
        }
        return headers;
    }

    private Response executeRealRequest(BufferedSource source, String realUri) throws IOException {
        Headers.Builder headers = buildHeaders(source);
        Request request = new Request.Builder()
                .url(realUri)
                .headers(headers.build())
                .build();
        return client.newCall(request).execute();
    }

    private void writeResponseStreams(Socket clientSocket, Response response) throws IOException {
        ForkedStream forkedStream = streamFactory.createForkedStream(new Properties());
        try {
            writeResponse(clientSocket, response, forkedStream);
        } catch (IOException e) {
            forkedStream.abort();
            throw e;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                // might be called twice, but that's fine
                forkedStream.abort();
            }
        }
    }

    private void writeResponse(Socket clientSocket, Response response, ForkedStream forkedStream)
            throws IOException {

        BufferedSource source = response.body().source();
        BufferedSink sink = Okio.buffer(Okio.sink(clientSocket));

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

                forkedStream.write(buffer, 0, read);
                forkedStream.flush();
            }
        } catch (IOException e) {
            throw e;

        } finally {
            //TODO close sideStream
            closeQuietly(source);
            closeQuietly(sink);
            closeQuietly(forkedStream);
        }
    }

    private void writeStatusLine(Response response, BufferedSink sink) throws IOException {
        String protocol = response.protocol().toString().toUpperCase(Locale.US);
        String statusLine = String.format("%s %d %s\r\n", protocol, response.code(), response.message());
        sink.writeUtf8(statusLine);
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

    public void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    public void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (IOException ignored) {
            }
        }
    }

    public void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (IOException ignored) {
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
