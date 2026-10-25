package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.ricarte.hookguard.config.HookguardProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class IngestControllerFailureTest {

    @Test
    void databaseFailureIsNotReportedAsAccepted() throws Exception {
        IngestService service = mock(IngestService.class);
        when(service.accept(any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        IngestController controller = new IngestController(service, properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLength()).thenReturn(2);
        when(request.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            private final byte[] body = "{}".getBytes();
            private int index;

            @Override
            public int read() {
                if (index >= body.length) {
                    return -1;
                }
                return body[index++];
            }

            @Override
            public boolean isFinished() {
                return index >= body.length;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
            }
        });
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getContentType()).thenReturn("application/json");

        assertThatThrownBy(() -> controller.ingest("project-key", request))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static HookguardProperties properties() {
        return new HookguardProperties(
                new HookguardProperties.Worker(false, 1000, 10, 120),
                new HookguardProperties.Delivery(1000, 10000, 6, 65536, 21600, 24),
                new HookguardProperties.Retention(14),
                new HookguardProperties.Billing(5, 100, 1000, "", "", "", "", ""),
                new HookguardProperties.Auth(
                        "http://localhost:3000",
                        "http://localhost:8080",
                        "test@example.com",
                        20,
                        30,
                        false,
                        false,
                        "",
                        "",
                        ""
                ),
                new HookguardProperties.RateLimit(100),
                new HookguardProperties.Ingest(2048, 24),
                new HookguardProperties.Totalrecall("")
        );
    }
}
