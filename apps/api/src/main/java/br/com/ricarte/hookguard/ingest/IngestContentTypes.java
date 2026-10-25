package br.com.ricarte.hookguard.ingest;

import java.util.Locale;

public final class IngestContentTypes {

    private IngestContentTypes() {
    }

    public static boolean allowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/json")
                || type.equals("text/plain")
                || type.equals("application/xml")
                || type.equals("text/xml")
                || type.equals("application/x-www-form-urlencoded")) {
            return true;
        }
        return type.startsWith("application/") && type.endsWith("+json");
    }
}
