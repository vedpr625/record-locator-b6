package lk.gov.moh.dhp.rl.ingest;

public record NewPointerRequest(
        String id,
        String phn,
        String patientName,   // full name as received from DICOM/FHIR; stored in OpenSearch patient index
        String resourceType,
        String docTypeLoinc,
        String contentType,
        String contentUrl,
        String facilityHin,
        String facilityName,
        String submissionSetId,
        String nehrEncounterRef,
        boolean nationalSynced) {
}
