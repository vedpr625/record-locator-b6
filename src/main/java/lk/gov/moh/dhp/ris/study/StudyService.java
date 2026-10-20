package lk.gov.moh.dhp.ris.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks a sample JPEG by modality, converts to DICOM in memory,
 * and pushes to Orthanc REST API.
 *
 * Status is kept in memory (demo only — resets on restart).
 */
@Service
public class StudyService {

    private static final Logger log = LoggerFactory.getLogger(StudyService.class);

    @Value("${sample.images.path}")
    private String sampleImagesPath;

    @Value("${orthanc.url}")
    private String orthancUrl;

    @Value("${hospital.facility-id}")
    private String facilityId;

    @Value("${hospital.name}")
    private String hospitalName;

    private final WebClient webClient;
    private final MppsService mppsService;
    private final Random random = new Random();

    // accessionNumber → studyInstanceUID (in-memory, demo only)
    private final Map<String, String> completedStudies = new ConcurrentHashMap<>();

    public StudyService(WebClient.Builder webClientBuilder, MppsService mppsService) {
        this.webClient = webClientBuilder.build();
        this.mppsService = mppsService;
    }

    public boolean isCompleted(String accessionNumber) {
        return completedStudies.containsKey(accessionNumber);
    }

    public String getStudyUid(String accessionNumber) {
        return completedStudies.get(accessionNumber);
    }

    /**
     * Perform study: pick sample JPEG → convert to DICOM → push to Orthanc.
     * Returns the StudyInstanceUID on success.
     */
    public String performStudy(String accessionNumber, String phn,
                               String patientName, String modality) throws Exception {
        // 1. Pick sample JPEG by modality
        byte[] jpegBytes = pickSampleImage(modality);

        // 2. Generate UIDs
        String studyUid   = generateUid();
        String seriesUid  = generateUid();
        String instanceUid = generateUid();

        // 3. Convert JPEG → DICOM in memory
        byte[] dicomBytes = convertToDicom(jpegBytes, studyUid, seriesUid, instanceUid,
                accessionNumber, phn, patientName, modality);

        // 4. Push to Orthanc
        pushToOrthanc(dicomBytes);

        // 5. Remember as completed
        completedStudies.put(accessionNumber, studyUid);
        log.info("study performed: acc={} phn={} modality={} uid={}", accessionNumber, phn, modality, studyUid);

        // 6. Mark MWL item COMPLETED in dcm4chee (non-fatal — study already in Orthanc)
        mppsService.markCompleted(accessionNumber, studyUid, phn, patientName, modality);

        return studyUid;
    }

    private byte[] pickSampleImage(String modality) throws Exception {
        File dir = new File(sampleImagesPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new Exception("Sample images folder not found: " + sampleImagesPath);
        }

        // Filter by modality prefix: CR-chest-1.jpg, MR-brain-1.jpg, etc.
        File[] matches = dir.listFiles(f ->
                f.getName().toLowerCase().startsWith(modality.toLowerCase() + "-") &&
                (f.getName().endsWith(".jpg") || f.getName().endsWith(".jpeg")));

        // Fall back to any JPEG if no modality match
        if (matches == null || matches.length == 0) {
            matches = dir.listFiles(f ->
                    f.getName().endsWith(".jpg") || f.getName().endsWith(".jpeg"));
        }

        if (matches == null || matches.length == 0) {
            throw new Exception("No sample JPEG images found in " + sampleImagesPath);
        }

        File chosen = matches[random.nextInt(matches.length)];
        log.debug("using sample image: {}", chosen.getName());
        return Files.readAllBytes(chosen.toPath());
    }

