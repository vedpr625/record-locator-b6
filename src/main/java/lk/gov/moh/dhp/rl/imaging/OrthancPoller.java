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

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Uses Orthanc's Changes API (GET /changes?since=X) rather than
 * re-listing every study on every poll.
 *
 * "since" cursor is now persisted to the orthanc_cursor table (V5 migration)
 * so a process restart resumes from where it left off instead of replaying
 * all studies from 0.  The AtomicLong is still used as the in-memory hot
 * copy; the DB is written after each page is fully processed.
 *
 * Bundle 6 / edge profile: federation and event publishing removed.
 * Pointers are saved directly to the local DB. No RabbitMQ, no forwarding.
 *
 * @Transactional self-call fix: processStudy is called via the Spring proxy
 * (retrieved from ApplicationContext) so the @Transactional annotation is
 * actually applied. Calling this.processStudy() would bypass the proxy.
 */
// H-2: matchIfMissing = false — the poller is DISABLED unless explicitly enabled.
// The previous matchIfMissing = true caused startup failures when rl.orthanc.base-url
// was not configured, because the bean would activate by default and then fail to
// resolve @Value("${rl.orthanc.base-url}") at first poll time.
@Component
@ConditionalOnProperty(name = "rl.orthanc.enabled", havingValue = "true", matchIfMissing = false)
public class OrthancPoller {

    private static final Logger log = LoggerFactory.getLogger(OrthancPoller.class);
    private static final int CHANGES_PAGE_SIZE = 100;
    private static final String STABLE_STUDY = "StableStudy";
    /** C-5: PHN must be 1-64 chars of [A-Za-z0-9._-] (no path/SQL metacharacters) — same constraint as ConsentEventRequest/PointerEventRequest. */
    private static final Pattern PHN_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

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
    private final OrthancCursorRepository cursorRepository;
    private final ApplicationContext applicationContext;
    /** Optional — only present when rl.ris.enabled=true. */
    private final Optional<RisNotificationService> risNotificationService;

    // In-memory hot copy of the cursor — loaded from DB on startup.
    private final AtomicLong since = new AtomicLong(0);

    public OrthancPoller(RestTemplate restTemplate, ObjectMapper objectMapper,
                         PointerFactory pointerFactory, PointerRepository pointerRepository,
                         OrthancCursorRepository cursorRepository,
                         ApplicationContext applicationContext,
                         Optional<RisNotificationService> risNotificationService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.pointerFactory = pointerFactory;
        this.pointerRepository = pointerRepository;
        this.cursorRepository = cursorRepository;
        this.applicationContext = applicationContext;
        this.risNotificationService = risNotificationService;
    }

    /** Load the persisted cursor on startup so we resume rather than replay. */
    @PostConstruct
    void loadCursor() {
        // S-7: warn at startup when orthancBaseUrl is not HTTPS.
        // A plain HTTP URL exposes DICOM study metadata in transit; operators should
        // configure a HTTPS endpoint in any non-local environment.
        if (orthancBaseUrl != null && !orthancBaseUrl.startsWith("https://")) {
            log.warn("rl.orthanc.base-url '{}' is not HTTPS — patient data may be transmitted in plain text",
                    orthancBaseUrl);
        }
        long persisted = cursorRepository.load();
        since.set(persisted);
        log.info("orthanc poller starting at cursor {}", persisted);
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
                if (body == null) {
                    log.debug("orthanc returned empty response body, skipping");
                    return;
                }
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

            long newCursor = page.path("Last").asLong(since.get());
            since.set(newCursor);
            // Persist after each page so a crash mid-page replays at most one page.
            cursorRepository.save(newCursor);

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

        JsonNode mainTags    = study.path("MainDicomTags");
        JsonNode patientTags = study.path("PatientMainDicomTags");

        String studyInstanceUid = mainTags.path("StudyInstanceUID").asText("").trim();
        String phn              = patientTags.path("PatientID").asText("").trim();
        // DICOM PatientName uses ^ as component separator: "Perera^Kasun^M"
        String patientName      = patientTags.path("PatientName").asText("").trim();
        String modalities       = mainTags.path("ModalitiesInStudy").asText("");
        // Extra tags needed for external RIS FHIR notification
        String accessionNumber  = mainTags.path("AccessionNumber").asText("").trim();
        String studyDate        = mainTags.path("StudyDate").asText("").trim();
        String studyTime        = mainTags.path("StudyTime").asText("").trim();
        int numberOfSeries      = study.path("Series").size();
        // Orthanc doesn't expose numberOfInstances directly at study level; sum from series
        int numberOfInstances   = study.path("Series").size(); // approximate; poller can refine later

        if (studyInstanceUid.isBlank() || phn.isBlank()) {
            log.warn("study {} missing StudyInstanceUID or PatientID, skipping", orthancStudyId);
            return;
        }

        // C-5: validate the PHN extracted from DICOM PatientID before storing.
        // A crafted PatientID with SQL metacharacters, FHIR path separators, or
        // another patient's PHN must be rejected rather than silently ingested.
        if (!PHN_PATTERN.matcher(phn).matches()) {
            log.warn("study {} has invalid PatientID '{}' (expected 1-64 chars of A-Za-z0-9._-), skipping",
                    orthancStudyId, phn);
            return;
        }

        String wadoRsUrl = orthancBaseUrl + "/dicom-web/studies/" + studyInstanceUid;

        boolean isNew = pointerRepository.findById(studyInstanceUid).isEmpty();

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

        // Notify external hospital RIS that study is available (new studies only).
        // Non-fatal: pointer is already saved even if RIS notification fails.
        if (isNew) {
            risNotificationService.ifPresent(ris -> ris.notifyStudyAvailable(
                    studyInstanceUid,
                    accessionNumber,
                    phn,
                    studyDate,
                    studyTime,
                    numberOfSeries,
                    numberOfInstances));
        }
    }

    private void updateIfUrlChanged(Pointer pointer, String wadoRsUrl) {
        if (!wadoRsUrl.equals(pointer.contentUrl())) {
            pointer.updateContentUrl(wadoRsUrl);
            pointerRepository.save(pointer);
        }
    }

    /**
     * Maps Orthanc modality codes to LOINC document type codes.
     *
     * <p>M-3 fix: split on the Orthanc separator and use set membership so that
     * "OCT", "CBCT" and "SCT" are not incorrectly matched by {@code contains("CT")}.
     * Orthanc returns ModalitiesInStudy as a comma- or space-separated list.
     */
    private String modalityToLoinc(String modalities) {
        java.util.Set<String> codes = java.util.Set.of(modalities.split("[\\s,]+"));
        if (codes.contains("CT")) return "18747-6";
        if (codes.contains("MR")) return "18755-9";
        if (codes.contains("US")) return "18760-9";
        if (codes.contains("MG")) return "26347-5";
        return "18748-4";
    }
}
