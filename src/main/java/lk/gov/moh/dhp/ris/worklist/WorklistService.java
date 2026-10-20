package lk.gov.moh.dhp.ris.worklist;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.service.BasicCFindSCU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Fetches today's Modality Worklist from dcm4chee via DICOM C-FIND.
 * Falls back to empty list if dcm4chee is unreachable (demo-safe).
 */
@Service
public class WorklistService {

    private static final Logger log = LoggerFactory.getLogger(WorklistService.class);

    @Value("${dcm4chee.host}")
    private String host;

    @Value("${dcm4chee.port}")
    private int port;

    @Value("${dcm4chee.ae-title}")
    private String remoteAeTitle;

    @Value("${dcm4chee.calling-ae}")
    private String callingAeTitle;

    /**
     * Returns today's MWL entries from dcm4chee.
     * Returns empty list (no crash) if dcm4chee is unreachable.
     */
    public List<WorklistItem> fetchTodayWorklist() {
        List<WorklistItem> results = new ArrayList<>();
        try {
            results = queryMwl();
        } catch (Exception e) {
            log.warn("MWL query failed (dcm4chee unreachable?): {}", e.getMessage());
        }
        return results;
    }

    private List<WorklistItem> queryMwl() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        Device device = new Device(callingAeTitle);
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduledExecutor);

        Connection conn = new Connection();
        device.addConnection(conn);

        ApplicationEntity ae = new ApplicationEntity(callingAeTitle);
        ae.setAssociationInitiator(true);
        ae.addConnection(conn);
        device.addApplicationEntity(ae);

        List<WorklistItem> items = new ArrayList<>();

        try {
            BasicCFindSCU cfind = new BasicCFindSCU();

            // Build MWL query keys — today's date
            Attributes keys = new Attributes();
            keys.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
            keys.setString(Tag.AccessionNumber, VR.SH, "");
            keys.setString(Tag.PatientID, VR.LO, "");
            keys.setString(Tag.PatientName, VR.PN, "");
            keys.setString(Tag.Modality, VR.CS, "");

            // Scheduled Procedure Step Sequence
            Sequence spsSeq = keys.newSequence(Tag.ScheduledProcedureStepSequence, 1);
            Attributes spsItem = new Attributes();
            spsItem.setString(Tag.Modality, VR.CS, "");
            spsItem.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, todayDicom());
            spsItem.setString(Tag.ScheduledProcedureStepStartTime, VR.TM, "");
            spsItem.setString(Tag.ScheduledProcedureStepDescription, VR.LO, "");
            spsItem.setString(Tag.ScheduledProcedureStepStatus, VR.CS, "SCHEDULED");
            spsSeq.add(spsItem);

            cfind.query(ae, Priority.NORMAL,
                    makeConnection(), remoteAeTitle,
                    UID.ModalityWorklistInformationModelFind,
                    keys,
                    result -> {
                        WorklistItem item = toWorklistItem(result);
                        if (item != null) items.add(item);
                    });

        } finally {
            executor.shutdown();
            scheduledExecutor.shutdown();
        }

        return items;
    }

    private Connection makeConnection() {
        Connection conn = new Connection();
        conn.setHostname(host);
        conn.setPort(port);
        return conn;
    }

    private WorklistItem toWorklistItem(Attributes attrs) {
        try {
            String accession = attrs.getString(Tag.AccessionNumber, "");
            String patientId = attrs.getString(Tag.PatientID, "");
            String patientName = attrs.getString(Tag.PatientName, "").replace("^", " ").trim();

            Attributes sps = attrs.getNestedDataset(Tag.ScheduledProcedureStepSequence);
            String modality = "";
            String date = "";
            String time = "";
            String description = "";
            if (sps != null) {
                modality = sps.getString(Tag.Modality, "");
                date = sps.getString(Tag.ScheduledProcedureStepStartDate, "");
                time = sps.getString(Tag.ScheduledProcedureStepStartTime, "");
                description = sps.getString(Tag.ScheduledProcedureStepDescription, "");
            }

            if (patientId.isBlank()) return null;

            return WorklistItem.builder()
                    .accessionNumber(accession)
                    .patientName(patientName)
                    .phn(patientId)
                    .modality(modality)
                    .studyDate(formatDate(date))
                    .scheduledTime(formatTime(time))
                    .procedureDescription(description)
                    .build();
        } catch (Exception e) {
            log.warn("skipping malformed MWL entry: {}", e.getMessage());
            return null;
        }
    }

    private String todayDicom() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String formatDate(String dicomDate) {
        if (dicomDate == null || dicomDate.length() != 8) return dicomDate;
        return dicomDate.substring(6) + "/" + dicomDate.substring(4, 6) + "/" + dicomDate.substring(0, 4);
    }

    private String formatTime(String dicomTime) {
        if (dicomTime == null || dicomTime.length() < 4) return "";
        return dicomTime.substring(0, 2) + ":" + dicomTime.substring(2, 4);
    }
}
