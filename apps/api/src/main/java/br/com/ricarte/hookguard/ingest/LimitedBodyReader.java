package br.com.ricarte.hookguard.ingest;

import br.com.ricarte.hookguard.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;

public final class LimitedBodyReader {

    private LimitedBodyReader() {
    }

    public static byte[] read(InputStream input, int maxBytes) throws IOException {
        int limit = Math.max(1, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > limit) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
