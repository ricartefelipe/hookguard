package br.com.ricarte.hookguard.delivery;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

public final class DestinationUrlValidator {

    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "instance-data.ec2.internal"
    );

    private DestinationUrlValidator() {
    }

    public static void validate(String rawUrl, boolean allowHttp) {
        resolve(rawUrl, allowHttp, DefaultLookup::resolve);
    }

    public static ResolvedEndpoint resolve(String rawUrl, boolean allowHttp, NameResolver resolver) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("destinationUrl is required");
        }
        String raw = rawUrl.trim();
        if (raw.indexOf('@') >= 0 || raw.indexOf('\\') >= 0 || hasWhitespace(raw)) {
            throw new IllegalArgumentException("destinationUrl is invalid");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("destinationUrl is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean http = "http".equals(scheme);
        if (!https && !(http && allowHttp)) {
            throw new IllegalArgumentException("destinationUrl must use https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("destinationUrl is invalid");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || !hostMatches(uri, host)) {
            throw new IllegalArgumentException("destinationUrl host is required");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(normalized) || normalized.endsWith(".metadata.google.internal")) {
            throw new IllegalArgumentException("destinationUrl host is not allowed");
        }
        if (ambiguousNumericHost(normalized)) {
            throw new IllegalArgumentException("destinationUrl host is not allowed");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = https ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("destinationUrl is invalid");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("destinationUrl host cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("destinationUrl host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address, allowHttp)) {
                throw new IllegalArgumentException("destinationUrl host is not allowed");
            }
        }
        return new ResolvedEndpoint(uri, addresses[0], host, port);
    }

    static boolean isBlocked(InetAddress original, boolean allowPrivate) {
        InetAddress address = unwrapMapped(original);
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            int first = raw[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return !allowPrivate;
            }
        }
        if (raw.length == 4) {
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            int b2 = raw[2] & 0xff;
            if (b0 == 0 || b0 == 255) {
                return true;
            }
            if (b0 == 192 && b1 == 0 && b2 == 0) {
                return true;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return !allowPrivate;
            }
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return !allowPrivate;
        }
        return false;
    }

    private static InetAddress unwrapMapped(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return address;
            }
        }
        if ((raw[10] & 0xff) != 0xff || (raw[11] & 0xff) != 0xff) {
            return address;
        }
        byte[] v4 = new byte[] {raw[12], raw[13], raw[14], raw[15]};
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException ex) {
            return address;
        }
    }

    private static boolean ambiguousNumericHost(String host) {
        if (host.indexOf("0x") >= 0) {
            return true;
        }
        if (host.chars().allMatch(Character::isDigit)) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length == 4) {
            for (String part : parts) {
                if (part.length() > 1 && part.startsWith("0")) {
                    return true;
                }
            }
        }
        return parts.length > 0 && parts.length < 4 && host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.');
    }

    private static boolean hostMatches(URI uri, String host) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return false;
        }
        String expected = host;
        if (host.indexOf(':') >= 0) {
            expected = "[" + host + "]";
        }
        return authority.equals(expected) || authority.startsWith(expected + ":");
    }

    private static boolean hasWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public record ResolvedEndpoint(URI uri, InetAddress address, String host, int port) {
    }

    private static final class DefaultLookup {
        private static InetAddress[] resolve(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }
    }
}
