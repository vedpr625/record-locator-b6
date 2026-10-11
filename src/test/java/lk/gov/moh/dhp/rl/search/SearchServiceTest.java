package lk.gov.moh.dhp.rl.search;

import lk.gov.moh.dhp.rl.common.SearchQuery;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.facility.FacilityRepository;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SearchService — no Spring, no DB.
 */
class SearchServiceTest {

    PointerRepository pointerRepository;
    FacilityRepository facilityRepository;
    OpenSearchServicePort openSearchService;
    SearchService searchService;

    @BeforeEach
    void setUp() {
        pointerRepository = Mockito.mock(PointerRepository.class);
        facilityRepository = Mockito.mock(FacilityRepository.class);
        openSearchService = Mockito.mock(OpenSearchServicePort.class);
        searchService = new SearchService(pointerRepository, facilityRepository, openSearchService);
    }

    // ── Basic PHN search ──────────────────────────────────────────────────────

    @Test
    void search_byPhn_queriesPointerRepository() {
        Pointer p = mockPointer("PHN001", Consent.defaultAccessible());
        when(pointerRepository.search(eq("PHN001"), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(p));

        SearchQuery q = SearchQuery.forPatient("PHN001");
        List<Pointer> result = searchService.search(q);

        assertThat(result).hasSize(1);
        verify(pointerRepository).search(eq("PHN001"), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(openSearchService);
    }

    @Test
    void search_byPhn_emptyResult() {
        when(pointerRepository.search(eq("PHN001"), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        List<Pointer> result = searchService.search(SearchQuery.forPatient("PHN001"));
        assertThat(result).isEmpty();
    }

    // ── Name search via OpenSearch ────────────────────────────────────────────

    @Test
    void search_byNameOnly_goesViaOpenSearch() {
        when(openSearchService.findPhnsByName("Perera")).thenReturn(List.of("PHN001", "PHN002"));
        Pointer p = mockPointer("PHN001", Consent.defaultAccessible());
        when(pointerRepository.searchByPhns(anyList(), any(), any(), any())).thenReturn(List.of(p));

        SearchQuery q = new SearchQuery(null, "Perera", Pointer.Status.CURRENT,
                null, null, null, null, null, null, null);
        List<Pointer> result = searchService.search(q);

        assertThat(result).hasSize(1);
        verify(openSearchService).findPhnsByName("Perera");
    }

    @Test
    void search_byName_noOpenSearchResults_returnsEmpty() {
        when(openSearchService.findPhnsByName(anyString())).thenReturn(List.of());

        SearchQuery q = new SearchQuery(null, "Nobody", Pointer.Status.CURRENT,
                null, null, null, null, null, null, null);

        assertThat(searchService.search(q)).isEmpty();
        verifyNoInteractions(pointerRepository);
    }

    // ── Purpose-of-use filtering ──────────────────────────────────────────────

    @Test
    void search_purposeOfUse_filtersOutNonMatchingConsent() {
        // pointer allows TREAT only
        Pointer treatOnly = mockPointer("PHN001", Consent.from(false, false, Set.of("TREAT")));
        when(pointerRepository.search(eq("PHN001"), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(treatOnly));

        SearchQuery q = new SearchQuery("PHN001", null, Pointer.Status.CURRENT,
                null, null, null, null, null, null, "HPAYMT");

        assertThat(searchService.search(q)).isEmpty();
    }

    @Test
    void search_purposeOfUse_allowsMatchingConsent() {
        Pointer treatOnly = mockPointer("PHN001", Consent.from(false, false, Set.of("TREAT")));
        when(pointerRepository.search(eq("PHN001"), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(treatOnly));

        SearchQuery q = new SearchQuery("PHN001", null, Pointer.Status.CURRENT,
                null, null, null, null, null, null, "TREAT");

        assertThat(searchService.search(q)).hasSize(1);
    }

    @Test
    void search_nullPurposeOfUse_doesNotFilter() {
        Pointer restricted = mockPointer("PHN001", Consent.from(false, false, Set.of("TREAT")));
        when(pointerRepository.search(eq("PHN001"), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(restricted));

        SearchQuery q = SearchQuery.forPatient("PHN001"); // purposeOfUse = null

        assertThat(searchService.search(q)).hasSize(1);
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_delegates_toRepository() {
        Pointer p = mockPointer("PHN001", Consent.defaultAccessible());
        when(pointerRepository.findById("doc1")).thenReturn(Optional.of(p));

        assertThat(searchService.findById("doc1")).isPresent();
    }

    @Test
    void findById_notFound_returnsEmpty() {
        when(pointerRepository.findById(anyString())).thenReturn(Optional.empty());
        assertThat(searchService.findById("missing")).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pointer mockPointer(String phn, Consent consent) {
        Pointer p = Mockito.mock(Pointer.class);
        when(p.phn()).thenReturn(phn);
        when(p.consent()).thenReturn(consent);
        when(p.status()).thenReturn(Pointer.Status.CURRENT);
        when(p.submissionSetId()).thenReturn(null);
        return p;
    }
}
