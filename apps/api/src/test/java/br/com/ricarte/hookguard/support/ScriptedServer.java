package br.com.ricarte.hookguard.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Local controlled destination that speaks raw HTTP, so tests can script exact wire behaviour. */
public final class ScriptedServer implements AutoCloseable {

    public interface Handler {
        void handle(Socket socket, Request request) throws Exception;
    }

    public record Request(String head, byte[] body, String sni) {
        public String header(String name) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    private final ServerSocket serverSocket;
    private final boolean tls;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Thread acceptor;
    private volatile boolean readBody = true;

    private ScriptedServer(ServerSocket serverSocket, boolean tls, Handler handler) {
        this.serverSocket = serverSocket;
        this.tls = tls;
        this.acceptor = new Thread(() -> acceptLoop(handler), "scripted-server");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public static ScriptedServer plain(Handler handler) throws IOException {
        return new ScriptedServer(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), false, handler);
    }

    public static ScriptedServer plainIpv6(Handler handler) throws IOException {
        return new ScriptedServer(new ServerSocket(0, 50, InetAddress.getByName("::1")), false, handler);
    }

    public static ScriptedServer tls(Handler handler) throws Exception {
        SSLContext context = serverContext();
        ServerSocket socket = context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        return new ScriptedServer(socket, true, handler);
    }

    /** Server that accepts the connection but never reads from it. */
    public static ScriptedServer silent() throws IOException {
        ScriptedServer server = new ScriptedServer(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), false,
                (socket, request) -> Thread.sleep(30_000));
        server.readBody = false;
        return server;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public List<Request> requests() {
        return requests;
    }

    public static SSLSocketFactory clientFactoryTrustingTestCertificate() throws Exception {
        KeyStore store = loadStore();
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    private static KeyStore loadStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = ScriptedServer.class.getResourceAsStream("/tls/test-server.p12")) {
            store.load(in, "changeit".toCharArray());
        }
        return store;
    }

    private static SSLContext serverContext() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(loadStore(), "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private void acceptLoop(Handler handler) {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> serve(socket, handler), "scripted-server-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException ex) {
                return;
            }
        }
    }

    private void serve(Socket socket, Handler handler) {
        try (socket) {
            String sni = null;
            if (tls) {
                SSLSocket ssl = (SSLSocket) socket;
                ssl.startHandshake();
                if (ssl.getHandshakeSession() == null && ssl.getSession() instanceof ExtendedSSLSession session) {
                    for (SNIServerName name : session.getRequestedServerNames()) {
                        if (name instanceof SNIHostName host) {
                            sni = host.getAsciiName();
                        }
                    }
                }
            }
            Request request = readBody ? readRequest(socket.getInputStream(), sni) : new Request("", new byte[0], sni);
            requests.add(request);
            handler.handle(socket, request);
        } catch (Exception ignored) {
        }
    }

    private static Request readRequest(InputStream in, String sni) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : (b == '\r' ? 1 : 0);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        int length = 0;
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        return new Request(text, body, sni);
    }

    public static void write(Socket socket, String raw) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
