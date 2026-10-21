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

    @Value("${hhmis.bearer-token:}")
    private String bearerToken;

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
     * @param numberOfSeries    actual series count from PACS
     * @param numberOfInstances actual instance count from PACS
     */
    public void notifyStudyDone(String studyInstanceUid, String accessionNumber,
                                String phn, String modality,
                                int numberOfSeries, int numberOfInstances) {
        try {
            String body = buildFhirBody(studyInstanceUid, accessionNumber, phn, modality,
                                        numberOfSeries, numberOfInstances);
            String url  = hhmisRestUrl + "/api/v1/ris/ImagingStudy/" + studyInstanceUid;

            var req = webClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null && !bearerToken.isBlank()) {
                req = req.header("Authorization", "Bearer " + bearerToken);
            }
            req.bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("HHMIS notified: PUT {} acc={} phn={}", url, accessionNumber, phn);

        } catch (Exception e) {
            log.warn("HHMIS notification failed for acc={} (non-fatal): {}", accessionNumber, e.getMessage());
        }
    }

    private String buildFhirBody(String studyUid, String accessionNumber,
                                  String phn, String modality,
                                  int numberOfSeries, int numberOfInstances) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Colombo"))
                .format(FHIR_DT);

        // Viewer address: use configured OHIF viewer URL, else fall back to DICOM UID URN
        String viewerAddress = (viewerBaseUrl == null || viewerBaseUrl.isBlank())
                ? "urn:oid:" + studyUid
                : viewerBaseUrl + "?StudyInstanceUIDs=" + studyUid;

        // modality coding system
        String modalitySystem = "http://dicom.nema.org/resources/ontology/DCM";

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
               "  \"modality\": [\n" +
               "    {\n" +
               "      \"system\": \"" + modalitySystem + "\",\n" +
               "      \"code\": \"" + modality + "\"\n" +
               "    }\n" +
               "  ],\n" +
               "  \"subject\": {\n" +
               "    \"identifier\": { \"value\": \"" + phn + "\" }\n" +
               "  },\n" +
               "  \"started\": \"" + now + "\",\n" +
               "  \"numberOfSeries\": " + numberOfSeries + ",\n" +
               "  \"numberOfInstances\": " + numberOfInstances + ",\n" +
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
