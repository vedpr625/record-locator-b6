package lk.gov.moh.dhp.ris.worklist;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /** Days back (including today) for which still-SCHEDULED items are listed. 0 = today only. */
    @Value("${worklist.days-back:0}")
    private int daysBack;

    /** Hospital timezone, used to decide what "today" is (server clock is often UTC). */
    @Value("${hospital.timezone:Asia/Colombo}")
    private String timezone;

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
        ExecutorService executor = Executors.newCachedThreadPool();
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
        Association as = null;

        try {
            // Remote connection
            Connection remote = new Connection();
            remote.setHostname(host);
            remote.setPort(port);

            // Build association request
            AAssociateRQ rq = new AAssociateRQ();
            rq.setCalledAET(remoteAeTitle);
            rq.setCallingAET(callingAeTitle);
            // Presentation context for MWL C-FIND
            rq.addPresentationContext(new PresentationContext(1,
                    UID.ModalityWorklistInformationModelFind,
                    UID.ImplicitVRLittleEndian,
                    UID.ExplicitVRLittleEndian));

            as = ae.connect(remote, rq);

            // Build MWL query keys
            Attributes keys = new Attributes();
            keys.setString(Tag.SpecificCharacterSet,    VR.CS, "ISO_IR 192");
            keys.setString(Tag.AccessionNumber,         VR.SH, "");
            keys.setString(Tag.PatientID,               VR.LO, "");
            keys.setString(Tag.PatientName,             VR.PN, "");
            keys.setString(Tag.StudyInstanceUID,        VR.UI, "");
            keys.setString(Tag.RequestedProcedureID,    VR.SH, "");

            // Scheduled Procedure Step Sequence
            Sequence spsSeq = keys.newSequence(Tag.ScheduledProcedureStepSequence, 1);
            Attributes spsItem = new Attributes();
            spsItem.setString(Tag.Modality,                          VR.CS, "");
            spsItem.setString(Tag.ScheduledProcedureStepStartDate,   VR.DA, startDateKey());
            spsItem.setString(Tag.ScheduledProcedureStepStartTime,   VR.TM, "");
            spsItem.setString(Tag.ScheduledProcedureStepDescription, VR.LO, "");
            spsItem.setString(Tag.ScheduledProcedureStepID,          VR.SH, "");
            spsItem.setString(Tag.ScheduledProcedureStepStatus,      VR.CS, "SCHEDULED");
            spsSeq.add(spsItem);

            // Issue C-FIND
            CountDownLatch done = new CountDownLatch(1);
            as.cfind(UID.ModalityWorklistInformationModelFind, Priority.NORMAL, keys,
                    UID.ImplicitVRLittleEndian,
                    new DimseRSPHandler(as.nextMessageID()) {
                        @Override
                        public void onDimseRSP(Association association, Attributes cmd, Attributes data) {
                            int status = cmd.getInt(Tag.Status, -1);
                            if (status == 0xFF00 || status == 0xFF01) {
                                // Pending — got a result
                                if (data != null) {
                                    WorklistItem item = toWorklistItem(data);
                                    if (item != null) items.add(item);
                                }
                            } else {
                                // Final response (0x0000 = success, others = error/cancel)
                                done.countDown();
                            }
                        }
                    });

            done.await(30, TimeUnit.SECONDS);

        } finally {
            if (as != null) {
                try { as.release(); } catch (Exception ignore) {}
            }
            executor.shutdown();
            scheduledExecutor.shutdown();
        }

        return items;
    }

    private WorklistItem toWorklistItem(Attributes attrs) {
        try {
            String accession   = attrs.getString(Tag.AccessionNumber, "");
            String patientId   = attrs.getString(Tag.PatientID, "");
            String patientName = attrs.getString(Tag.PatientName, "").replace("^", " ").trim();
            String studyUid    = attrs.getString(Tag.StudyInstanceUID, "");
            String reqProcId   = attrs.getString(Tag.RequestedProcedureID, "");

            Attributes sps = attrs.getNestedDataset(Tag.ScheduledProcedureStepSequence);
            String modality   = "";
            String date       = "";
            String time       = "";
            String description = "";
            String spsId      = "";
            if (sps != null) {
                modality    = sps.getString(Tag.Modality, "");
                date        = sps.getString(Tag.ScheduledProcedureStepStartDate, "");
                time        = sps.getString(Tag.ScheduledProcedureStepStartTime, "");
                description = sps.getString(Tag.ScheduledProcedureStepDescription, "");
                spsId       = sps.getString(Tag.ScheduledProcedureStepID, "");
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
                    .requestedProcedureId(reqProcId)
                    .scheduledProcedureStepId(spsId)
                    .studyInstanceUid(studyUid)
                    .build();
        } catch (Exception e) {
            log.warn("skipping malformed MWL entry: {}", e.getMessage());
            return null;
        }
    }

    private String todayDicom() {
        return java.time.LocalDate.now(java.time.ZoneId.of(timezone))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** DICOM date matching key: single day, or a range "yyyyMMdd-yyyyMMdd" when days-back > 0. */
    private String startDateKey() {
        String today = todayDicom();
        if (daysBack <= 0) return today;
        String from = java.time.LocalDate.now(java.time.ZoneId.of(timezone)).minusDays(daysBack)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return from + "-" + today;
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
