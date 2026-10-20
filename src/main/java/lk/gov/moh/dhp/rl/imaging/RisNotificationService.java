package lk.gov.moh.dhp.rl.imaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Notifies the hospital's external RIS that a study is now available.
 *
 * Called by {@link OrthancPoller} after a pointer is registered in national-rl.
 *
 * The RIS API expects a FHIR ImagingStudy resource body:
 *   PUT {RIS_URL}/{StudyInstanceUID}
 *
 * Hospital A — Homagama Hospital
 *   RIS_URL=https://nova-qa-ris.external.dxdyhosting.xyz/api/v1/ris/ImagingStudy
 *   RIS_FACILITY_ID=29751
 *
 * Hospital B — NHSL
 *   RIS_URL=https://nova-develop-ris.external.dxdyhosting.xyz/api/v1/ris/ImagingStudy
 *   RIS_FACILITY_ID=org-dgh
 *
 * Enabled per deployment via RIS_ENABLED=true environment variable.
 */
@Service
@ConditionalOnProperty(name = "rl.ris.enabled", havingValue = "true", matchIfMissing = false)
public class RisNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RisNotificationService.class);

    @Value("${rl.ris.url}")
    private String risUrl;

    @Value("${rl.ris.facility-id}")
    private String facilityId;

    @Value("${rl.ris.ohif-viewer-base-url}")
    private String ohifViewerBaseUrl;

    private final RestTemplate restTemplate;

    public RisNotificationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Sends FHIR ImagingStudy PUT to the external RIS.
     *
     * @param studyInstanceUid DICOM StudyInstanceUID
     * @param accessionNumber  DICOM AccessionNumber (may be blank)
     * @param patientId        Patient NIC / PHN
     * @param studyDateStr     DICOM StudyDate e.g. "20261020"
     * @param studyTimeStr     DICOM StudyTime e.g. "101500.000" (may be blank)
     * @param numberOfSeries   from Orthanc study response
     * @param numberOfInstances from Orthanc study response
     */
    public void notifyStudyAvailable(String studyInstanceUid,
                                     String accessionNumber,
                                     String patientId,
                                     String studyDateStr,
                                     String studyTimeStr,
                                     int numberOfSeries,
                                     int numberOfInstances) {
        try {
            String started = buildStartedDateTime(studyDateStr, studyTimeStr);
            String viewerUrl = ohifViewerBaseUrl + "?StudyInstanceUIDs=" + studyInstanceUid;

            Map<String, Object> body = buildFhirImagingStudy(
                    studyInstanceUid, accessionNumber, patientId,
                    started, numberOfSeries, numberOfInstances, viewerUrl);

            String url = risUrl + "/" + studyInstanceUid;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            log.info("RIS notified for study {} (facilityId={})", studyInstanceUid, facilityId);

        } catch (Exception e) {
            // Non-fatal — pointer is already saved. Log and continue.
            log.warn("RIS notification failed for study {}: {}", studyInstanceUid, e.getMessage());
        }
    }

    /**
     * Builds the FHIR ImagingStudy resource body expected by the external RIS.
     */
    private Map<String, Object> buildFhirImagingStudy(String studyInstanceUid,
                                                       String accessionNumber,
                                                       String patientId,
                                                       String started,
                                                       int numberOfSeries,
                                                       int numberOfInstances,
                                                       String viewerUrl) {
        Map<String, Object> endpoint = Map.of(
                "resourceType", "Endpoint",
                "id", "viewer",
                "status", "active",
                "connectionType", Map.of(
                        "system", "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
                        "code", "dicom-wado-rs"
                ),
                "payloadType", List.of(Map.of("text", "DICOM")),
                "address", viewerUrl
        );

        List<Map<String, Object>> identifiers = new java.util.ArrayList<>();
        identifiers.add(Map.of(
                "system", "urn:dicom:uid",
                "value", "urn:oid:" + studyInstanceUid
        ));
        if (accessionNumber != null && !accessionNumber.isBlank()) {
            identifiers.add(Map.of(
                    "type", Map.of("coding", List.of(Map.of(
                            "system", "http://terminology.hl7.org/CodeSystem/v2-0203",
                            "code", "ACSN"
                    ))),
                    "value", accessionNumber
            ));
        }

        return Map.of(
                "resourceType", "ImagingStudy",
                "id", studyInstanceUid,
                "identifier", identifiers,
                "status", "available",
                "subject", Map.of("identifier", Map.of("value", patientId)),
                "started", started,
                "numberOfSeries", numberOfSeries,
                "numberOfInstances", numberOfInstances,
                "contained", List.of(endpoint),
                "endpoint", List.of(Map.of("reference", "#viewer"))
        );
    }

    /**
     * Converts DICOM date/time strings to ISO-8601 with +05:30 timezone (Sri Lanka).
     * DICOM StudyDate = "YYYYMMDD", StudyTime = "HHMMSS.fff" (optional).
     */
    private String buildStartedDateTime(String studyDate, String studyTime) {
        try {
            if (studyDate != null && studyDate.length() == 8) {
                int year  = Integer.parseInt(studyDate.substring(0, 4));
                int month = Integer.parseInt(studyDate.substring(4, 6));
                int day   = Integer.parseInt(studyDate.substring(6, 8));

                int hour = 0, minute = 0, second = 0;
                if (studyTime != null && studyTime.length() >= 6) {
                    // Strip fractional seconds if present
                    String t = studyTime.split("\\.")[0];
                    hour   = Integer.parseInt(t.substring(0, 2));
                    minute = Integer.parseInt(t.substring(2, 4));
                    second = Integer.parseInt(t.substring(4, 6));
                }

                ZoneOffset sriLanka = ZoneOffset.ofHoursMinutes(5, 30);
                OffsetDateTime dt = OffsetDateTime.of(year, month, day, hour, minute, second, 0, sriLanka);
                return dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
        } catch (Exception e) {
            log.debug("could not parse DICOM date/time '{}/{}': {}", studyDate, studyTime, e.getMessage());
        }
        // Fallback: current time
        return OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
