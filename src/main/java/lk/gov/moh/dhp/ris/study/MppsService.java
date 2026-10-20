package lk.gov.moh.dhp.ris.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notifies dcm4chee that a Modality Performed Procedure Step (MPPS) is COMPLETED.
 *
 * Flow:
 *   1. POST /aets/DCM4CHEE/dimse/mppsscu  → N-CREATE  (status=IN PROGRESS)
 *   2. POST /aets/DCM4CHEE/dimse/mppsscu  → N-SET     (status=COMPLETED)
 *
 * dcm4chee then marks the matching SPS as COMPLETED, so it disappears
 * from the worklist on the next C-FIND query.
 *
 * If dcm4chee is unreachable the failure is logged but does NOT abort the study —
 * the DICOM is already in Orthanc and the study is recorded as completed.
 */
@Service
public class MppsService {

    private static final Logger log = LoggerFactory.getLogger(MppsService.class);

    @Value("${dcm4chee.rest-url}")
    private String dcm4cheeRestUrl;

    @Value("${hospital.name}")
    private String hospitalName;

    private final WebClient webClient;

    public MppsService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    /**
     * Mark the SPS for the given accessionNumber as COMPLETED in dcm4chee.
     * Uses the dcm4chee MPPS REST proxy endpoint.
     *
     * @param accessionNumber  the study accession number from the worklist
     * @param studyInstanceUid the freshly created StudyInstanceUID
     * @param patientId        PHN
     * @param patientName      patient name
     * @param modality         CR / CT / MR / …
     */
    public void markCompleted(String accessionNumber, String studyInstanceUid,
                              String patientId, String patientName, String modality) {
        try {
            String mppsUid = org.dcm4che3.util.UIDUtils.createUID();
            String now     = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

            // ── N-CREATE: IN PROGRESS ──────────────────────────────────
            Map<String, Object> nCreate = buildMppsNCreate(
                    mppsUid, accessionNumber, studyInstanceUid,
                    patientId, patientName, modality, now, nowTime);

            postMpps(mppsUid, nCreate, "N-CREATE");

            // ── N-SET: COMPLETED ───────────────────────────────────────
            Map<String, Object> nSet = buildMppsNSet(mppsUid, now, nowTime);
            postMpps(mppsUid, nSet, "N-SET");

            log.info("MPPS completed: acc={} mppsUid={}", accessionNumber, mppsUid);

        } catch (Exception e) {
            log.warn("MPPS update failed for acc={} — worklist item will remain (non-fatal): {}",
                    accessionNumber, e.getMessage());
        }
    }

    // ── REST POST ──────────────────────────────────────────────────────────────

    private void postMpps(String mppsUid, Map<String, Object> body, String step) {
        // dcm4chee MPPS endpoint: POST /aets/DCM4CHEE/dimse/mppsscu/{mppsUid}
        String url = dcm4cheeRestUrl + "/aets/DCM4CHEE/dimse/mppsscu/" + mppsUid;
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        log.debug("MPPS {} sent to {}", step, url);
    }

    // ── DICOM MPPS dataset builders (JSON encoding per DICOMweb STOW-RS) ──────

    private Map<String, Object> buildMppsNCreate(
            String mppsUid, String accessionNumber, String studyInstanceUid,
            String patientId, String patientName, String modality,
            String date, String time) {

        Map<String, Object> ds = new LinkedHashMap<>();

        // SOP Instance UID
        ds.put("00080018", tag("UI", mppsUid));
        // Patient ID
        ds.put("00100020", tag("LO", patientId));
        // Patient Name
        ds.put("00100010", tag("PN", patientName));
        // Modality
        ds.put("00080060", tag("CS", modality));
        // Performed Procedure Step Status — IN PROGRESS
        ds.put("00400252", tag("CS", "IN PROGRESS"));
        // Performed Procedure Step Start Date
        ds.put("00400244", tag("DA", date));
        // Performed Procedure Step Start Time
        ds.put("00400245", tag("TM", time));
        // Performed Procedure Step ID
        ds.put("00400253", tag("SH", accessionNumber));
        // Performed Station Name
        ds.put("00400242", tag("SH", "HOSPITAL-RIS"));
        // Performed Station AE Title
        ds.put("00400241", tag("AE", "HOSPITAL-RIS"));
        // Study Instance UID
        ds.put("0020000D", tag("UI", studyInstanceUid));
        // Performed Series Sequence (one series)
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("0020000E", tag("UI", org.dcm4che3.util.UIDUtils.createUID())); // SeriesInstanceUID
        series.put("00400254", tag("LO", "Performed Procedure"));
        ds.put("00400340", seq(series)); // Performed Series Sequence
        // Referenced Study Sequence
        Map<String, Object> refStudy = new LinkedHashMap<>();
        refStudy.put("00081150", tag("UI", "1.2.840.10008.3.1.2.3.1")); // SOP Class = Detached Study Mgmt
        refStudy.put("00081155", tag("UI", studyInstanceUid));
        ds.put("00081110", seq(refStudy));
        // Scheduled Step Attributes Sequence
        Map<String, Object> ssas = new LinkedHashMap<>();
        ssas.put("00400009", tag("SH", accessionNumber)); // Scheduled Procedure Step ID
        ssas.put("00400007", tag("LO", modality + " Study")); // Scheduled Procedure Step Description
        ssas.put("00080050", tag("SH", accessionNumber)); // Accession Number
        ssas.put("0020000D", tag("UI", studyInstanceUid)); // Study Instance UID
        ds.put("00400270", seq(ssas));

        return ds;
    }

    private Map<String, Object> buildMppsNSet(String mppsUid, String date, String time) {
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("00080018", tag("UI", mppsUid));
        // Status → COMPLETED
        ds.put("00400252", tag("CS", "COMPLETED"));
        // End date/time
        ds.put("00400250", tag("DA", date));
        ds.put("00400251", tag("TM", time));
        return ds;
    }

    // ── DICOMweb JSON helpers ──────────────────────────────────────────────────

    private Map<String, Object> tag(String vr, String value) {
        return Map.of("vr", vr, "Value", List.of(value));
    }

    private Map<String, Object> seq(Map<String, Object> item) {
        return Map.of("vr", "SQ", "Value", List.of(item));
    }
}
