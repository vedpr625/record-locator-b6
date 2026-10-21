package lk.gov.moh.dhp.ris.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registers a pointer to a newly stored study at the national Record Locator
 * (POST {national-rl.push-url}/pointer-events). Non-fatal: failures are logged only.
 * Disabled when national-rl.push-url is blank.
 */
@Service
public class RecordLocatorService {

    private static final Logger log = LoggerFactory.getLogger(RecordLocatorService.class);
    private static final Pattern PHN = Pattern.compile("[^\\p{Cntrl}]{1,255}");

    @Value("${national-rl.push-url:}")
    private String pushUrl;

    @Value("${national-rl.bearer-token:}")
    private String bearerToken;

    /** Public DICOMweb base other hospitals can reach, e.g. https://dcma.duckdns.org/orthanc/dicom-web */
    @Value("${national-rl.wado-base-url:}")
    private String wadoBaseUrl;

    @Value("${hospital.facility-id}")
    private String facilityId;

    @Value("${hospital.name}")
    private String hospitalName;

    private final WebClient webClient;

    public RecordLocatorService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public void registerPointer(String studyUid, String phn, String patientName, String modality) {
        if (pushUrl == null || pushUrl.isBlank()) {
            log.info("Record Locator push disabled (RL_PUSH_URL not set)");
            return;
        }
        if (!PHN.matcher(phn == null ? "" : phn).matches()) {
            log.warn("Record Locator: PHN '{}' is blank, too long or has control characters, pointer not registered", phn);
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", studyUid);
            body.put("phn", phn);
            if (patientName != null && !patientName.isBlank()) body.put("patientName", patientName);
            body.put("resourceType", "ImagingStudy");
            body.put("docTypeLoinc", loinc(modality));
            body.put("contentType", "application/dicom+json");
            body.put("contentUrl", wadoBaseUrl + "/studies/" + studyUid);
            body.put("facilityHin", facilityId);
            body.put("facilityName", hospitalName);

            String url = pushUrl.replaceAll("/+$", "") + "/pointer-events";
            var req = webClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null && !bearerToken.isBlank()) {
                req = req.header("Authorization", "Bearer " + bearerToken);
            }
            ResponseEntity<String> resp = req.bodyValue(body)
                    .exchangeToMono(r -> r.toEntity(String.class)).block();
            if (resp != null && resp.getStatusCode().is2xxSuccessful()) {
                log.info("Record Locator pointer registered: POST {} -> {} study={} phn={}",
                        url, resp.getStatusCode().value(), studyUid, phn);
            } else {
                String b = resp == null || resp.getBody() == null ? "" : resp.getBody();
                if (b.length() > 500) b = b.substring(0, 500) + "…";
                log.warn("Record Locator rejected pointer (non-fatal): POST {} -> {} response={}",
                        url, resp == null ? "no response" : resp.getStatusCode().value(), b);
            }
        } catch (Exception e) {
            log.warn("Record Locator push failed for study {} (non-fatal): {}", studyUid, e.getMessage());
        }
    }

    private static String loinc(String modality) {
        return switch (modality == null ? "" : modality) {
            case "CT" -> "18747-6";
            case "MR" -> "18755-9";
            case "US" -> "18760-9";
            case "MG" -> "26347-5";
            default -> "18748-4";
        };
    }
}
