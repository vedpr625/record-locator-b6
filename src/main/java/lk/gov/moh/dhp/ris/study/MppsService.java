package lk.gov.moh.dhp.ris.study;

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
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends DICOM MPPS N-CREATE (IN PROGRESS) + N-SET (COMPLETED) to dcm4chee
 * via the DICOM network (not REST — dcm4chee 5.x has no REST MPPS write endpoint).
 *
 * dcm4chee then marks the matching SPS as COMPLETED so it disappears
 * from the worklist on the next C-FIND query.
 *
 * Non-fatal: failure is logged but does NOT abort the study.
 */
@Service
public class MppsService {

    private static final Logger log = LoggerFactory.getLogger(MppsService.class);

    @Value("${dcm4chee.host}")
    private String dcm4cheeHost;

    @Value("${dcm4chee.port}")
    private int dcm4cheePort;

    @Value("${dcm4chee.ae-title}")
    private String remoteAeTitle;

    @Value("${dcm4chee.calling-ae}")
    private String callingAeTitle;

    @Value("${hospital.name}")
    private String hospitalName;

    public void markCompleted(String accessionNumber, String studyInstanceUid,
                              String patientId, String patientName, String modality) {
        try {
            String mppsUid = org.dcm4che3.util.UIDUtils.createUID();
            String date    = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            String time    = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

            ExecutorService executor = Executors.newCachedThreadPool();
            ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

            Device device = new Device(callingAeTitle + "-MPPS");
            device.setExecutor(executor);
            device.setScheduledExecutor(scheduledExecutor);

            Connection conn = new Connection();
            device.addConnection(conn);

            ApplicationEntity ae = new ApplicationEntity(callingAeTitle);
            ae.setAssociationInitiator(true);
            ae.addConnection(conn);
            device.addApplicationEntity(ae);

            Association as = null;
            try {
                Connection remote = new Connection();
                remote.setHostname(dcm4cheeHost);
                remote.setPort(dcm4cheePort);

                AAssociateRQ rq = new AAssociateRQ();
                rq.setCalledAET(remoteAeTitle);
                rq.setCallingAET(callingAeTitle);
                rq.addPresentationContext(new PresentationContext(1,
                        UID.ModalityPerformedProcedureStep,
                        UID.ImplicitVRLittleEndian,
                        UID.ExplicitVRLittleEndian));

                as = ae.connect(remote, rq);

                // ── N-CREATE: IN PROGRESS ──────────────────────────────
                Attributes nCreateAttrs = buildNCreate(mppsUid, accessionNumber,
                        studyInstanceUid, patientId, patientName, modality, date, time);

                CountDownLatch latch1 = new CountDownLatch(1);
                as.ncreate(UID.ModalityPerformedProcedureStep, mppsUid, nCreateAttrs,
                        UID.ImplicitVRLittleEndian,
                        new DimseRSPHandler(as.nextMessageID()) {
                            @Override
                            public void onDimseRSP(Association a, Attributes cmd, Attributes data) {
                                int status = cmd.getInt(Tag.Status, -1);
                                log.debug("MPPS N-CREATE response status=0x{}", Integer.toHexString(status));
                                latch1.countDown();
                            }
                        });
                latch1.await(10, TimeUnit.SECONDS);

                // ── N-SET: COMPLETED ───────────────────────────────────
                Attributes nSetAttrs = buildNSet(date, time);

                CountDownLatch latch2 = new CountDownLatch(1);
                as.nset(UID.ModalityPerformedProcedureStep, mppsUid, nSetAttrs,
                        UID.ImplicitVRLittleEndian,
                        new DimseRSPHandler(as.nextMessageID()) {
                            @Override
                            public void onDimseRSP(Association a, Attributes cmd, Attributes data) {
                                int status = cmd.getInt(Tag.Status, -1);
                                log.debug("MPPS N-SET response status=0x{}", Integer.toHexString(status));
                                latch2.countDown();
                            }
                        });
                latch2.await(10, TimeUnit.SECONDS);

                log.info("MPPS COMPLETED sent via DICOM: acc={} mppsUid={}", accessionNumber, mppsUid);

            } finally {
                if (as != null) try { as.release(); } catch (Exception ignore) {}
                executor.shutdown();
                scheduledExecutor.shutdown();
            }

        } catch (Exception e) {
            log.warn("MPPS update failed for acc={} — worklist item will remain (non-fatal): {}",
                    accessionNumber, e.getMessage());
        }
    }

    private Attributes buildNCreate(String mppsUid, String accessionNumber,
                                     String studyInstanceUid, String patientId,
                                     String patientName, String modality,
                                     String date, String time) {
        Attributes attrs = new Attributes();

        // Patient
        attrs.setString(Tag.PatientID,   VR.LO, patientId);
        attrs.setString(Tag.PatientName, VR.PN, patientName);

        // MPPS status
        attrs.setString(Tag.PerformedProcedureStepStatus,    VR.CS, "IN PROGRESS");
        attrs.setString(Tag.PerformedProcedureStepStartDate, VR.DA, date);
        attrs.setString(Tag.PerformedProcedureStepStartTime, VR.TM, time);
        attrs.setString(Tag.PerformedProcedureStepID,        VR.SH, accessionNumber);
        attrs.setString(Tag.PerformedStationAETitle,         VR.AE, callingAeTitle);
        attrs.setString(Tag.PerformedStationName,            VR.SH, "HOSPITAL-RIS");
        attrs.setString(Tag.Modality,                        VR.CS, modality);

        // Study reference
        attrs.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUid);

        // Performed Series Sequence
        Sequence seriesSeq = attrs.newSequence(Tag.PerformedSeriesSequence, 1);
        Attributes series = new Attributes();
        series.setString(Tag.SeriesInstanceUID,       VR.UI, org.dcm4che3.util.UIDUtils.createUID());
        series.setString(Tag.PerformingPhysicianName, VR.PN, "");
        series.newSequence(Tag.ReferencedImageSequence, 0);
        seriesSeq.add(series);

        // Scheduled Step Attributes Sequence
        Sequence ssas = attrs.newSequence(Tag.ScheduledStepAttributesSequence, 1);
        Attributes ssa = new Attributes();
        ssa.setString(Tag.StudyInstanceUID,              VR.UI, studyInstanceUid);
        ssa.setString(Tag.AccessionNumber,               VR.SH, accessionNumber);
        ssa.setString(Tag.RequestedProcedureID,          VR.SH, accessionNumber);
        ssa.setString(Tag.ScheduledProcedureStepID,      VR.SH, accessionNumber);
        ssa.setString(Tag.ScheduledProcedureStepDescription, VR.LO, modality + " Study");
        ssas.add(ssa);

        return attrs;
    }

    private Attributes buildNSet(String date, String time) {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PerformedProcedureStepStatus,  VR.CS, "COMPLETED");
        attrs.setString(Tag.PerformedProcedureStepEndDate, VR.DA, date);
        attrs.setString(Tag.PerformedProcedureStepEndTime, VR.TM, time);
        return attrs;
    }
}
