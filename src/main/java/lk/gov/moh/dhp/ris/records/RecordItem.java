package lk.gov.moh.dhp.ris.records;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecordItem {
    private String studyUid;
    private String patientName;
    private String phn;
    private String modality;
    private String studyDate;
    private String facilityName;
    private boolean local;   // true = this hospital, false = remote
}
