package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.fhir.PointerFhirMapper;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentReferenceProvider — no Spring context, no HAPI server.
 * Exercises the search/read logic, consent gate, audit recording, and status parsing.
 */
class DocumentReferenceProviderTest {

    SearchService searchService;
    ConsentGate consentGate;
    PointerFhirMapper mapper;
    AuditService auditService;
    DocumentReferenceProvider provider;

    @BeforeEach
    void setUp() {
        searchService = Mockito.mock(SearchService.class);
        consentGate   = Mockito.mock(ConsentGate.class);
        mapper        = Mockito.mock(PointerFhirMapper.class);
        auditService  = Mockito.mock(AuditService.class);
        provider = new DocumentReferenceProvider(searchService, consentGate, mapper, auditService);
        SecurityContextHolder.clearContext();
    }

    // ── getResourceType ──────────────────────────────────────────────────────

    @Test
    void getResourceType_returnsDocumentReference() {
        assertThat(provider.getResourceType()).isEqualTo(DocumentReference.class);
    }

    // ── search — happy path ──────────────────────────────────────────────────

    @Test
    void search_byPatientParam_returnsSearchsetBundle() {
        Pointer p = pointer("doc-1", "PHN001");
        when(searchService.search(any())).thenReturn(List.of(p));
        when(consentGate.checkSearch(anyString(), anyString())).thenReturn(false);
        when(mapper.toFhir(p)).thenReturn(new DocumentReference());

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.getTotal()).isEqualTo(1);
        verify(auditService).recordSearch("ITI-67", "PHN001", true);
    }

    @Test
    void search_byPatientIdentifierParam_resolvesPhn() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(anyString(), anyString())).thenReturn(false);

        provider.search(null, new TokenParam("PHN002"),
                null, null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().phn()).isEqualTo("PHN002");
    }

    @Test
    void search_noPatientParams_phnIsNull() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(isNull(), anyString())).thenReturn(false);

        provider.search(null, null, null, null, null, null, null, null,
                null, new StringParam("John"));

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().phn()).isNull();
        assertThat(cap.getValue().patientName()).isEqualTo("John");
    }

    @Test
    void search_consentBlocked_returnsEmptyBundle() {
        when(consentGate.checkSearch("PHN001", "ITI-67")).thenReturn(true);

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        assertThat(bundle.getTotal()).isEqualTo(0);
        verify(searchService, never()).search(any());
        verify(auditService, never()).recordSearch(anyString(), anyString(), anyBoolean());
    }

    @Test
    void search_statusCurrent_parsedCorrectly() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        provider.search(new ReferenceParam("PHN001"), null,
                new TokenParam("current"), null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(Pointer.Status.CURRENT);
    }

    @Test
    void search_statusSuperseded_parsedCorrectly() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        provider.search(new ReferenceParam("PHN001"), null,
                new TokenParam("superseded"), null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(Pointer.Status.SUPERSEDED);
    }

    @Test
    void search_statusEnteredInError_parsedCorrectly() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        provider.search(new ReferenceParam("PHN001"), null,
                new TokenParam("entered-in-error"), null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(Pointer.Status.ENTERED_IN_ERROR);
    }

    @Test
    void search_unknownStatus_throws400() {
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> provider.search(
                new ReferenceParam("PHN001"), null,
                new TokenParam("invalid-status"), null, null, null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("invalid-status");
    }

    @Test
    void search_nullStatus_defaultsToCurrent() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        provider.search(new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(Pointer.Status.CURRENT);
    }

    @Test
    void search_periodParamFallsBackToDate() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        // date=null, period=something — period should be used
        DateRangeParam period = new DateRangeParam();
        period.setLowerBoundInclusive(java.util.Date.from(
                java.time.LocalDate.of(2024, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));

        provider.search(new ReferenceParam("PHN001"), null,
                null, null, period, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().dateFrom()).isEqualTo(java.time.LocalDate.of(2024, 1, 1));
    }

    @Test
    void search_dateTakesPrecedenceOverPeriod() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        DateRangeParam date = new DateRangeParam();
        date.setLowerBoundInclusive(java.util.Date.from(
                java.time.LocalDate.of(2024, 3, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        DateRangeParam period = new DateRangeParam();
        period.setLowerBoundInclusive(java.util.Date.from(
                java.time.LocalDate.of(2024, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));

        provider.search(new ReferenceParam("PHN001"), null,
                null, date, period, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        // date param wins
        assertThat(cap.getValue().dateFrom()).isEqualTo(java.time.LocalDate.of(2024, 3, 1));
    }

    @Test
    void search_searchThrows_auditsFailure() {
        when(consentGate.checkSearch(any(), any())).thenReturn(false);
        when(searchService.search(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> provider.search(
                new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(RuntimeException.class);

        verify(auditService).recordSearch("ITI-67", "PHN001", false);
    }

    @Test
    void search_withJwtPurposeOfUse_forwardsToQuery() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        // Set up a JWT in the SecurityContext with a purposeOfUse claim
        Jwt jwt = Mockito.mock(Jwt.class);
        when(jwt.getClaim("purposeOfUse")).thenReturn("TREAT");
        Authentication auth = Mockito.mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        provider.search(new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().purposeOfUse()).isEqualTo("TREAT");
    }

    @Test
    void search_withJwtPurposeOfUseAlternateClaimName_forwardsToQuery() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        Jwt jwt = Mockito.mock(Jwt.class);
        when(jwt.getClaim("purposeOfUse")).thenReturn(null);
        when(jwt.getClaim("purpose_of_use")).thenReturn("HPAYMT");
        Authentication auth = Mockito.mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        provider.search(new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().purposeOfUse()).isEqualTo("HPAYMT");
    }

    @Test
    void search_noJwt_purposeOfUseIsNull() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);
        // No authentication set — SecurityContextHolder has nothing

        provider.search(new ReferenceParam("PHN001"), null,
                null, null, null, null, null, null, null, null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        assertThat(cap.getValue().purposeOfUse()).isNull();
    }

    @Test
    void search_allOptionalParams_forwarded() {
        when(searchService.search(any())).thenReturn(List.of());
        when(consentGate.checkSearch(any(), any())).thenReturn(false);

        provider.search(
                new ReferenceParam("PHN001"), null,
                new TokenParam("current"),
                null, null,
                new TokenParam("18842-5"),
                new TokenParam("HIN001"),
                new StringParam("WP"),
                new TokenParam("IDENTIFIER-001"),
                null);

        ArgumentCaptor<lk.gov.moh.dhp.rl.common.SearchQuery> cap =
                ArgumentCaptor.forClass(lk.gov.moh.dhp.rl.common.SearchQuery.class);
        verify(searchService).search(cap.capture());
        lk.gov.moh.dhp.rl.common.SearchQuery q = cap.getValue();
        assertThat(q.docTypeLoinc()).isEqualTo("18842-5");
        assertThat(q.facilityHin()).isEqualTo("HIN001");
        assertThat(q.provinceCode()).isEqualTo("WP");
        assertThat(q.identifier()).isEqualTo("IDENTIFIER-001");
    }

    // ── read ─────────────────────────────────────────────────────────────────

    @Test
    void read_existingPointer_returnsDocumentReference() {
        Pointer p = pointer("doc-1", "PHN001");
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doNothing().when(consentGate).checkRetrieve(anyString(), anyString(), anyString(), anyString());
        DocumentReference dr = new DocumentReference();
        when(mapper.toFhir(p)).thenReturn(dr);

        DocumentReference result = provider.read(new IdType("doc-1"));

        assertThat(result).isSameAs(dr);
        verify(auditService).recordSearch("ITI-67", "PHN001", true);
    }

    @Test
    void read_notFound_throws404() {
        when(searchService.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.read(new IdType("missing")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void read_consentBlocked_throws404() {
        Pointer p = pointer("doc-1", "PHN001");
        when(searchService.findById("doc-1")).thenReturn(Optional.of(p));
        doThrow(new ResourceNotFoundException("blocked"))
                .when(consentGate).checkRetrieve("PHN001", "doc-1", "DocumentReference", "ITI-67");

        assertThatThrownBy(() -> provider.read(new IdType("doc-1")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Pointer pointer(String id, String phn) {
        return Pointer.builder()
                .id(id)
                .phn(phn)
                .resourceType("DocumentReference")
                .status(Pointer.Status.CURRENT)
                .consent(Consent.defaultAccessible())
                .build();
    }
}
