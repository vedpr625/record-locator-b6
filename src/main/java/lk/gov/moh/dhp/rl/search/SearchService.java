package lk.gov.moh.dhp.rl.search;

import lk.gov.moh.dhp.rl.common.SearchQuery;
import lk.gov.moh.dhp.rl.facility.Facility;
import lk.gov.moh.dhp.rl.facility.FacilityRepository;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Bundle 6 / edge profile: all search is local (no federation).
 *
 * Search routing:
 * 1. PHN-only query         → PostgreSQL directly (fast path)
 * 2. patientName query      → OpenSearch for PHN list → PostgreSQL for pointers
 * 3. PHN + patientName      → PostgreSQL using the PHN (name is ignored; if both
 *                             are supplied, the PHN is more precise)
 *
 * Purpose-of-use filtering:
 * When the caller's JWT carries a purpose-of-use claim (SearchQuery.purposeOfUse),
 * results are post-filtered using Consent.allows().  A pointer with no purpose
 * restriction passes through; one that restricts to a specific purpose set is
 * excluded when the caller's purpose is not in that set.
 *
 * The filter is applied after PostgreSQL retrieval (not in SQL) because the
 * purpose-of-use set is stored as a comma-separated string in the embedded
 * Consent — adding it to the JPQL would require a LIKE / string-split that
 * isn't portable across DBs and is harder to unit-test.  The result sets are
 * small enough (per-patient, per-facility) that in-memory filtering is fine.
 */
@Service
public class SearchService {

    private final PointerRepository pointerRepository;
    private final FacilityRepository facilityRepository;
    private final OpenSearchServicePort openSearchService;

    public SearchService(PointerRepository pointerRepository,
                         FacilityRepository facilityRepository,
                         OpenSearchServicePort openSearchService) {
        this.pointerRepository = pointerRepository;
        this.facilityRepository = facilityRepository;
        this.openSearchService = openSearchService;
    }

    public List<Pointer> search(SearchQuery query) {
        List<Pointer> results;

        // Name-only path: no PHN — go via OpenSearch to find matching PHNs
        if ((query.phn() == null || query.phn().isBlank()) && query.patientName() != null) {
            results = searchByName(query);
        } else {
            results = searchLocal(query);
        }

        return filterByPurposeOfUse(results, query.purposeOfUse());
    }

    /**
     * OpenSearch name lookup → PostgreSQL pointer fetch.
     * Returns all accessible pointers for patients whose name matches the
     * free-text query, filtered by any date/type/facility constraints.
     */
    private List<Pointer> searchByName(SearchQuery query) {
        List<String> phns = openSearchService.findPhnsByName(query.patientName());
        if (phns.isEmpty()) {
            return List.of();
        }

        Instant dateFrom = query.dateFrom() == null ? null
                : query.dateFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dateTo = query.dateTo() == null ? null
                : query.dateTo().atStartOfDay(ZoneOffset.UTC).toInstant();

        return pointerRepository.searchByPhns(phns, query.status(), dateFrom, dateTo);
    }

    private List<Pointer> searchLocal(SearchQuery query) {
        Instant dateFrom = query.dateFrom() == null ? null
                : query.dateFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dateTo = query.dateTo() == null ? null
                : query.dateTo().atStartOfDay(ZoneOffset.UTC).toInstant();

        if (query.provinceCode() == null) {
            return pointerRepository.search(
                    query.phn(), query.status(), dateFrom, dateTo, query.docTypeLoinc(),
                    query.facilityHin(), query.identifier());
        }

        List<String> facilitiesInProvince = facilityRepository.findByProvinceCode(query.provinceCode()).stream()
                .map(Facility::hin)
                .toList();

        if (facilitiesInProvince.isEmpty()) {
            return List.of();
        }

        return pointerRepository.searchWithinFacilities(
                query.phn(), query.status(), dateFrom, dateTo, query.docTypeLoinc(),
                query.identifier(), facilitiesInProvince);
    }

    /**
     * Filters out pointers whose consent does not allow the requested purpose.
     * When purposeOfUse is null (caller didn't supply one), no filtering is applied.
     */
    private List<Pointer> filterByPurposeOfUse(List<Pointer> pointers, String purposeOfUse) {
        if (purposeOfUse == null || purposeOfUse.isBlank()) {
            return pointers;
        }
        return pointers.stream()
                .filter(p -> p.consent().allows(purposeOfUse))
                .toList();
    }

    public Optional<Pointer> findById(String id) {
        return pointerRepository.findById(id);
    }

    /**
     * Grouped by submissionSetId for ITI-66 (ListProvider).
     */
    public java.util.Map<String, List<Pointer>> submissionSetsWithMembers(String phn, Pointer.Status status) {
        List<Pointer> pointers = pointerRepository.findAllByPhn(phn).stream()
                .filter(p -> status == null || p.status() == status)
                .filter(p -> p.submissionSetId() != null)
                .toList();

        return pointers.stream().collect(
                java.util.stream.Collectors.groupingBy(Pointer::submissionSetId));
    }
}
