package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.mockito.Mockito.mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BinaryProvider (ITI-68 Retrieve Document).
 * Exercises URL validation, consent gate, redirect logic, and audit recording.
 */
class BinaryProviderTest {

    SearchService searchService;
    ConsentGate consentGate;
    AuditService auditService;
    HttpServletResponse response;
    BinaryProvider provider;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        consentGate   = mock(ConsentGate.class);
        auditService  = mock(AuditService.class);
        response      = mock(HttpServletResponse.class);

        // Build with allowed hosts including our test host
        provider = new BinaryProvider(searchService, consentGate, auditService,
                "pacs.hospital.lk,docs.hospital.lk");
    }

    // ── getResourceType ──────────────────────────────────────────────────────

    @Test
    void getResourceType_returnsBinary() {
        assertThat(provider.getResourceType()).isEqualTo(Binary.class);
    }

    // ── happy path: redirect ─────────────────────────────────────────────────

    @Test
    void read_validPointer_issues307RedirectAndReturnsNull() {
        Pointer p = pointer("img-1", "PHN001",
                "https://pacs.hospital.lk/wado/img-1", Pointer.Status.CURRENT);
        when(searchService.findById("img-1")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());

        Binary result = provider.read(new IdType("img-1"), response);

        assertThat(result).isNull(); // returning null prevents HAPI from overwriting the status
        verify(response).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        verify(response).setHeader("Location", "https://pacs.hospital.lk/wado/img-1");
        verify(auditService).recordRetrieve("PHN001", "img-1", true);
    }

    // ── not found ────────────────────────────────────────────────────────────

    @Test
    void read_notFound_throws404() {
        when(searchService.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.read(new IdType("missing"), response))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── superseded pointer ────────────────────────────────────────────────────

    @Test
    void read_supersededPointer_throws410Gone() {
        Pointer p = pointer("doc-old", "PHN001",
                "https://pacs.hospital.lk/old", Pointer.Status.SUPERSEDED);
        when(searchService.findById("doc-old")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> provider.read(new IdType("doc-old"), response))
                .isInstanceOf(ResourceGoneException.class)
                .hasMessageContaining("superseded");

        verify(auditService).recordRetrieve("PHN001", "doc-old", false);
    }

    // ── URL validation: null, non-HTTPS, disallowed host ──────────────────────

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "nullContentUrl,          ,                             no content",
        "httpContentUrl,          http://pacs.hospital.lk/img, must use HTTPS",
        "disallowedHost,          https://evil.example.com/img, not in the allowed list"
    })
    void read_invalidContentUrl_throws404(String scenario, String contentUrl, String expectedMessage) {
        Pointer p = pointer("doc-1", "PHN001", contentUrl, Pointer.Status.CURRENT);
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> provider.read(new IdType("doc-1"), response))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(expectedMessage.trim());

        verify(auditService).recordRetrieve("PHN001", "doc-1", false);
    }

    // ── empty allowed-hosts list (blocks all) ─────────────────────────────────

    @Test
    void read_emptyAllowedHosts_blocksAll() {
        BinaryProvider blockAll = new BinaryProvider(searchService, consentGate, auditService, "");
        Pointer p = pointer("doc-1", "PHN001",
                "https://pacs.hospital.lk/img", Pointer.Status.CURRENT);
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());

        // With an empty allowedHosts set, the "!allowedHosts.isEmpty()" guard
        // is false so the host check is skipped → redirect is issued
        Binary result = blockAll.read(new IdType("doc-1"), response);
        assertThat(result).isNull();
        verify(response).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
    }

    // ── consent gate throws ───────────────────────────────────────────────────

    @Test
    void read_consentBlocked_throws404() {
        Pointer p = pointer("doc-1", "PHN001",
                "https://pacs.hospital.lk/img", Pointer.Status.CURRENT);
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doThrow(new ResourceNotFoundException("blocked"))
                .when(consentGate).checkRetrieve("PHN001", "doc-1", "Binary", "ITI-68");

        assertThatThrownBy(() -> provider.read(new IdType("doc-1"), response))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── invalid URI ──────────────────────────────────────────────────────────

    @Test
    void read_invalidUri_throws404() {
        // URI.create() throws IllegalArgumentException for strings with spaces
        Pointer p = pointer("doc-1", "PHN001",
                "https://pacs.hospital.lk/path with spaces", Pointer.Status.CURRENT);
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> provider.read(new IdType("doc-1"), response))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("invalid content URL");

        verify(auditService).recordRetrieve("PHN001", "doc-1", false);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Pointer pointer(String id, String phn, String contentUrl, Pointer.Status status) {
        Pointer.Builder b = Pointer.builder()
                .id(id).phn(phn).resourceType("ImagingStudy")
                .status(status).consent(Consent.defaultAccessible());
        if (contentUrl != null) {
            b.contentUrl(contentUrl);
        }
        return b.build();
    }
}
