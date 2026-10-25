package br.com.ricarte.hookguard.ingest;

import java.nio.charset.StandardCharsets;

final class IngestHashes {

    private IngestHashes() {
    }

    static String sha256Hex(String value) {
        return PayloadFingerprint.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
