package com.squirrel.android.proxy;

import android.net.Uri;
import android.util.Log;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;


public class AudioStreamProxy implements Runnable {
    public static final int PROXY_PORT = 22333;

    private static final String LOG_TAG = AudioStreamProxy.class.getSimpleName();

    private ServerSocket socket;
    private Thread thread;

    private volatile boolean streamingAllowed = true;
    private volatile boolean isRunning = true;
    private Thread runner;

    private ReentrantLock processing;
    private ExecutorService service;

    /*private IOutputStreamFactory streamFactory;
    private ReentrantLock processing;

    public AudioStreamProxy(IOutputStreamFactory streamFactory) {
        this.streamFactory = streamFactory;
    }*/

    public AudioStreamProxy(Object o) {
    }

    public void init() {
        try {
            socket = new ServerSocket(PROXY_PORT, 0, InetAddress.getByAddress(new byte[] {127,0,0,1}));
            socket.setSoTimeout(0); //was 5000
            int port = socket.getLocalPort();
            Log.d(LOG_TAG, "Port " + port + " obtained for proxy");
        } catch (UnknownHostException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        }

        processing = new ReentrantLock();
        service = Executors.newFixedThreadPool(5);
    }

    public void start() {
        Log.d(LOG_TAG, "Starting proxy");
        if (socket == null) {
            throw new IllegalStateException("Cannot start proxy; it has not been initialized.");
        }

        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        Log.w(LOG_TAG, "Stopping proxy");
        isRunning = false;

        if (thread == null) {
            throw new IllegalStateException("Cannot stop proxy; it has not been started.");
        }

        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void interruptRequest() {
        Log.d(LOG_TAG, "Interrupting request processing...");
        streamingAllowed = false;

        try {
            processing.lock();
            Log.d(LOG_TAG, "Request interrupted");
        } finally {
            processing.unlock();
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        while (isRunning) {
            Log.v(LOG_TAG, "Proxy is alive...");
            try {
                final Socket client = socket.accept();
                if (client == null) {
                    continue;
                }

                client.setSendBufferSize(1024);

                try {
                    processing.lock();

                    Log.d(LOG_TAG, Thread.currentThread().getName() + "Client connected: " + client.getInetAddress().toString()
                            + ". Keep alive: " + client.getKeepAlive());

                    streamingAllowed = true;

                    /*HttpRequest request = readRequest(client);
                    try {
                        processRequest(request, client);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }*/

                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            HttpRequest request = readRequest(client);
                            try {
                                processRequest(request, client);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    };


                    service.execute(r);

                    /*if (runner != null) {
                        runner.interrupt();
                    }
                    runner = new Thread();
                    runner.start();*/
                } finally {
                    processing.unlock();
                }

            } catch (SocketTimeoutException e) {
                Log.w(LOG_TAG, "Socket timeout");
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error connecting to client", e);
            }
        }
        Log.w(LOG_TAG, "Proxy interrupted. Shutting down.");
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

            headers = new HashMap<String, String>();

            String inputLine;
            while (!(inputLine = reader.readLine()).equals("")) {
                Log.i(LOG_TAG, "ZZZZ" + inputLine);
                headers.put(inputLine.split(": ")[0],
                        inputLine.split(": ")[1]);
            }

            // Map<String, String> map = parseHTTPHeaders(is);


            //headers = HttpParser.parseHeaders(is, "UTF-8");
            /*int ccc = is.available();
            Log.i(LOG_TAG, ccc+ " bytes available");
            byte[] bff = new byte[ccc];
            int read = is.read(bff, 0, bff.length);

            String string = new String(bff);//CharStreams.toString(new InputStreamReader(is, "UTF-8"));
            //String s = IOUtils.toString(is);
            Log.i(LOG_TAG, string);*/

        } catch (IOException e) {
            Log.e(LOG_TAG, "Error parsing request", e);
            return request;
        }

        if (firstLine == null) {
            Log.i(LOG_TAG, "Proxy client closed connection without a request.");
            return request;
        }

        StringTokenizer st = new StringTokenizer(firstLine);
        String method = st.nextToken();
        String uri = st.nextToken();
        String realUri = uri.substring(1);

        /*Uri parsed = Uri.parse(realUri);

        Properties props = new Properties();
        props.setProperty("artist", parsed.getQueryParameter("artist"));
        props.setProperty("track", parsed.getQueryParameter("track"));

        outputStream = streamFactory.createOutputStream(props);*/

        Log.d(LOG_TAG, "Client requested: " + realUri);
        request = new BasicHttpRequest(method, realUri);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }
//        request.addHeader();

        //request.setHeaders((Header[]) headers);

        return request;
    }

