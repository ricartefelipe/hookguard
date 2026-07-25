package br.com.ricarte.hookguard.delivery;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class DestinationUrlValidator {

    private DestinationUrlValidator() {
    }

    public static void validate(String rawUrl, boolean allowHttp) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("destinationUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("destinationUrl is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean httpAllowed = "http".equals(scheme) && allowHttp;
        if (!https && !httpAllowed) {
            throw new IllegalArgumentException("destinationUrl must use https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("destinationUrl host is required");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            if (!allowHttp) {
                throw new IllegalArgumentException("destinationUrl host is not allowed");
            }
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("destinationUrl host is not allowed");
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("destinationUrl host cannot be resolved");
        }
    }
}
