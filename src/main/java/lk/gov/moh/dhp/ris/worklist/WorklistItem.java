package lk.gov.moh.dhp.ris.worklist;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorklistItem {
    private String accessionNumber;
    private String patientName;
    private String phn;                    // PatientID = PHN (10 digits)
    private String modality;               // CR, MR, CT, DX, US
    private String studyDate;              // formatted dd/MM/yyyy
    private String scheduledTime;          // HH:mm
    private String procedureDescription;
    private String requestedProcedureId;   // RequestedProcedureID tag
    private String scheduledProcedureStepId; // ScheduledProcedureStepID tag
    private String studyInstanceUid;       // StudyInstanceUID (if present in MWL)
}
