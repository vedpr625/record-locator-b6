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

    /** Path of the OHIF viewer on the host that owns the study (OHIF is at the site root). */
    @Value("${national-rl.viewer-path:/viewer}")
    private String viewerPath;

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
            // FHIR DocumentReference search: GET /fhir/DocumentReference?patient=Patient/{phn} (RL FHIR base is /fhir)
            String url = nationalRlUrl + "/fhir/DocumentReference?patient="
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

            // Facility: the Record Locator writes it to context.facilityType.coding[0]
            // (code = facility HIN, display = name); fall back to custodian for other servers.
            JsonNode fac = resource.path("context").path("facilityType").path("coding").path(0);
            String facilityHin = fac.path("code").asText(
                    resource.path("custodian").path("identifier").path("value").asText(""));
            String facilityName = fac.path("display").asText(
                    resource.path("custodian").path("display").asText(facilityHin));

            // Modality / type from type.coding[0].code (LOINC)
            String loinc = resource.path("type").path("coding")
                    .path(0).path("code").asText("");
            String modality = loincToModality(loinc);

            // Date from date field
            String dateRaw = resource.path("date").asText("");
            String date = dateRaw.length() >= 10 ? dateRaw.substring(0, 10) : dateRaw;

            // Patient name from subject display if present
            String patientName = resource.path("subject").path("display").asText("");

            // LOCAL if facilityHin matches this hospital
            boolean local = !facilityHin.isBlank() && facilityHin.equals(myFacilityId);

            String wadoUrl = resource.path("content").path(0).path("attachment").path("url").asText("");
            String viewerUrl = "";
            try {
                java.net.URI u = java.net.URI.create(wadoUrl);
                if (u.getHost() != null) {
                    viewerUrl = u.getScheme() + "://" + u.getAuthority() + viewerPath
                            + "?StudyInstanceUIDs=" + studyUid;
                }
            } catch (Exception ignore) { /* leave blank */ }

            // The Record Locator does not return the patient name; read it from the
            // study's DICOM metadata at the WADO-RS URL (best effort, short timeout).
            if (patientName.isBlank() && !wadoUrl.isBlank()) {
                patientName = fetchPatientName(wadoUrl);
            }

            return RecordItem.builder()
                    .wadoUrl(wadoUrl)
                    .viewerUrl(viewerUrl)
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

    /** GET {wadoUrl}/metadata (DICOMweb) and read PatientName (0010,0010). Never throws. */
    private String fetchPatientName(String wadoUrl) {
        try {
            String body = webClient.get().uri(java.net.URI.create(wadoUrl + "/metadata"))
                    .accept(org.springframework.http.MediaType.parseMediaType("application/dicom+json"))
                    .retrieve().bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(4));
            if (body == null) return "";
            JsonNode name = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                    .path(0).path("00100010").path("Value").path(0);
            String n = name.isObject() ? name.path("Alphabetic").asText("") : name.asText("");
            return n.replace('^', ' ').trim();
        } catch (Exception e) {
            log.debug("could not read patient name from {}: {}", wadoUrl, e.getMessage());
            return "";
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
