package lk.gov.moh.dhp.rl.imaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.moh.dhp.rl.ingest.NewPointerRequest;
import lk.gov.moh.dhp.rl.ingest.PointerFactory;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Uses Orthanc's Changes API (GET /changes?since=X) rather than
 * re-listing every study Orthanc has ever stored on every poll.
 *
 * "since" is kept in memory, not persisted — if this process restarts,
 * we replay from 0, which just means re-checking (not re-creating,
 * findById guards that) studies already seen.
 *
 * Bundle 6 / edge profile: federation and event publishing removed.
 * Pointers are saved directly to the local DB. No RabbitMQ, no forwarding.
 *
 * @Transactional self-call fix: processStudy is called via the Spring proxy
 * (retrieved from ApplicationContext) so the @Transactional annotation is
 * actually applied. Calling this.processStudy() would bypass the proxy.
 */
@Component
@ConditionalOnProperty(name = "rl.orthanc.enabled", havingValue = "true", matchIfMissing = true)
public class OrthancPoller {

    private static final Logger log = LoggerFactory.getLogger(OrthancPoller.class);
    private static final int CHANGES_PAGE_SIZE = 100;
    private static final String STABLE_STUDY = "StableStudy";

    @Value("${rl.orthanc.base-url}")
    private String orthancBaseUrl;

    @Value("${rl.orthanc.facility-hin}")
    private String facilityHin;

    @Value("${rl.orthanc.facility-name}")
    private String facilityName;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final PointerFactory pointerFactory;
    private final PointerRepository pointerRepository;
    private final ApplicationContext applicationContext;

    // AtomicLong so concurrent scheduler threads can't race on the read-modify-write.
    private final AtomicLong since = new AtomicLong(0);

    public OrthancPoller(RestTemplate restTemplate, ObjectMapper objectMapper,
                          PointerFactory pointerFactory, PointerRepository pointerRepository,
                          ApplicationContext applicationContext) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.pointerFactory = pointerFactory;
        this.pointerRepository = pointerRepository;
        this.applicationContext = applicationContext;
    }

    @Scheduled(fixedDelayString = "${rl.orthanc.poll-interval-ms:30000}")
    void poll() {
        // Resolve the proxy-wrapped self so @Transactional on processStudy is honoured.
        OrthancPoller self = applicationContext.getBean(OrthancPoller.class);

        boolean done = false;
        while (!done) {
            JsonNode page;
            try {
                String url = orthancBaseUrl + "/changes?since=" + since.get() + "&limit=" + CHANGES_PAGE_SIZE;
                String body = restTemplate.getForObject(url, String.class);
                page = objectMapper.readTree(body);
            } catch (Exception e) {
                log.debug("orthanc unreachable: {}", e.getMessage());
                return;
            }

            for (JsonNode change : page.path("Changes")) {
                if (STABLE_STUDY.equals(change.path("ChangeType").asText())) {
                    try {
                        // Per-study try/catch: one bad study logs a warning and
                        // continues. Without this, a single malformed study aborts
                        // the entire page and the cursor sticks there forever.
                        self.processStudy(change.path("ID").asText());
                    } catch (Exception e) {
                        log.warn("failed to process study {}: {}", change.path("ID").asText(), e.getMessage());
                    }
                }
            }

            since.set(page.path("Last").asLong(since.get()));
            done = page.path("Done").asBoolean(true);
        }
    }

    @Transactional
    public void processStudy(String orthancStudyId) {
        JsonNode study;
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    orthancBaseUrl + "/studies/" + orthancStudyId, String.class);
            study = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("could not read study {}: {}", orthancStudyId, e.getMessage());
            return;
        }

        JsonNode mainTags = study.path("MainDicomTags");
        JsonNode patientTags = study.path("PatientMainDicomTags");

        String studyInstanceUid = mainTags.path("StudyInstanceUID").asText("").trim();
        String phn = patientTags.path("PatientID").asText("").trim();
        // DICOM PatientName uses ^ as component separator: "Perera^Kasun^M" → keep as-is for now,
        // OpenSearch will index it for free-text search.
        String patientName = patientTags.path("PatientName").asText("").trim();
        String modalities = mainTags.path("ModalitiesInStudy").asText("");

        if (studyInstanceUid.isBlank() || phn.isBlank()) {
            log.warn("study {} missing StudyInstanceUID or PatientID, skipping", orthancStudyId);
            return;
        }

        String wadoRsUrl = orthancBaseUrl + "/dicom-web/studies/" + studyInstanceUid;

        pointerRepository.findById(studyInstanceUid).ifPresentOrElse(
                existing -> updateIfUrlChanged(existing, wadoRsUrl),
                () -> pointerFactory.create(new NewPointerRequest(
                        studyInstanceUid,
                        phn,
                        patientName.isBlank() ? null : patientName,
                        "ImagingStudy",
                        modalityToLoinc(modalities),
                        "application/dicom+json",
                        wadoRsUrl,
                        facilityHin,
                        facilityName,
                        orthancStudyId,
                        null,
                        false)));
    }

    private void updateIfUrlChanged(Pointer pointer, String wadoRsUrl) {
        if (!wadoRsUrl.equals(pointer.contentUrl())) {
            pointer.updateContentUrl(wadoRsUrl);
            pointerRepository.save(pointer);
        }
    }

    private String modalityToLoinc(String modalities) {
        if (modalities.contains("CT")) return "18747-6";
        if (modalities.contains("MR")) return "18755-9";
        if (modalities.contains("US")) return "18760-9";
        if (modalities.contains("MG")) return "26347-5";
        return "18748-4";
    }
}