    private byte[] convertToDicom(byte[] jpegBytes,
                                   String studyUid, String seriesUid, String instanceUid,
                                   String accessionNumber, String phn,
                                   String patientName, String modality) throws Exception {
        // Use dcm4che Attributes to build DICOM dataset
        org.dcm4che3.data.Attributes attrs = new org.dcm4che3.data.Attributes();

        // Patient module
        attrs.setString(org.dcm4che3.data.Tag.PatientID,   org.dcm4che3.data.VR.LO, phn);
        attrs.setString(org.dcm4che3.data.Tag.PatientName, org.dcm4che3.data.VR.PN, patientName);

        // Study module
        attrs.setString(org.dcm4che3.data.Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI, studyUid);
        attrs.setString(org.dcm4che3.data.Tag.AccessionNumber,  org.dcm4che3.data.VR.SH, accessionNumber);
        attrs.setString(org.dcm4che3.data.Tag.StudyDate, org.dcm4che3.data.VR.DA, todayDicom());
        attrs.setString(org.dcm4che3.data.Tag.StudyTime, org.dcm4che3.data.VR.TM, nowTimeDicom());
        attrs.setString(org.dcm4che3.data.Tag.InstitutionName, org.dcm4che3.data.VR.LO, hospitalName);

        // Series module
        attrs.setString(org.dcm4che3.data.Tag.SeriesInstanceUID, org.dcm4che3.data.VR.UI, seriesUid);
        attrs.setString(org.dcm4che3.data.Tag.Modality,          org.dcm4che3.data.VR.CS, modality);
        attrs.setInt(   org.dcm4che3.data.Tag.SeriesNumber,       org.dcm4che3.data.VR.IS, 1);

        // Instance module
        attrs.setString(org.dcm4che3.data.Tag.SOPInstanceUID, org.dcm4che3.data.VR.UI, instanceUid);
        attrs.setString(org.dcm4che3.data.Tag.SOPClassUID,    org.dcm4che3.data.VR.UI,
                sopClassForModality(modality));
        attrs.setString(org.dcm4che3.data.Tag.TransferSyntaxUID, org.dcm4che3.data.VR.UI,
                org.dcm4che3.data.UID.JPEGBaseline8Bit);
        attrs.setInt(org.dcm4che3.data.Tag.InstanceNumber, org.dcm4che3.data.VR.IS, 1);

        // Image pixel data — wrap JPEG as encapsulated pixel data
        org.dcm4che3.data.Attributes fmi = attrs.createFileMetaInformation(
                org.dcm4che3.data.UID.JPEGBaseline8Bit);

        // Read JPEG dimensions for image attributes
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(jpegBytes));
        if (img != null) {
            attrs.setInt(org.dcm4che3.data.Tag.Rows,             org.dcm4che3.data.VR.US, img.getHeight());
            attrs.setInt(org.dcm4che3.data.Tag.Columns,          org.dcm4che3.data.VR.US, img.getWidth());
            attrs.setInt(org.dcm4che3.data.Tag.BitsAllocated,    org.dcm4che3.data.VR.US, 8);
            attrs.setInt(org.dcm4che3.data.Tag.BitsStored,       org.dcm4che3.data.VR.US, 8);
            attrs.setInt(org.dcm4che3.data.Tag.HighBit,          org.dcm4che3.data.VR.US, 7);
            attrs.setInt(org.dcm4che3.data.Tag.SamplesPerPixel,  org.dcm4che3.data.VR.US, 3);
            attrs.setInt(org.dcm4che3.data.Tag.PixelRepresentation, org.dcm4che3.data.VR.US, 0);
            attrs.setString(org.dcm4che3.data.Tag.PhotometricInterpretation, org.dcm4che3.data.VR.CS, "YBR_FULL_422");
        }

        // Encapsulated pixel data
        org.dcm4che3.data.Fragments frags = attrs.newFragments(
                org.dcm4che3.data.Tag.PixelData, org.dcm4che3.data.VR.OB, 2);
        frags.add(new byte[0]); // basic offset table
        frags.add(jpegBytes);

        // Serialize to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        org.dcm4che3.io.DicomOutputStream dos = new org.dcm4che3.io.DicomOutputStream(baos,
                org.dcm4che3.data.UID.JPEGBaseline8Bit);
        dos.writeDataset(fmi, attrs);
        dos.close();

        return baos.toByteArray();
    }

    private void pushToOrthanc(byte[] dicomBytes) {
        webClient.post()
                .uri(orthancUrl + "/instances")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(dicomBytes)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String sopClassForModality(String modality) {
        return switch (modality) {
            case "CT" -> org.dcm4che3.data.UID.CTImageStorage;
            case "MR" -> org.dcm4che3.data.UID.MRImageStorage;
            case "US" -> org.dcm4che3.data.UID.UltrasoundImageStorage;
            case "MG" -> org.dcm4che3.data.UID.DigitalMammographyXRayImageStorageForPresentation;
            default   -> org.dcm4che3.data.UID.DigitalXRayImageStorageForPresentation; // CR, DX
        };
    }

    private String generateUid() {
        return org.dcm4che3.util.UIDUtils.createUID();
    }

    private String todayDicom() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String nowTimeDicom() {
        return java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
    }
}
