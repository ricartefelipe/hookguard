package br.com.ricarte.hookguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void rateLimitedRequestIsRefusedBeforeItsBodyIsRead() throws Exception {
        IngestService service = mock(IngestService.class);
        org.mockito.Mockito.doThrow(new br.com.ricarte.hookguard.web.ApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate_limited", 5)).when(service).admit(any());
        IngestController controller = new IngestController(service, properties());
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.ingest("project-key", request))
                .isInstanceOf(br.com.ricarte.hookguard.web.ApiException.class);

        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).getInputStream();
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).accept(any(), any(), any(), any());
    }

    @Test
    void concurrencyGateRefusesWhenAllSlotsAreBusy() throws Exception {
        IngestService service = mock(IngestService.class);
        HookguardProperties base = properties();
        HookguardProperties oneSlot = new HookguardProperties(base.worker(), base.delivery(), base.retention(), base.billing(),
                base.auth(), base.rateLimit(), new HookguardProperties.Ingest(2048, 24, 1), base.totalrecall());
        IngestController controller = new IngestController(service, oneSlot);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            inside.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(service).admit(any());
        HttpServletRequest first = mock(HttpServletRequest.class);
        Thread holder = new Thread(() -> {
            try {
                controller.ingest("k", first);
            } catch (Exception ignored) {
            }
        });
        holder.start();
        try {
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> controller.ingest("k", mock(HttpServletRequest.class)))
                    .isInstanceOf(br.com.ricarte.hookguard.web.ApiException.class)
                    .extracting(ex -> ((br.com.ricarte.hookguard.web.ApiException) ex).getCode())
                    .isEqualTo("ingest_busy");
        } finally {
            release.countDown();
            holder.join();
        }
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
                new HookguardProperties.Ingest(2048, 24, 64),
                new HookguardProperties.Totalrecall("")
        );
    }
}
