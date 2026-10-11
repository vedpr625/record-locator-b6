package lk.gov.moh.dhp.rl.common;

import lk.gov.moh.dhp.rl.pointer.Pointer;

import java.time.LocalDate;

/**
 * Query parameters for a pointer search. Lives in common so both
 * SearchService and any future federation layer can use the same type.
 *
 * patientName is an OpenSearch-only parameter — it triggers a name
 * lookup in the patient index first, which returns a list of PHNs that
 * are then resolved against PostgreSQL. When patientName is non-null
 * and phn is null, the OpenSearch result set drives the query.
 */
public record SearchQuery(
        String phn,
        String patientName,
        Pointer.Status status,
        LocalDate dateFrom,
        LocalDate dateTo,
        String docTypeLoinc,
        String facilityHin,
        String provinceCode,
        String identifier) {

    public static SearchQuery forPatient(String phn) {
        return new SearchQuery(phn, null, Pointer.Status.CURRENT, null, null, null, null, null, null);
    }
}