    public static Map<String, String> parseHTTPHeaders(InputStream inputStream)
            throws IOException {
        int charRead;
        StringBuffer sb = new StringBuffer();
        while (true) {
            sb.append((char) (charRead = inputStream.read()));
            if ((char) charRead == '\r') {            // if we've got a '\r'
                sb.append((char) inputStream.read()); // then write '\n'
                charRead = inputStream.read();        // read the next char;
                if (charRead == '\r') {                  // if it's another '\r'
                    sb.append((char) inputStream.read());// write the '\n'
                    break;
                } else {
                    sb.append((char) charRead);
                }
            }
        }

        String[] headersArray = sb.toString().split("\r\n");
        Map<String, String> headers = new HashMap<String, String>();
        for (int i = 1; i < headersArray.length - 1; i++) {
            headers.put(headersArray[i].split(": ")[0],
                    headersArray[i].split(": ")[1]);
        }

        return headers;
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
            Log.d(LOG_TAG, "Executing real request...");
            response = http.execute(method);
            Log.d(LOG_TAG, "Executed");
        } catch (ClientProtocolException e) {
            Log.e(LOG_TAG, "Error executing", e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error executing", e);
        }
        return response;
    }

    private void processRequest(HttpRequest request, Socket client)
            throws IllegalStateException, IOException {
        if (request == null) {
            return;
        }
        Log.d(LOG_TAG, "Processing");
        String url = request.getRequestLine().getUri();
        HttpResponse realResponse = download(url, request);
        if (realResponse == null) {
            return;
        }

        Uri parsed = Uri.parse(url);

        Properties props = new Properties();
        props.setProperty("artist", parsed.getQueryParameter("artist"));
        props.setProperty("track", parsed.getQueryParameter("track"));

        OutputStream outputStream = null;//streamFactory.createOutputStream(props);

        InputStream data = realResponse.getEntity().getContent();
        StatusLine line = realResponse.getStatusLine();
        HttpResponse response = new BasicHttpResponse(line);
        response.setHeaders(realResponse.getAllHeaders());

        Log.d(LOG_TAG, "Reading headers");
        StringBuilder httpString = new StringBuilder();
        httpString.append(response.getStatusLine().toString());

        httpString.append("\r\n"); //was httpString.append("\n");
        for (Header h : response.getAllHeaders()) {
            httpString.append(h.getName()).append(": ").append(h.getValue()).append("\r\n"); //was httpString.append("\n");
        }
        httpString.append("\r\n"); //was httpString.append("\n");
        Log.d(LOG_TAG, "Copying headers done");

        try {
            int slicer = 0;
            int readBytes = 0;
            Log.d(LOG_TAG, Thread.currentThread().getName() + "Writing to client...");
            byte[] buffer = httpString.toString().getBytes();
            client.getOutputStream().write(buffer, 0, buffer.length);
            byte[] buff = new byte[1024 * 64];

            while (isRunning
/*                    && streamingAllowed
                    && !Thread.currentThread().isInterrupted()*/
                    && (readBytes /*= data.read(buff, 0, buff.length)*/) != -1) {

                if (slicer % 25 == 0)
                Log.d("WWWW", Thread.currentThread().getName() + "Reading bytes...");

                readBytes = data.read(buff, 0, buff.length);
                if (readBytes == -1) break;

                if (slicer % 25 == 0)
                Log.d("WWWW", Thread.currentThread().getName() + "Read bytes: " + readBytes);

                client.getOutputStream().write(buff, 0, readBytes);
                if (slicer % 25 == 0)
                Log.d("WWWW", Thread.currentThread().getName() + "Written bytes: " + readBytes);

                slicer++;

                //Log.d("WWWW", "Checking flags");
                if (!streamingAllowed || Thread.currentThread().isInterrupted()) {
                    break;
                }
                //Log.d("WWWW", "End of cycle");

                //outputStream.write(buff, 0, readBytes);
            }
        } catch (SocketException e) {
            Log.w(LOG_TAG, Thread.currentThread().getName() + "Looks like MediaPlayer has disconnected", e);
        } catch (Exception e) {
            Log.e(LOG_TAG, Thread.currentThread().getName() + "Exception!", e);
        } finally {
            Log.w(LOG_TAG, Thread.currentThread().getName() + "Stopped streaming");
            streamingAllowed = true; //allow it back

            //outputStream.close();

            /*realResponse.getEntity().consumeContent();
            Log.w(LOG_TAG, "Consumed content");

			if (data != null) {
				data.close();
                Log.w(LOG_TAG, "Closed input stream");
			}*/
            client.getOutputStream().flush();
            client.getOutputStream().close();


            //client.close();
            Log.w(LOG_TAG, "Closed client");
        }
    }
}
