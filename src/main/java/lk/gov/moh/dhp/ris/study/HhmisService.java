package lk.gov.moh.dhp.ris.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Notifies the HHMIS (hospital FHIR RIS) that a study has been completed
 * by sending a PUT ImagingStudy resource.
 *
 * Homagama  → nova-qa-ris.external.dxdyhosting.xyz  (facilityId=29751)
 * NHSL      → nova-develop-ris.external.dxdyhosting.xyz (facilityId=org-dgh)
 *
 * Non-fatal: failure is logged but does NOT abort the study workflow.
 */
@Service
public class HhmisService {

    private static final Logger log = LoggerFactory.getLogger(HhmisService.class);

    private static final DateTimeFormatter FHIR_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Value("${hhmis.rest-url}")
    private String hhmisRestUrl;

    @Value("${hhmis.viewer-base-url:}")
    private String viewerBaseUrl;

    @Value("${hospital.facility-id}")
    private String facilityId;

    private final WebClient webClient;

    public HhmisService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * PUT ImagingStudy to HHMIS after a study is performed.
     *
     * @param studyInstanceUid  dcm4che-generated StudyInstanceUID
     * @param accessionNumber   local accession number
     * @param phn               patient PHN
     * @param modality          e.g. CR, CT, MR
     */
    public void notifyStudyDone(String studyInstanceUid, String accessionNumber,
                                String phn, String modality) {
        try {
            String body = buildFhirBody(studyInstanceUid, accessionNumber, phn, modality);
            String url  = hhmisRestUrl + "/api/v1/ris/ImagingStudy/" + studyInstanceUid;

            webClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("HHMIS notified: PUT {} acc={} phn={}", url, accessionNumber, phn);

        } catch (Exception e) {
            log.warn("HHMIS notification failed for acc={} (non-fatal): {}", accessionNumber, e.getMessage());
        }
    }

    private String buildFhirBody(String studyUid, String accessionNumber,
                                  String phn, String modality) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Colombo"))
                .format(FHIR_DT);

        // Viewer address (optional — empty string if not configured)
        String viewerAddress = viewerBaseUrl.isBlank()
                ? "urn:oid:" + studyUid
                : viewerBaseUrl + "?StudyInstanceUIDs=" + studyUid;

        return "{\n" +
               "  \"resourceType\": \"ImagingStudy\",\n" +
               "  \"id\": \"" + studyUid + "\",\n" +
               "  \"identifier\": [\n" +
               "    {\n" +
               "      \"system\": \"urn:dicom:uid\",\n" +
               "      \"value\": \"urn:oid:" + studyUid + "\"\n" +
               "    },\n" +
               "    {\n" +
               "      \"type\": {\n" +
               "        \"coding\": [\n" +
               "          { \"system\": \"http://terminology.hl7.org/CodeSystem/v2-0203\", \"code\": \"ACSN\" }\n" +
               "        ]\n" +
               "      },\n" +
               "      \"value\": \"" + accessionNumber + "\"\n" +
               "    }\n" +
               "  ],\n" +
               "  \"status\": \"available\",\n" +
               "  \"subject\": {\n" +
               "    \"identifier\": { \"value\": \"" + phn + "\" }\n" +
               "  },\n" +
               "  \"started\": \"" + now + "\",\n" +
               "  \"numberOfSeries\": 1,\n" +
               "  \"numberOfInstances\": 1,\n" +
               "  \"contained\": [\n" +
               "    {\n" +
               "      \"resourceType\": \"Endpoint\",\n" +
               "      \"id\": \"viewer\",\n" +
               "      \"status\": \"active\",\n" +
               "      \"connectionType\": {\n" +
               "        \"system\": \"http://terminology.hl7.org/CodeSystem/endpoint-connection-type\",\n" +
               "        \"code\": \"dicom-wado-rs\"\n" +
               "      },\n" +
               "      \"payloadType\": [{ \"text\": \"DICOM\" }],\n" +
               "      \"address\": \"" + viewerAddress + "\"\n" +
               "    }\n" +
               "  ],\n" +
               "  \"endpoint\": [{ \"reference\": \"#viewer\" }]\n" +
               "}\n";
    }
}
