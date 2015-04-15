package com.github.upelsin.streamProxy;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import okio.*;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class StreamProxy implements Runnable {

    private Logger logger = Logger.getLogger(StreamProxy.class.getName());

    private ServerSocket serverSocket;

    private Thread serverThread;

    private volatile boolean streamingAllowed = true;
    private volatile boolean isRunning = true;

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

        isRunning = false; //TODO remove

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

                        /*HttpRequest request = readRequest(clientSocket);
                        try {
                            processRequest(request, clientSocket);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }*/
                    }
                });

            } catch (IOException e) {
                logger.log(Level.WARNING, "Exception while processing connection from client", e);
            }
        }
    }

    private HttpRequest readRequest(Socket client) {
        HttpRequest request = null;
        InputStream is;
        String firstLine = "";
        Map<String, String> headers;
        try {
            is = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is), 8192); //header should fit in 8K
            firstLine = reader.readLine();

            headers = readHeaders(reader);


        } catch (IOException e) {
            //Log.e(LOG_TAG, "Error parsing request", e);
            return request;
        }

        if (firstLine == null) {
            //Log.i(LOG_TAG, "Proxy client closed connection without a request.");
            return request;
        }

        StringTokenizer st = new StringTokenizer(firstLine);
        String method = st.nextToken();
        String uri = st.nextToken();
        String realUri = uri.substring(1);

        //Log.d(LOG_TAG, "Client requested: " + realUri);
        request = new BasicHttpRequest(method, realUri);

        addHeaders(request, headers);

        return request;
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
        //processRequest2(response, clientSocket);

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
        String s = source.readString(Charset.forName("UTF-8"));
        sink.write(s.getBytes());
        sink.flush();

        source.close();
        sink.close();

    }

    private StringBuilder getHeaders2(Response response) {
        StringBuilder httpString = new StringBuilder();
        httpString.append("http/1.1 200 OK\r\nContent-Length: 5\r\n\r\n");


        /*String statusLine = String.format("%s %d %s\r\n", response.protocol().toString(), response.code(), response.message());
        httpString.append(statusLine);

        Headers headers = response.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {
            httpString.append(headers.name(i));
            httpString.append(": ");
            httpString.append(headers.value(i));
            httpString.append("\r\n");
        }
        httpString.append("\r\n");*/

        return httpString;
    }

    private void processRequest2(Response response, Socket client)
            throws IllegalStateException, IOException {

        //Uri parsed = Uri.parse(url);

        Properties props = new Properties();
        /*props.setProperty("artist", parsed.getQueryParameter("artist"));
        props.setProperty("track", parsed.getQueryParameter("track"));*/

        OutputStream outputStream = streamFactory.createOutputStream(props);

        InputStream data = response.body().byteStream();//realResponse.getEntity().getContent();

        //Log.d(LOG_TAG, "Reading headers");
        StringBuilder httpString = getHeaders2(response);
        //Log.d(LOG_TAG, "Copying headers done");

        int slicer = 0;
        int readBytes = 0;
        try {
            //Log.d(LOG_TAG, Thread.currentThread().getName() + "Writing to client...");
            byte[] buffer = httpString.toString().getBytes();
            client.getOutputStream().write(buffer, 0, buffer.length);
            byte[] buff = new byte[1024 * 64];

            while (isRunning
/*                    && streamingAllowed
                    && !Thread.currentThread().isInterrupted()*/
                    && (readBytes /*= data.read(buff, 0, buff.length)*/) != -1) {

                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Reading bytes...");

                    readBytes = data.read(buff, 0, buff.length);
                if (readBytes == -1) {
                    break;
                }

                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Read bytes: " + readBytes);

                    client.getOutputStream().write(buff, 0, readBytes);
                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Written bytes: " + readBytes);

                    slicer++;

                outputStream.write(buff, 0, readBytes);

                if (!streamingAllowed || Thread.currentThread().isInterrupted()) {
                    break;
                }

            }
        } catch (SocketException e) {
            //Log.w(LOG_TAG, Thread.currentThread().getName() + "Looks like MediaPlayer has disconnected", e);
        } catch (Exception e) {
            //Log.e(LOG_TAG, Thread.currentThread().getName() + "Exception!", e);
        } finally {
            //Log.w(LOG_TAG, Thread.currentThread().getName() + "Stopped streaming");
            streamingAllowed = true; //allow it back


            if (readBytes == -1) {
                //Log.d(LOG_TAG, "Written full stream");
                outputStream.close();
            }

            /*realResponse.getEntity().consumeContent();
            Log.w(LOG_TAG, "Consumed content");

			if (data != null) {
				data.close();
                Log.w(LOG_TAG, "Closed input stream");
			}*/
            client.getOutputStream().flush();
            client.getOutputStream().close();


            //client.close();
            //Log.w(LOG_TAG, "Closed client");
        }
    }

    private void addHeaders(HttpRequest request, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> result = new HashMap<String, String>();
        String inputLine;

        while (!(inputLine = reader.readLine()).equals("")) {
            //Log.i(LOG_TAG, "ZZZZ" + inputLine);
            result.put(inputLine.split(": ")[0],
                    inputLine.split(": ")[1]);
        }

        return result;
    }

    private HttpResponse download(String url, HttpRequest request) {
        DefaultHttpClient seed = new DefaultHttpClient();
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        SingleClientConnManager mgr = new SingleClientConnManager(seed.getParams(), registry);
        DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());
        HttpGet method = new HttpGet(url);

        Header[] allHeaders = request.getAllHeaders();
        method.setHeaders(allHeaders);

        HttpResponse response = null;
        try {
            //Log.d(LOG_TAG, "Executing real request...");
            response = http.execute(method);
            //Log.d(LOG_TAG, "Executed");
        } catch (ClientProtocolException e) {
            //Log.e(LOG_TAG, "Error executing", e);
        } catch (IOException e) {
            //Log.e(LOG_TAG, "Error executing", e);
        }
        return response;
    }

    private void processRequest(HttpRequest request, Socket client)
            throws IllegalStateException, IOException {
        if (request == null) {
            return;
        }
        //Log.d(LOG_TAG, "Processing");
        String url = request.getRequestLine().getUri();
        HttpResponse realResponse = download(url, request);
        if (realResponse == null) {
            return;
        }

        //Uri parsed = Uri.parse(url);

        Properties props = new Properties();
        /*props.setProperty("artist", parsed.getQueryParameter("artist"));
        props.setProperty("track", parsed.getQueryParameter("track"));*/

        OutputStream outputStream = streamFactory.createOutputStream(props);

        InputStream data = realResponse.getEntity().getContent();
        StatusLine line = realResponse.getStatusLine();
        HttpResponse response = new BasicHttpResponse(line);
        response.setHeaders(realResponse.getAllHeaders());

        //Log.d(LOG_TAG, "Reading headers");
        StringBuilder httpString = getHeaders(response);
        //Log.d(LOG_TAG, "Copying headers done");

        int slicer = 0;
        int readBytes = 0;
        try {
            //Log.d(LOG_TAG, Thread.currentThread().getName() + "Writing to client...");
            byte[] buffer = httpString.toString().getBytes();
            client.getOutputStream().write(buffer, 0, buffer.length);
            byte[] buff = new byte[1024 * 64];

            while (isRunning
/*                    && streamingAllowed
                    && !Thread.currentThread().isInterrupted()*/
                    && (readBytes /*= data.read(buff, 0, buff.length)*/) != -1) {

                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Reading bytes...");

                    readBytes = data.read(buff, 0, buff.length);
                if (readBytes == -1) {
                    break;
                }

                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Read bytes: " + readBytes);

                    client.getOutputStream().write(buff, 0, readBytes);
                if (slicer % 25 == 0)
                    //Log.d("WWWW", Thread.currentThread().getName() + "Written bytes: " + readBytes);

                    slicer++;

                outputStream.write(buff, 0, readBytes);

                if (!streamingAllowed || Thread.currentThread().isInterrupted()) {
                    break;
                }

            }
        } catch (SocketException e) {
            //Log.w(LOG_TAG, Thread.currentThread().getName() + "Looks like MediaPlayer has disconnected", e);
        } catch (Exception e) {
            //Log.e(LOG_TAG, Thread.currentThread().getName() + "Exception!", e);
        } finally {
            //Log.w(LOG_TAG, Thread.currentThread().getName() + "Stopped streaming");
            streamingAllowed = true; //allow it back


            if (readBytes == -1) {
                //Log.d(LOG_TAG, "Written full stream");
                outputStream.close();
            }

            /*realResponse.getEntity().consumeContent();
            Log.w(LOG_TAG, "Consumed content");

			if (data != null) {
				data.close();
                Log.w(LOG_TAG, "Closed input stream");
			}*/
            client.getOutputStream().flush();
            client.getOutputStream().close();


            //client.close();
            //Log.w(LOG_TAG, "Closed client");
        }
    }

    private StringBuilder getHeaders(HttpResponse response) {
        StringBuilder httpString = new StringBuilder();
        httpString.append(response.getStatusLine().toString());

        httpString.append("\r\n"); //was httpString.append("\n");
        for (Header h : response.getAllHeaders()) {
            httpString.append(h.getName()).append(": ").append(h.getValue()).append("\r\n"); //was httpString.append("\n");
        }
        httpString.append("\r\n"); //was httpString.append("\n");
        return httpString;
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
