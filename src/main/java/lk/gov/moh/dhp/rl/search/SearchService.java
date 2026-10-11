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
 * 3. PHN + patientName      → PostgreSQL using the PHN (name is ignored; client
 *                             should use one or the other, not both — if they send
 *                             both we trust the PHN as the more precise identifier)
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
        // Name-only path: no PHN — go via OpenSearch to find matching PHNs
        if ((query.phn() == null || query.phn().isBlank()) && query.patientName() != null) {
            return searchByName(query);
        }
        return searchLocal(query);
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
