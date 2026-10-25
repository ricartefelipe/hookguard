package br.com.ricarte.hookguard.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PayloadMaskerTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void masksStringFieldsAndKeepsOtherValues() {
        String preview = PayloadMasker.preview(bytes("{\"password\":\"super-secret\",\"ok\":true,\"order\":1}"), "application/json");
        assertThat(preview).doesNotContain("super-secret").contains("\"ok\":true").contains("\"order\":1");
    }

    @Test
    void masksNumbersArraysAndObjectsUnderSensitiveKeys() {
        String json = "{\"cvv\":123,\"token\":[\"s1\",\"s2\"],\"secret\":{\"a\":\"nested-secret\"},\"keep\":\"visible\"}";
        String preview = PayloadMasker.preview(bytes(json), "application/json");
        assertThat(preview).doesNotContain("123").doesNotContain("s1").doesNotContain("nested-secret").contains("visible");
    }

    @Test
    void masksNestedAndEncodedFieldNames() {
        String json = "{\"a\":{\"b\":[{\"pass\\u0077ord\":\"enc-secret\"},{\"Api-Key\":\"k-123\"}]}}";
        String preview = PayloadMasker.preview(bytes(json), "application/json");
        assertThat(preview).doesNotContain("enc-secret").doesNotContain("k-123");
    }

    @Test
    void masksValuesWithEscapesAndMultibyteBeforeTruncating() {
        String secret = "ação\\\"é\\\\segredo-longo-😀";
        String json = "{\"filler\":\"" + "x".repeat(480) + "\",\"password\":\"" + secret + "\"}";
        String preview = PayloadMasker.preview(bytes(json), "application/json");
        assertThat(preview).doesNotContain("segredo-longo").doesNotContain("ação");
        assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(500);
    }

    @Test
    void secretCrossingThePreviewBoundaryIsNotShown() {
        String json = "{\"filler\":\"" + "x".repeat(480) + "\",\"password\":\"boundary-secret-value\"}";
        String preview = PayloadMasker.preview(bytes(json), "application/json");
        assertThat(preview).doesNotContain("boundary").doesNotContain("secret-value");
    }

    @Test
    void jsonStringContainingJsonIsMasked() {
        String json = "{\"payload\":\"{\\\"password\\\":\\\"inner-secret\\\"}\",\"h\":\"Bearer abc.def-123\"}";
        String preview = PayloadMasker.preview(bytes(json), "application/json");
        assertThat(preview).doesNotContain("inner-secret").doesNotContain("abc.def-123");
    }

    @Test
    void malformedOrUnknownContentIsWithheldNotShownRaw() {
        assertThat(PayloadMasker.preview(bytes("{\"password\":\"cut-secret"), "application/json"))
                .isEqualTo(PayloadMasker.WITHHELD);
        assertThat(PayloadMasker.preview(bytes("password is hunter2"), "text/plain")).isEqualTo(PayloadMasker.WITHHELD);
        assertThat(PayloadMasker.preview(new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'}, "application/json"))
                .isEqualTo(PayloadMasker.WITHHELD);
        assertThat(PayloadMasker.preview(bytes("{\"password\":\"a\",\"password\":\"b\"}"), "application/json"))
                .isEqualTo(PayloadMasker.WITHHELD);
    }

    @Test
    void oversizedInputIsWithheld() {
        byte[] big = new byte[PayloadMasker.INPUT_LIMIT_BYTES + 1];
        assertThat(PayloadMasker.preview(big, "application/json")).isEqualTo(PayloadMasker.TOO_LARGE);
    }

    @Test
    void formEncodedFieldsAreMasked() {
        String preview = PayloadMasker.preview(bytes("user=ana&pass%77ord=hunter2&note=ok"), "application/x-www-form-urlencoded");
        assertThat(preview).doesNotContain("hunter2").contains("user=ana").contains("note=ok");
    }

    @Test
    void originalBytesAreNotModified() {
        byte[] original = bytes("{\"password\":\"keep-me\"}");
        byte[] copy = original.clone();
        PayloadMasker.preview(original, "application/json");
        assertThat(original).isEqualTo(copy);
    }

    @Test
    void responseSnippetIsMaskedBeforeTruncation() {
        String body = "{\"filler\":\"" + "y".repeat(1990) + "\",\"token\":\"resp-secret-token\"}";
        String snippet = PayloadMasker.maskResponse(body, 2000);
        assertThat(snippet).doesNotContain("resp-secret").doesNotContain("secret-token");
        assertThat(snippet.length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void responsesThatAreNotJsonAreNotKept() {
        assertThat(PayloadMasker.maskResponse("error: Authorization: Bearer abc123 password=pw1", 2000)).isEqualTo(PayloadMasker.RESPONSE_WITHHELD);
        assertThat(PayloadMasker.maskResponse("<html><body>token=xyz</body></html>", 2000)).isEqualTo(PayloadMasker.RESPONSE_WITHHELD);
        assertThat(PayloadMasker.maskResponse("{\"token\":\"cut-tok", 2000)).doesNotContain("cut-tok").startsWith("{");
        assertThat(PayloadMasker.maskResponse("{\"a\":{\"cvv\":[1,2,3]}, \"b\": ", 2000)).doesNotContain("1,2,3");
    }

    @Test
    void storedLegacyTextIsNotShownRaw() {
        assertThat(PayloadMasker.mask("upstream said password=hunter2")).isEqualTo(PayloadMasker.RESPONSE_WITHHELD);
        assertThat(PayloadMasker.mask(PayloadMasker.RESPONSE_WITHHELD)).isEqualTo(PayloadMasker.RESPONSE_WITHHELD);
        assertThat(PayloadMasker.mask("{\"ok\":true,\"secret\":\"abc")).doesNotContain("abc").contains("\"ok\":true");
    }

    @Test
    void looseMaskHandlesTruncatedStoredSnippets() {
        assertThat(PayloadMasker.mask("{\"a\":1,\"cvv\":12")).doesNotContain("12\"").doesNotContain(":12");
        assertThat(PayloadMasker.mask("{\"password\":\"abc")).doesNotContain("abc");
        assertThat(PayloadMasker.mask("{\"secret\":{\"k\":\"v-secret\"")).doesNotContain("v-secret");
        assertThat(PayloadMasker.mask("{\"password\":")).endsWith("\"••••\"");
        assertThat(PayloadMasker.mask("{\"pass\\u0077ord\":\"zz-secret\"}")).doesNotContain("zz-secret");
    }

    @Test
    void emptyAndNullAreHandled() {
        assertThat(PayloadMasker.preview(null)).isEmpty();
        assertThat(PayloadMasker.preview(new byte[0])).isEmpty();
        assertThat(PayloadMasker.mask(null)).isNull();
    }
}
