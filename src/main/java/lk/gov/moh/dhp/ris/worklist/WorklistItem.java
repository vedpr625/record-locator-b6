package lk.gov.moh.dhp.ris.worklist;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorklistItem {
    private String accessionNumber;
    private String patientName;
    private String phn;           // PatientID = PHN (10 digits)
    private String modality;      // CR, MR, CT, DX, US
    private String studyDate;     // YYYYMMDD
    private String scheduledTime; // HHmm
    private String procedureDescription;
}
