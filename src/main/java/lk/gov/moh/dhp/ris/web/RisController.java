package lk.gov.moh.dhp.ris.web;

import lk.gov.moh.dhp.ris.records.RecordItem;
import lk.gov.moh.dhp.ris.records.RecordsService;
import lk.gov.moh.dhp.ris.study.CompletedService;
import lk.gov.moh.dhp.ris.study.StudyService;
import lk.gov.moh.dhp.ris.worklist.WorklistItem;
import lk.gov.moh.dhp.ris.worklist.WorklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class RisController {

    private static final Logger log = LoggerFactory.getLogger(RisController.class);

    @Value("${hospital.name}")
    private String hospitalName;

    @Value("${hospital.short-code}")
    private String hospitalShortCode;

    @Value("${hospital.facility-id}")
    private String facilityId;

    private final WorklistService worklistService;
    private final StudyService studyService;
    private final RecordsService recordsService;
    private final CompletedService completedService;

    public RisController(WorklistService worklistService,
                         StudyService studyService,
                         RecordsService recordsService,
                         CompletedService completedService) {
        this.completedService = completedService;
        this.worklistService = worklistService;
        this.studyService = studyService;
        this.recordsService = recordsService;
    }

    /** Home → redirect to worklist tab */
    @GetMapping("/")
    public String home() {
        return "redirect:/worklist";
    }

    /** Tab 1: Worklist */
    @GetMapping("/worklist")
    public String worklist(Model model) {
        // Orders already performed (study exists in Orthanc) leave the worklist; they are in "Completed"
        java.util.Set<String> performed = completedService.performedAccessions();
        List<WorklistItem> items = worklistService.fetchTodayWorklist().stream()
                .filter(i -> !performed.contains(i.getAccessionNumber()))
                .toList();

        // Group by PHN (preserving order of first appearance)
        Map<String, List<WorklistItem>> patientGroups = new LinkedHashMap<>();
        for (WorklistItem item : items) {
            patientGroups.computeIfAbsent(item.getPhn(), k -> new java.util.ArrayList<>()).add(item);
        }

        model.addAttribute("hospitalName", hospitalName);
        model.addAttribute("hospitalShortCode", hospitalShortCode);
        model.addAttribute("activeTab", "worklist");
        model.addAttribute("items", items);
        model.addAttribute("patientGroups", patientGroups.entrySet());
        model.addAttribute("studyService", studyService);
        return "index";
    }

    /** AJAX: return worklist rows as JSON for auto-poll refresh */
    @GetMapping("/worklist/poll")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> worklistPoll() {
        java.util.Set<String> performed = completedService.performedAccessions();
        List<WorklistItem> items = worklistService.fetchTodayWorklist();
        List<Map<String, Object>> result = items.stream().map(item -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("accessionNumber",          item.getAccessionNumber());
            m.put("patientName",              item.getPatientName());
            m.put("phn",                      item.getPhn());
            m.put("modality",                 item.getModality());
            m.put("studyDate",                item.getStudyDate());
            m.put("scheduledTime",            item.getScheduledTime());
            m.put("procedureDescription",     item.getProcedureDescription());
            m.put("requestedProcedureId",     item.getRequestedProcedureId());
            m.put("scheduledProcedureStepId", item.getScheduledProcedureStepId());
            m.put("studyInstanceUid",         item.getStudyInstanceUid());
            m.put("completed",                studyService.isCompleted(item.getAccessionNumber())
                                                      || performed.contains(item.getAccessionNumber()));
            m.put("studyUid",                 studyService.getStudyUid(item.getAccessionNumber()));
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** POST: Perform study — AJAX call from worklist row */
    @PostMapping("/perform-study")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> performStudy(
            @RequestParam String accessionNumber,
            @RequestParam String phn,
            @RequestParam String patientName,
            @RequestParam String modality) {

        try {
            // Use the order's own StudyInstanceUID from the worklist (looked up server-side by
            // accession number) so the study matches what the hospital system created.
            String orderStudyUid = worklistService.fetchTodayWorklist().stream()
                    .filter(i -> accessionNumber.equals(i.getAccessionNumber()))
                    .map(WorklistItem::getStudyInstanceUid)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst()
                    .orElse(null);

            String studyUid = studyService.performStudy(accessionNumber, phn, patientName, modality,
                    orderStudyUid);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "studyUid", studyUid,
                    "message", "Study performed successfully"
            ));
        } catch (Exception e) {
            log.error("perform study failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed: " + e.getMessage()
            ));
        }
    }

    /** Tab 2: Completed studies (performed here; they leave the worklist but stay listed) */
    @GetMapping("/completed")
    public String completed(Model model) {
        model.addAttribute("hospitalName", hospitalName);
        model.addAttribute("hospitalShortCode", hospitalShortCode);
        model.addAttribute("activeTab", "completed");
        model.addAttribute("completed", completedService.list());
        return "index";
    }

    /** Tab 3: Records - search the Record Locator by PHN (any format) or by patient name */
    @GetMapping("/records")
    public String records(@RequestParam(required = false) String q,
                          @RequestParam(required = false) String phn,
                          @RequestParam(defaultValue = "phn") String by,
                          Model model) {
        String term = (q != null && !q.isBlank()) ? q.trim() : (phn == null ? null : phn.trim());
        boolean byName = "name".equals(by);
        model.addAttribute("hospitalName", hospitalName);
        model.addAttribute("hospitalShortCode", hospitalShortCode);
        model.addAttribute("activeTab", "records");
        model.addAttribute("searchTerm", term);
        model.addAttribute("searchBy", byName ? "name" : "phn");

        if (term != null && !term.isBlank()) {
            List<RecordItem> records = byName ? recordsService.searchByName(term)
                                              : recordsService.searchByPhn(term);
            model.addAttribute("records", records);
        }
        return "index";
    }
}
