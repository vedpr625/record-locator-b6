package lk.gov.moh.dhp.rl.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload that a hospital node POSTs to POST /pointer-events to register
 * a new imaging study (or document) pointer at the National Record Locator.
 *
 * Design: mirrors NewPointerRequest field-for-field so the controller can
 * delegate directly to PointerFactory without a translation layer.
 * nationalSynced is always false at ingest (the national RL IS the national
 * node, so inbound pointers from hospitals are not yet "nationally synced"
 * until any federation routing runs — currently no federation is implemented).
 *
 * Auth: JWT OAuth2 resource server (always active) + mTLS header filter
 * (enabled via rl.mtls.enabled=true in production).
 *
 * When domain affinity federation is implemented, routing to the appropriate
 * domain node will be added here.
 */
public record PointerEventRequest(

        /** Stable document/study id — StudyInstanceUID for imaging, resource id for documents. */
        @NotBlank String id,

        /** Patient Health Number — national patient identifier from the Client Registry. */
        @NotBlank @Pattern(regexp = "\\d{10}", message = "phn must be exactly 10 digits") String phn,

        /** Display name (optional). Used only for OpenSearch patient-name indexing. */
        String patientName,

        /**
         * FHIR resource type of the pointed-to record.
         * Expected values: "DocumentReference" | "ImagingStudy"
         */
        @NotBlank String resourceType,

        /** LOINC code describing the document type (e.g. "18747-6" for CT, "11502-2" for lab). */
        String docTypeLoinc,

        /** MIME type of the retrievable content (e.g. "application/dicom+json", "application/fhir+json"). */
        String contentType,

        /** Retrieval URL — WADO-RS endpoint for imaging, FHIR endpoint for documents. */
        @NotBlank String contentUrl,

        /** Health Institution Number of the originating facility. */
        @NotBlank String facilityHin,

        /** Human-readable facility name (optional but recommended for display). */
        String facilityName,

        /** IHE MHD SubmissionSet id this pointer belongs to, if any. */
        String submissionSetId,

        /**
         * NEHR Encounter reference id, when the study is tied to a specific encounter.
         * Null for standalone studies.
         */
        String nehrEncounterRef
) {

    /** Converts to the internal NewPointerRequest used by PointerFactory. */
    public NewPointerRequest toNewPointerRequest() {
        return new NewPointerRequest(
                id, phn, patientName, resourceType, docTypeLoinc, contentType,
                contentUrl, facilityHin, facilityName, submissionSetId,
                nehrEncounterRef,
                false   // nationalSynced = false: the hospital pointer has not been federated yet
        );
    }
}
