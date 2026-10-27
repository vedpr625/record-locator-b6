package lk.gov.moh.dhp.rl.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit tests for the mTLS header filter - no Spring context. */
class MtlsHeaderFilterTest {

    private static final String HEADER = "X-Client-Cert-Verified";

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        return req;
    }

    @Test
    void enabled_missingHeader_returns403DirectlyAndStopsChain() throws Exception {
        SecurityConfig.MtlsHeaderFilter filter = new SecurityConfig.MtlsHeaderFilter(true, HEADER, "SUCCESS");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/pointer-events"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        // must be a real 403 body, not a sendError() (which would be re-dispatched to /error)
        assertThat(res.getErrorMessage()).isNull();
        assertThat(res.getContentAsString()).contains("client certificate verification required");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabled_wrongHeaderValue_isRejected() throws Exception {
        SecurityConfig.MtlsHeaderFilter filter = new SecurityConfig.MtlsHeaderFilter(true, HEADER, "SUCCESS");
        MockHttpServletRequest req = post("/consent-events");
        req.addHeader(HEADER, "FAILED");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabled_successHeader_passesThrough() throws Exception {
        SecurityConfig.MtlsHeaderFilter filter = new SecurityConfig.MtlsHeaderFilter(true, HEADER, "SUCCESS");
        MockHttpServletRequest req = post("/pointer-events");
        req.addHeader(HEADER, "SUCCESS");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(req, res);
    }

    @Test
    void enabled_otherPaths_areNotChecked() throws Exception {
        SecurityConfig.MtlsHeaderFilter filter = new SecurityConfig.MtlsHeaderFilter(true, HEADER, "SUCCESS");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/fhir/DocumentReference");
        req.setRequestURI("/fhir/DocumentReference");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void disabled_neverRejects() throws Exception {
        SecurityConfig.MtlsHeaderFilter filter = new SecurityConfig.MtlsHeaderFilter(false, HEADER, "SUCCESS");
        MockHttpServletRequest req = post("/pointer-events");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
