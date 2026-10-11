package lk.gov.moh.dhp.rl.search;

/**
 * The document shape stored in the OpenSearch patient index.
 *
 * One document per PHN — upserted whenever OrthancPoller (or the FHIR
 * ingest path) creates or updates a pointer for that patient. This is
 * purely a search index: the authoritative patient record lives in the
 * Client Registry (B1). We only store what we need to answer name/date
 * searches from DocumentReferenceProvider._name queries.
 *
 * Index mapping (set in V4 migration / index template):
 *   phn        → keyword  (exact match, used as document id)
 *   givenName  → text     (analyzed, full-text)
 *   familyName → text     (analyzed, full-text)
 *   fullName   → text     (analyzed, full-text — concatenated for single-field search)
 *   facilityHin → keyword (for future facility-scoped name searches)
 *
 * patientName from DICOM arrives in "Perera^Kasun^M^Dr" format.
 * We split on ^ and store components in givenName/familyName separately
 * to make partial matches work correctly (searching "Kasun" should match).
 */
public record PatientDocument(
        String phn,
        String givenName,
        String familyName,
        String fullName,
        String facilityHin) {

    /**
     * Build from a DICOM PatientName tag value (uses ^ as component separator).
     * DICOM PN format: FamilyName^GivenName^MiddleName^NamePrefix^NameSuffix
     */
    public static PatientDocument fromDicom(String phn, String dicomPatientName, String facilityHin) {
        if (dicomPatientName == null || dicomPatientName.isBlank()) {
            return new PatientDocument(phn, null, null, null, facilityHin);
        }
        String[] parts = dicomPatientName.split("\\^", -1);
        String familyName = parts.length > 0 ? parts[0].trim() : null;
        String givenName  = parts.length > 1 ? parts[1].trim() : null;
        // fullName for single-field searches: "Kasun Perera" and "Perera Kasun" both work
        // because OpenSearch analyzes text fields with a standard analyzer.
        String fullName = String.join(" ",
                java.util.stream.Stream.of(givenName, familyName)
                        .filter(s -> s != null && !s.isBlank())
                        .toList());
        return new PatientDocument(phn,
                givenName != null && !givenName.isBlank() ? givenName : null,
                familyName != null && !familyName.isBlank() ? familyName : null,
                fullName.isBlank() ? null : fullName,
                facilityHin);
    }

    /**
     * Build from a FHIR HumanName (given + family as separate strings).
     */
    public static PatientDocument fromFhir(String phn, String givenName, String familyName, String facilityHin) {
        String g = givenName != null ? givenName.trim() : null;
        String f = familyName != null ? familyName.trim() : null;
        String fullName = String.join(" ",
                java.util.stream.Stream.of(g, f)
                        .filter(s -> s != null && !s.isBlank())
                        .toList());
        return new PatientDocument(phn,
                (g != null && !g.isBlank()) ? g : null,
                (f != null && !f.isBlank()) ? f : null,
                fullName.isBlank() ? null : fullName,
                facilityHin);
    }
}
