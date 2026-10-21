package lk.gov.moh.dhp.ris.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Studies already performed at this hospital, read from the local Orthanc (so the list
 * survives RIS restarts). Shown in the "Completed" tab after a study leaves the worklist.
 */
@Service
public class CompletedService {

    private static final Logger log = LoggerFactory.getLogger(CompletedService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${orthanc.url}")
    private String orthancUrl;

    @Value("${hhmis.viewer-base-url:}")
    private String viewerBaseUrl;

    private final WebClient webClient;

    public CompletedService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    /** Newest first, at most 200. Empty list if Orthanc is unreachable. */
    public List<Map<String, String>> list() {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String body = webClient.get().uri(orthancUrl + "/studies?expand")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8));
            if (body == null) return out;
            List<JsonNode> studies = new ArrayList<>();
            MAPPER.readTree(body).forEach(studies::add);
            studies.sort(Comparator.comparing((JsonNode n) -> n.path("LastUpdate").asText("")).reversed());
            for (JsonNode st : studies) {
                if (out.size() >= 200) break;
                JsonNode m = st.path("MainDicomTags");
                JsonNode p = st.path("PatientMainDicomTags");
                String uid = m.path("StudyInstanceUID").asText("");
                Map<String, String> row = new LinkedHashMap<>();
                row.put("patientName", p.path("PatientName").asText("").replace('^', ' ').trim());
                row.put("phn", p.path("PatientID").asText(""));
                row.put("accession", m.path("AccessionNumber").asText(""));
                row.put("modality", m.path("ModalitiesInStudy").asText(""));
                row.put("studyDate", fmtDate(m.path("StudyDate").asText("")));
                row.put("studyUid", uid);
                row.put("viewerUrl", viewerBaseUrl == null || viewerBaseUrl.isBlank() || uid.isBlank()
                        ? "" : viewerBaseUrl + "?StudyInstanceUIDs=" + uid);
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("could not list completed studies from Orthanc: {}", e.getMessage());
        }
        return out;
    }

    private static String fmtDate(String d) {
        return d.length() == 8 ? d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6) : d;
    }
}
