package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

/**
 * Shared FHIR parameter extraction helpers used by multiple
 * {@link ca.uhn.fhir.rest.server.IResourceProvider} implementations.
 *
 * <p>M-12: {@code resolvePhn()} was previously duplicated verbatim in both
 * {@link ListProvider} and {@link DocumentReferenceProvider}. This utility
 * centralises the logic while preserving the behavioural difference between
 * the two callers (ListProvider requires a PHN; DocumentReferenceProvider
 * allows null for OpenSearch name-only queries).
 */
final class FhirParamUtil {

    private FhirParamUtil() {}

    /**
     * Extracts the PHN from either a {@code patient} reference parameter or a
     * {@code patient.identifier} token parameter.
     *
     * <p>Returns {@code null} when neither is supplied — callers that require a
     * PHN must check for null and throw {@link InvalidRequestException} themselves.
     *
     * @param patient           the patient reference parameter (may be null)
     * @param patientIdentifier the patient.identifier token parameter (may be null)
     * @return the extracted PHN, or {@code null} if neither param was supplied
     */
    static String resolvePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        if (patient != null) {
            return patient.getIdPart();
        }
        if (patientIdentifier != null) {
            return patientIdentifier.getValue();
        }
        return null;
    }

    /**
     * Like {@link #resolvePhn(ReferenceParam, TokenParam)} but throws
     * {@link InvalidRequestException} instead of returning {@code null}.
     * Use this for ITI-66 (ListProvider) where a PHN is always required.
     *
     * @param patient           the patient reference parameter (may be null)
     * @param patientIdentifier the patient.identifier token parameter (may be null)
     * @return the extracted PHN — never null
     * @throws InvalidRequestException if neither parameter is supplied
     */
    static String requirePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        String phn = resolvePhn(patient, patientIdentifier);
        if (phn == null) {
            throw new InvalidRequestException("either patient or patient.identifier is required");
        }
        return phn;
    }
}
