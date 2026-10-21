package lk.gov.moh.dhp.ris.records;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Queries national-rl FHIR endpoint for records by PHN.
 * Labels each record LOCAL (this hospital) or REMOTE (another hospital).
 */
@Service
public class RecordsService {

    private static final Logger log = LoggerFactory.getLogger(RecordsService.class);

    @Value("${national-rl.url}")
    private String nationalRlUrl;

    @Value("${national-rl.bearer-token:}")
    private String bearerToken;

    @Value("${hospital.facility-id}")
    private String myFacilityId;

    private final WebClient webClient;

    public RecordsService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Search national-rl for all records for a given PHN.
     * Returns empty list on error (demo-safe).
     */
    public List<RecordItem> searchByPhn(String phn) {
        List<RecordItem> results = new ArrayList<>();
        try {
            // FHIR DocumentReference search: GET /fhir/r4/DocumentReference?patient=Patient/{phn}
            String url = nationalRlUrl + "/fhir/r4/DocumentReference?patient="
                    + java.net.URLEncoder.encode("Patient/" + phn, java.nio.charset.StandardCharsets.UTF_8)
                    + "&status=current";

            var req = webClient.get().uri(java.net.URI.create(url));
            if (bearerToken != null && !bearerToken.isBlank()) {
                req = req.header("Authorization", "Bearer " + bearerToken);
            }
            String body = req
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (body == null) return results;

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode bundle = mapper.readTree(body);
            JsonNode entries = bundle.path("entry");

            for (JsonNode entry : entries) {
                JsonNode resource = entry.path("resource");
                RecordItem item = toRecordItem(resource, phn);
                if (item != null) results.add(item);
            }

        } catch (Exception e) {
            log.warn("national-rl search failed for PHN {} (url={}): {}", phn, nationalRlUrl, e.getMessage());
        }
        return results;
    }

    private RecordItem toRecordItem(JsonNode resource, String phn) {
        try {
            // Study UID from masterIdentifier
            String studyUid = resource.path("masterIdentifier").path("value").asText("");
            if (studyUid.startsWith("urn:oid:")) studyUid = studyUid.substring(8);

            // Facility from custodian.identifier.value
            String facilityHin = resource.path("custodian")
                    .path("identifier").path("value").asText("");

            // Facility name from custodian.display
            String facilityName = resource.path("custodian").path("display").asText(facilityHin);

            // Modality / type from type.coding[0].code (LOINC)
            String loinc = resource.path("type").path("coding")
                    .path(0).path("code").asText("");
            String modality = loincToModality(loinc);

            // Date from date field
            String date = resource.path("date").asText("").substring(0, 10);

            // Patient name from subject display if present
            String patientName = resource.path("subject").path("display").asText("");

            // LOCAL if facilityHin matches this hospital
            boolean local = facilityHin.contains(myFacilityId) || myFacilityId.contains(facilityHin);

            return RecordItem.builder()
                    .studyUid(studyUid)
                    .patientName(patientName)
                    .phn(phn)
                    .modality(modality)
                    .studyDate(date)
                    .facilityName(facilityName)
                    .local(local)
                    .build();

        } catch (Exception e) {
            log.warn("skipping malformed record: {}", e.getMessage());
            return null;
        }
    }

    private String loincToModality(String loinc) {
        return switch (loinc) {
            case "18747-6" -> "CT";
            case "18755-9" -> "MR";
            case "18760-9" -> "US";
            case "26347-5" -> "MG";
            default        -> "XR";
        };
    }
}
