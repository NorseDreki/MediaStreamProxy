package com.github.upelsin.streamProxy;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.upelsin.streamProxy.Utils.closeQuietly;
import static com.github.upelsin.streamProxy.Utils.joinUninterruptibly;


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

        ThreadFactory threadFactory = ExceptionHandlingThreadFactory.loggingExceptionThreadFactory();
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

            String url = parseGetRequestUrl(source);
            Response response = executeRealRequest(source, url);
            Properties queryParams = parseQueryParams(url);

            if (Thread.currentThread().isInterrupted()) return;

            writeResponseStreams(clientSocket, response, queryParams);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Exception while serving client request", e);

        } finally {
            closeQuietly(source);
            clientSockets.remove(clientSocket);
        }
    }

    private Properties parseQueryParams(String url) throws UnsupportedEncodingException {
        Properties queryParams = new Properties();
        Map<String, List<String>> mappedParams = getUrlParameters(url);
        for (Map.Entry<String, List<String>> entry : mappedParams.entrySet()) {
            queryParams.setProperty(entry.getKey(), entry.getValue().get(0));
        }

        return queryParams;
    }

    public static Map<String, List<String>> getUrlParameters(String url)
            throws UnsupportedEncodingException {
        Map<String, List<String>> params = new HashMap<>();
        String[] urlParts = url.split("\\?");
        if (urlParts.length > 1) {
            String query = urlParts[1];
            for (String param : query.split("&")) {
                String pair[] = param.split("=");
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = "";
                if (pair.length > 1) {
                    value = URLDecoder.decode(pair[1], "UTF-8");
                }
                List<String> values = params.get(key);
                if (values == null) {
                    values = new ArrayList<String>();
                    params.put(key, values);
                }
                values.add(value);
            }
        }
        return params;
    }

    private String parseGetRequestUrl(BufferedSource source) throws IOException {
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

    private void writeResponseStreams(Socket clientSocket, Response response, Properties props) throws IOException {
        ForkedStream forkedStream = streamFactory.createForkedStream(props);
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
        OutputStream os = clientSocket.getOutputStream();
        InputStream responseBody = response.body().byteStream();

        try {
            writeStatusLine(response, sink);
            writeHeaders(response, sink);
            sink.flush();

            byte[] buffer = new byte[16384];
            while (!Thread.currentThread().isInterrupted()) {
                int read = responseBody.read(buffer);//source.read(buffer);
                if (read == -1) {
                    logger.log(Level.INFO, "Breaking");
                    break;
                }
                logger.log(Level.INFO, "Writing! " + read);

                sink.write(buffer, 0, read);
                sink.flush();
/*                os.write(buffer, 0, read);
                os.flush();*/

                forkedStream.write(buffer, 0, read);
                forkedStream.flush();
            }
        } catch (IOException e) {
            logger.log(Level.INFO, e.getMessage());
            throw e;

        } finally {
            closeQuietly(os);
            closeQuietly(responseBody);
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

    public ForkedStreamFactory getForkedStreamFactory() {
        return streamFactory;
    }
}
