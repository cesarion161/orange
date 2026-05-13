package com.ai.orange.webhook;

import com.ai.orange.github.GithubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GithubWebhookHmacFilterTest {

    private static final String SECRET = "shh-very-secret";
    private static final String PATH = "/webhooks/github";
    private static final String BODY = "{\"action\":\"opened\"}";

    private final GithubWebhookHmacFilter filter =
            new GithubWebhookHmacFilter(props(SECRET, PATH));

    private static GithubProperties props(String secret, String path) {
        return new GithubProperties("", "", "main", List.of(), Duration.ofDays(7), secret, path);
    }

    @Test
    void valid_signature_passes_through() throws Exception {
        MockHttpServletRequest req = makeRequest(sign(BODY, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void wrong_signature_is_rejected() throws Exception {
        MockHttpServletRequest req = makeRequest("sha256=" + "00".repeat(32));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void missing_signature_is_rejected() throws Exception {
        MockHttpServletRequest req = makeRequest(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void unconfigured_secret_yields_503() throws Exception {
        GithubWebhookHmacFilter unconfigured =
                new GithubWebhookHmacFilter(props("", PATH));
        MockHttpServletRequest req = makeRequest(sign(BODY, SECRET));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        unconfigured.doFilter(req, res, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void other_paths_are_skipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/something/else");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    private static MockHttpServletRequest makeRequest(String signature) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", PATH);
        req.setRequestURI(PATH);
        req.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            req.addHeader("X-Hub-Signature-256", signature);
        }
        return req;
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
