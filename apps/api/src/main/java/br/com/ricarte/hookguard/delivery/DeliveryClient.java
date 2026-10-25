package br.com.ricarte.hookguard.delivery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import br.com.ricarte.hookguard.config.HookguardProperties;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeliveryClient {

    private static final int ABSOLUTE_RESPONSE_CAP = 1_048_576;
    private static final int MAX_INFORMATIONAL = 8;
    private static final int MAX_STATUS_LINE = 8192;
    private static final int MAX_HEADER_BYTES = 32768;
    private static final int MAX_HEADER_LINES = 100;

    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, daemonThreads("hookguard-delivery-watchdog"));
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(
            0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), daemonThreads("hookguard-delivery-dns"));

    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private final NameResolver nameResolver;
    private final boolean allowHttp;
    private final int connectTimeoutMs;
    private final int maxResponseBytes;
    private final SSLSocketFactory sslSocketFactory;

    @Autowired
    public DeliveryClient(
            NameResolver nameResolver,
            HookguardProperties properties,
            @Value("${hookguard.delivery.allow-http:false}") boolean allowHttp
    ) {
        this(
                nameResolver,
                allowHttp,
                properties.delivery().connectTimeoutMs(),
                properties.delivery().maxResponseBytes()
        );
    }

    public DeliveryClient(NameResolver nameResolver, boolean allowHttp, int connectTimeoutMs, int maxResponseBytes) {
        this(nameResolver, allowHttp, connectTimeoutMs, maxResponseBytes, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    DeliveryClient(
            NameResolver nameResolver,
            boolean allowHttp,
            int connectTimeoutMs,
            int maxResponseBytes,
            SSLSocketFactory sslSocketFactory
    ) {
        this.sslSocketFactory = sslSocketFactory;
        this.nameResolver = nameResolver;
        this.allowHttp = allowHttp;
        this.connectTimeoutMs = connectTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
    }

    public DeliveryResult post(
            String destinationUrl,
            byte[] body,
            String contentType,
            Map<String, String> headers,
            int timeoutMs
    ) {
        Deadline deadline = Deadline.after(Math.max(timeoutMs, 1));
        try {
            DestinationUrlValidator.ResolvedEndpoint endpoint = resolveWithin(destinationUrl, deadline);
            return exchange(endpoint, body == null ? new byte[0] : body, contentType, headers, deadline);
        } catch (HostUnresolvedException ex) {
            return new DeliveryResult(null, "dns_unresolved", null, null);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (DeadlineExceededException ex) {
            return new DeliveryResult(null, "timeout: total delivery deadline exceeded", null, null);
        } catch (Exception ex) {
            if (deadline.expired() || ex instanceof SocketTimeoutException) {
                return new DeliveryResult(null, "timeout: total delivery deadline exceeded", null, null);
            }
            return new DeliveryResult(null, safeMessage(ex.getMessage()), null, null);
        }
    }

    private DestinationUrlValidator.ResolvedEndpoint resolveWithin(String destinationUrl, Deadline deadline)
            throws IOException {
        Future<DestinationUrlValidator.ResolvedEndpoint> pending;
        try {
            pending = DNS.submit(() -> DestinationUrlValidator.resolve(destinationUrl, allowHttp, nameResolver));
        } catch (RejectedExecutionException ex) {
            throw new IOException("dns_busy");
        }
        try {
            return pending.get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new DeadlineExceededException();
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IOException("dns_failed");
        }
    }

    private DeliveryResult exchange(
            DestinationUrlValidator.ResolvedEndpoint endpoint,
            byte[] body,
            String contentType,
            Map<String, String> headers,
            Deadline deadline
    ) throws IOException {
        Socket socket = new Socket();
        SSLSocket tls = null;
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
                () -> {
                    expired.set(true);
                    closeQuietly(socket);
                },
                Math.max(deadline.remainingMs(), 1),
                TimeUnit.MILLISECONDS
        );
        try {
            socket.connect(
                    new InetSocketAddress(endpoint.address(), endpoint.port()),
                    (int) Math.max(1, Math.min(connectTimeoutMs, deadline.remainingMs()))
            );
            InputStream in;
            OutputStream out;
            if ("https".equalsIgnoreCase(endpoint.uri().getScheme())) {
                socket.setSoTimeout((int) Math.max(1, deadline.remainingMs()));
                tls = (SSLSocket) sslSocketFactory.createSocket(socket, endpoint.host(), endpoint.port(), true);
                applyTls(tls, endpoint.host());
                tls.startHandshake();
                in = tls.getInputStream();
                out = tls.getOutputStream();
            } else {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            }
            writeRequest(out, endpoint, body, contentType, headers);
            return readResponse(in, socket, deadline);
        } catch (IOException ex) {
            if (expired.get()) {
                throw new DeadlineExceededException();
            }
            throw ex;
        } finally {
            watchdog.cancel(false);
            if (tls != null) {
                closeQuietly(tls);
            }
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void applyTls(SSLSocket tls, String host) {
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!ipLiteral(host)) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        tls.setSSLParameters(parameters);
        List<String> supported = List.of(tls.getSupportedProtocols());
        String[] enabled = List.of("TLSv1.3", "TLSv1.2").stream()
                .filter(supported::contains)
                .toArray(String[]::new);
        if (enabled.length > 0) {
            tls.setEnabledProtocols(enabled);
        }
    }

    private void writeRequest(
            OutputStream out,
            DestinationUrlValidator.ResolvedEndpoint endpoint,
            byte[] body,
            String contentType,
            Map<String, String> headers
    ) throws IOException {
        String path = endpoint.uri().getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = endpoint.uri().getRawQuery();
        String target = query == null || query.isEmpty() ? path : path + "?" + query;
        StringBuilder head = new StringBuilder();
        head.append("POST ").append(target).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(hostHeader(endpoint)).append("\r\n");
        if (contentType != null && !contentType.isBlank() && !contentType.contains("\r") && !contentType.contains("\n")) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!headerToken(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("content-type") || lower.equals("content-length")
                        || lower.equals("connection") || lower.equals("transfer-encoding")) {
                    continue;
                }
                if (entry.getValue().indexOf('\r') >= 0 || entry.getValue().indexOf('\n') >= 0) {
                    continue;
                }
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private DeliveryResult readResponse(InputStream rawIn, Socket socket, Deadline deadline) throws IOException {
        InputStream in = new DeadlineInputStream(rawIn, socket, deadline);
        int informational = 0;
        int status;
        Map<String, List<String>> responseHeaders;
        while (true) {
            status = readStatus(in);
            responseHeaders = readHeaders(in);
            if (status >= 100 && status < 200) {
                if (status == 101) {
                    throw new IOException("unexpected protocol switch");
                }
                if (++informational > MAX_INFORMATIONAL) {
                    throw new IOException("too many informational responses");
                }
                continue;
            }
            break;
        }
        byte[] payload = status == 204 || status == 304
                ? new byte[0]
                : readPayload(in, responseHeaders);
        String snippet = new String(payload, StandardCharsets.UTF_8);
        return new DeliveryResult(status, null, snippet, parseRetryAfter(first(responseHeaders, "retry-after")));
    }

    private int readStatus(InputStream in) throws IOException {
        String statusLine = readLine(in, MAX_STATUS_LINE);
        if (statusLine.isEmpty()) {
            throw new IOException("empty response");
        }
        if (!statusLine.startsWith("HTTP/1.")) {
            throw new IOException("invalid response status");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || parts[1].length() != 3) {
            throw new IOException("invalid response status");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IOException("invalid response status");
        }
        if (status < 100 || status > 599) {
            throw new IOException("invalid response status");
        }
        return status;
    }

    private Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        int count = 0;
        while (true) {
            String line = readLine(in, MAX_HEADER_BYTES - total);
            if (line.isEmpty()) {
                return headers;
            }
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) {
                throw new IOException("response headers too large");
            }
            if (++count > MAX_HEADER_LINES) {
                throw new IOException("too many response headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                throw new IOException("malformed response header");
            }
            String name = line.substring(0, colon);
            if (!headerToken(name)) {
                throw new IOException("malformed response header");
            }
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private byte[] readPayload(InputStream in, Map<String, List<String>> headers) throws IOException {
        int cap = Math.min(Math.max(maxResponseBytes, 1), ABSOLUTE_RESPONSE_CAP);
        List<String> encodings = headers.get("transfer-encoding");
        List<String> lengths = headers.get("content-length");
        if (encodings != null) {
            if (lengths != null) {
                throw new IOException("conflicting message framing");
            }
            String joined = String.join(",", encodings).toLowerCase(Locale.ROOT).replace(" ", "");
            if (!joined.equals("chunked") && !joined.endsWith(",chunked")) {
                throw new IOException("unsupported transfer encoding");
            }
            return readChunked(in, cap);
        }
        if (lengths != null) {
            long declared = declaredLength(lengths);
            int wanted = (int) Math.min(declared, cap);
            byte[] data = in.readNBytes(wanted);
            if (data.length < wanted) {
                throw new IOException("truncated response body");
            }
            return data;
        }
        return in.readNBytes(cap);
    }

    private static long declaredLength(List<String> values) throws IOException {
        Long declared = null;
        for (String value : values) {
            for (String piece : value.split(",")) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty() || trimmed.length() > 15 || !trimmed.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                    throw new IOException("invalid content length");
                }
                long parsed = Long.parseLong(trimmed);
                if (declared != null && declared != parsed) {
                    throw new IOException("conflicting content length");
                }
                declared = parsed;
            }
        }
        if (declared == null) {
            throw new IOException("invalid content length");
        }
        return declared;
    }

    private byte[] readChunked(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, MAX_STATUS_LINE);
            int semi = sizeLine.indexOf(';');
            String sizeToken = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (sizeToken.isEmpty() || sizeToken.length() > 8
                    || !sizeToken.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
                throw new IOException("invalid chunk size");
            }
            int size = Integer.parseInt(sizeToken, 16);
            if (size == 0) {
                break;
            }
            int remaining = Math.min(size, cap - out.size());
            byte[] data = in.readNBytes(remaining);
            if (data.length < remaining) {
                throw new IOException("truncated chunked body");
            }
            out.write(data);
            if (remaining < size || out.size() >= cap) {
                return out.toByteArray();
            }
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("invalid chunk terminator");
            }
        }
        int trailerBytes = 0;
        while (true) {
            String trailer = readLine(in, MAX_HEADER_BYTES - trailerBytes);
            if (trailer.isEmpty()) {
                return out.toByteArray();
            }
            trailerBytes += trailer.length() + 2;
        }
    }

    private String readLine(InputStream in, int limit) throws IOException {
        if (limit <= 0) {
            throw new IOException("response headers too large");
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current == -1) {
                throw new IOException("connection closed before the response was complete");
            }
            if (previous == '\r' && current == '\n') {
                byte[] all = line.toByteArray();
                return new String(all, 0, all.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            if (line.size() > limit) {
                throw new IOException("response line too large");
            }
            previous = current;
        }
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return Duration.ZERO;
            }
            return Duration.ofSeconds(Math.min(seconds, 7L * 24 * 3600));
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration until = Duration.between(ZonedDateTime.now().toInstant(), date.toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }

    private static String hostHeader(DestinationUrlValidator.ResolvedEndpoint endpoint) {
        boolean https = "https".equalsIgnoreCase(endpoint.uri().getScheme());
        boolean defaultPort = (https && endpoint.port() == 443) || (!https && endpoint.port() == 80);
        String host = endpoint.host().indexOf(':') >= 0 ? "[" + endpoint.host() + "]" : endpoint.host();
        return defaultPort ? host : host + ":" + endpoint.port();
    }

    private static boolean ipLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (!(Character.isDigit(ch) || ch == '.')) {
                return false;
            }
        }
        return !host.isEmpty();
    }

    private static boolean headerToken(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "delivery_failed";
        }
        String flat = message.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }

    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class DeadlineExceededException extends IOException {
        DeadlineExceededException() {
            super("deadline exceeded");
        }
    }

    private static final class Deadline {
        private final long endNanos;

        private Deadline(long endNanos) {
            this.endNanos = endNanos;
        }

        static Deadline after(long millis) {
            return new Deadline(System.nanoTime() + millis * 1_000_000L);
        }

        long remainingMs() {
            return Math.max(0, (endNanos - System.nanoTime()) / 1_000_000L);
        }

        boolean expired() {
            return System.nanoTime() - endNanos >= 0;
        }
    }

    /** Applies the remaining total budget to every blocking read, including slow trickles. */
    private static final class DeadlineInputStream extends InputStream {
        private final InputStream delegate;
        private final Socket socket;
        private final Deadline deadline;

        DeadlineInputStream(InputStream delegate, Socket socket, Deadline deadline) {
            this.delegate = delegate;
            this.socket = socket;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (deadline.expired()) {
                throw new DeadlineExceededException();
            }
            socket.setSoTimeout((int) Math.max(1, deadline.remainingMs()));
            return delegate.read(buffer, offset, length);
        }
    }

    public record DeliveryResult(Integer httpStatus, String errorMessage, String responseBodySnippet, Duration retryAfter) {
        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }
}
