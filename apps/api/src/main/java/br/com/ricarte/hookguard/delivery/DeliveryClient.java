package br.com.ricarte.hookguard.delivery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import br.com.ricarte.hookguard.config.HookguardProperties;
import java.util.Locale;
import java.util.Map;
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
    private static final int SNIPPET_CHARS = 2000;

    private final NameResolver nameResolver;
    private final boolean allowHttp;
    private final int connectTimeoutMs;
    private final int maxResponseBytes;

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
        try {
            DestinationUrlValidator.ResolvedEndpoint endpoint = DestinationUrlValidator.resolve(
                    destinationUrl,
                    allowHttp,
                    nameResolver
            );
            return exchange(endpoint, body == null ? new byte[0] : body, contentType, headers, Math.max(timeoutMs, 1));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            return new DeliveryResult(null, safeMessage(ex.getMessage()), null, null);
        }
    }

    private DeliveryResult exchange(
            DestinationUrlValidator.ResolvedEndpoint endpoint,
            byte[] body,
            String contentType,
            Map<String, String> headers,
            int timeoutMs
    ) throws IOException {
        Socket socket = new Socket();
        SSLSocket tls = null;
        try {
            socket.connect(new InetSocketAddress(endpoint.address(), endpoint.port()), connectTimeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream in;
            OutputStream out;
            if ("https".equalsIgnoreCase(endpoint.uri().getScheme())) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                tls = (SSLSocket) factory.createSocket(socket, endpoint.host(), endpoint.port(), true);
                applyTls(tls, endpoint.host());
                tls.startHandshake();
                in = tls.getInputStream();
                out = tls.getOutputStream();
            } else {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            }
            writeRequest(out, endpoint, body, contentType, headers);
            return readResponse(in);
        } finally {
            if (tls != null) {
                tls.close();
            } else {
                socket.close();
            }
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

    private DeliveryResult readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine.isEmpty()) {
            throw new IOException("empty response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("invalid response status");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IOException("invalid response status");
        }
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) {
                break;
            }
            if (responseHeaders.size() > 100) {
                throw new IOException("too many response headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            responseHeaders.putIfAbsent(name, value);
        }
        byte[] payload = readPayload(in, responseHeaders);
        String snippet = new String(payload, StandardCharsets.UTF_8);
        if (snippet.length() > SNIPPET_CHARS) {
            snippet = snippet.substring(0, SNIPPET_CHARS);
        }
        return new DeliveryResult(status, null, snippet, parseRetryAfter(responseHeaders.get("retry-after")));
    }

    private byte[] readPayload(InputStream in, Map<String, String> headers) throws IOException {
        int cap = Math.min(Math.max(maxResponseBytes, 1), ABSOLUTE_RESPONSE_CAP);
        String encoding = headers.getOrDefault("transfer-encoding", "");
        if (encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in, cap);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int declared;
            try {
                declared = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException ex) {
                declared = cap;
            }
            if (declared < 0) {
                declared = 0;
            }
            return in.readNBytes(Math.min(declared, cap));
        }
        return in.readNBytes(cap);
    }

    private byte[] readChunked(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < cap) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            String sizeToken = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(sizeToken, 16);
            } catch (NumberFormatException ex) {
                break;
            }
            if (size <= 0) {
                break;
            }
            int remaining = Math.min(size, cap - out.size());
            out.write(in.readNBytes(remaining));
            if (remaining < size) {
                break;
            }
            readLine(in);
        }
        return out.toByteArray();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current == -1) {
                break;
            }
            if (previous == '\r' && current == '\n') {
                byte[] all = line.toByteArray();
                int length = Math.max(0, all.length - 1);
                return new String(all, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            if (line.size() > 16384) {
                throw new IOException("response header too large");
            }
            previous = current;
        }
        return line.toString(StandardCharsets.ISO_8859_1);
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

    public record DeliveryResult(Integer httpStatus, String errorMessage, String responseBodySnippet, Duration retryAfter) {
        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }
}
