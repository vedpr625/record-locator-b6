package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ListResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ListProvider (IHE MHD ITI-66 Find Document Lists).
 */
class ListProviderTest {

    SearchService searchService;
    ConsentGate consentGate;
    AuditService auditService;
    ListProvider provider;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        consentGate   = mock(ConsentGate.class);
        auditService  = mock(AuditService.class);
        provider = new ListProvider(searchService, consentGate, auditService);
        ReflectionTestUtils.setField(provider, "homeCommunityId", "urn:oid:2.16.840.1.113883.3.9999");
    }

    // ── getResourceType ──────────────────────────────────────────────────────

    @Test
    void getResourceType_returnsListResource() {
        assertThat(provider.getResourceType()).isEqualTo(ListResource.class);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void search_byPatient_returnsSubmissionSetBundle() {
        Pointer p = pointer("doc-1", "PHN001", "SS-001");
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of("SS-001", List.of(p)));

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), null, null);

        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.getTotal()).isEqualTo(1);
        verify(auditService).recordSearch("ITI-66", "PHN001", true);
    }

    @Test
    void search_byPatientIdentifier_resolvesPhn() {
        when(consentGate.checkSearch("PHN002", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN002", Pointer.Status.CURRENT))
                .thenReturn(Map.of());

        Bundle bundle = provider.search(
                null, new TokenParam("PHN002"),
                null, new TokenParam("current"), null, null);

        assertThat(bundle.getTotal()).isZero();
    }

    @Test
    void search_consentBlocked_returnsEmptyBundle() {
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(true);

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), null, null);

        assertThat(bundle.getTotal()).isZero();
        verify(searchService, never()).submissionSetsWithMembers(any(), any());
    }

    // ── no patient → exception ────────────────────────────────────────────────

    @Test
    void search_noPatient_throwsInvalidRequest() {
        TokenParam current = new TokenParam("current");

        assertThatThrownBy(() -> provider.search(null, null, null, current, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("patient");
    }

    // ── code validation ───────────────────────────────────────────────────────

    @Test
    void search_invalidCode_throwsInvalidRequest() {
        ReferenceParam patient = new ReferenceParam("PHN001");
        TokenParam wrongCode = new TokenParam("wrongcode");
        TokenParam current = new TokenParam("current");

        assertThatThrownBy(() -> provider.search(patient, null, wrongCode, current, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("submissionset");
    }

    @Test
    void search_validSubmissionsetCode_accepted() {
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of());

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                new TokenParam("submissionset"), new TokenParam("current"), null, null);

        assertThat(bundle).isNotNull();
    }

    // ── status parsing ────────────────────────────────────────────────────────

    @Test
    void search_statusSuperseded_parsedCorrectly() {
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.SUPERSEDED))
                .thenReturn(Map.of());

        provider.search(new ReferenceParam("PHN001"), null,
                null, new TokenParam("superseded"), null, null);

        verify(searchService).submissionSetsWithMembers("PHN001", Pointer.Status.SUPERSEDED);
    }

    @Test
    void search_statusEnteredInError_parsedCorrectly() {
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.ENTERED_IN_ERROR))
                .thenReturn(Map.of());

        provider.search(new ReferenceParam("PHN001"), null,
                null, new TokenParam("entered-in-error"), null, null);

        verify(searchService).submissionSetsWithMembers("PHN001", Pointer.Status.ENTERED_IN_ERROR);
    }

    @Test
    void search_unknownStatus_throws400() {
        // M-5: unknown status values now throw InvalidRequestException (400) instead of
        // silently falling back to CURRENT, matching the FHIR spec and DocumentReferenceProvider.
        ReferenceParam patient = new ReferenceParam("PHN001");
        TokenParam badStatus = new TokenParam("anything-else");

        assertThatThrownBy(() -> provider.search(patient, null, null, badStatus, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Unknown status value");
    }

    // ── identifier filter ─────────────────────────────────────────────────────

    @Test
    void search_identifierFilter_returnsOnlyMatchingSet() {
        Pointer p1 = pointer("doc-1", "PHN001", "SS-001");
        Pointer p2 = pointer("doc-2", "PHN001", "SS-002");
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of("SS-001", List.of(p1), "SS-002", List.of(p2)));

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), null, new TokenParam("SS-001"));

        assertThat(bundle.getTotal()).isEqualTo(1);
    }

    // ── date filter ───────────────────────────────────────────────────────────

    @Test
    void search_dateFilter_excludesOlderSets() {
        Pointer p = pointer("doc-1", "PHN001", "SS-OLD");
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of("SS-OLD", List.of(p)));

        // Set a lower bound that is in the future relative to the pointer's indexedAt
        ca.uhn.fhir.rest.param.DateRangeParam future = new ca.uhn.fhir.rest.param.DateRangeParam();
        future.setLowerBoundInclusive(java.util.Date.from(Instant.now().plusSeconds(3600)));

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), future, null);

        // The set's date is before the lower bound, so it should be filtered out
        assertThat(bundle.getTotal()).isZero();
    }

    // ── list resource structure ───────────────────────────────────────────────

    @Test
    void search_listResource_hasCorrectIdentifiersAndSubject() {
        Pointer p = pointer("doc-1", "PHN001", "SS-001");
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of("SS-001", List.of(p)));

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), null, null);

        assertThat(bundle.getEntry()).hasSize(1);
        ListResource list = (ListResource) bundle.getEntry().get(0).getResource();
        assertThat(list.getSubject().getReference()).isEqualTo("Patient/PHN001");
        assertThat(list.getIdentifier()).hasSize(2);
        // One identifier has our system, one has urn:ietf:rfc:3986
        boolean hasOurSystem = list.getIdentifier().stream()
                .anyMatch(id -> "urn:oid:2.16.840.1.113883.3.9999".equals(id.getSystem()));
        assertThat(hasOurSystem).isTrue();
    }

    @Test
    void search_emptySubmissionSets_returnsZeroBundle() {
        when(consentGate.checkSearch("PHN001", "ITI-66")).thenReturn(false);
        when(searchService.submissionSetsWithMembers("PHN001", Pointer.Status.CURRENT))
                .thenReturn(Map.of());

        Bundle bundle = provider.search(
                new ReferenceParam("PHN001"), null,
                null, new TokenParam("current"), null, null);

        assertThat(bundle.getTotal()).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Pointer pointer(String id, String phn, String submissionSetId) {
        return Pointer.builder()
                .id(id).phn(phn)
                .resourceType("DocumentReference")
                .status(Pointer.Status.CURRENT)
                .submissionSetId(submissionSetId)
                .consent(Consent.defaultAccessible())
                .build();
    }
}
