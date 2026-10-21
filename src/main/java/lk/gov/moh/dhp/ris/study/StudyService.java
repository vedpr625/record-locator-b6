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

    @Value("${dcm4chee.rest-url}")
    private String dcm4cheeRestUrl;

    @Value("${dcm4chee.ae-title}")
    private String dcm4cheeAeTitle;

    @Value("${hospital.facility-id}")
    private String facilityId;

    @Value("${hospital.name}")
    private String hospitalName;

    private final WebClient webClient;
    private final MppsService mppsService;
    private final HhmisService hhmisService;
    private final Random random = new Random();

    // accessionNumber → studyInstanceUID (in-memory, demo only)
    private final Map<String, String> completedStudies = new ConcurrentHashMap<>();

    public StudyService(WebClient.Builder webClientBuilder, MppsService mppsService,
                        HhmisService hhmisService) {
        this.webClient = webClientBuilder.build();
        this.mppsService = mppsService;
        this.hhmisService = hhmisService;
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

        // 6. Mark MWL item COMPLETED in dcm4chee via MPPS (non-fatal)
        mppsService.markCompleted(accessionNumber, studyUid, phn, patientName, modality);

        // 7. Delete MWL item via REST so it disappears from worklist immediately
        //    (dcmUpdateMWLStatus not available in this schema version)
        deleteMwlItem(accessionNumber);

        // 8. Notify HHMIS that study is available (non-fatal)
        int[] counts = queryOrthancCounts(studyUid);
        hhmisService.notifyStudyDone(studyUid, accessionNumber, phn, modality,
                counts[0], counts[1]);

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

    /**
     * Delete the MWL item from dcm4chee via REST after study is performed.
     * dcm4chee REST: DELETE /aets/{ae}/rs/mwlitems/{studyUID}/{spsID}
     * We use accession number as the SPS ID (matches how we create MWL items).
     * Non-fatal — logs warning if it fails.
     */
    private void deleteMwlItem(String accessionNumber) {
        try {
            // First find the MWL item to get the study UID and SPS ID
            // dcm4chee MWL search: filter by AccessionNumber DICOM tag (00080050)
            String searchUrl = dcm4cheeRestUrl + "/aets/" + dcm4cheeAeTitle
                    + "/rs/mwlitems?00080050=" + accessionNumber + "&fuzzymatching=false";
            String response = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null || response.equals("[]") || response.isBlank()) {
                log.debug("MWL item not found for acc={} (may already be gone)", accessionNumber);
                return;
            }

            // Parse study UID and SPS ID from response
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(response);
            if (!arr.isArray() || arr.size() == 0) return;

            com.fasterxml.jackson.databind.JsonNode item = arr.get(0);
            String studyUidForMwl = getTagValue(item, "0020000D"); // StudyInstanceUID
            com.fasterxml.jackson.databind.JsonNode spsSeq = item.path("00400100").path("Value");
            String spsId = accessionNumber; // fallback
            if (spsSeq.isArray() && spsSeq.size() > 0) {
                String fromSeq = getTagValue(spsSeq.get(0), "00400009");
                if (fromSeq != null && !fromSeq.isEmpty()) spsId = fromSeq;
            }

            if (studyUidForMwl == null || studyUidForMwl.isEmpty()) {
                log.warn("Cannot delete MWL item for acc={}: no StudyInstanceUID in response", accessionNumber);
                return;
            }

            String deleteUrl = dcm4cheeRestUrl + "/aets/" + dcm4cheeAeTitle
                    + "/rs/mwlitems/" + studyUidForMwl + "/" + spsId;
            webClient.delete()
                    .uri(deleteUrl)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("MWL item deleted via REST: acc={} studyUid={} spsId={}", accessionNumber, studyUidForMwl, spsId);

        } catch (Exception e) {
            log.warn("MWL item deletion failed for acc={} (non-fatal): {}", accessionNumber, e.getMessage());
        }
    }

    private String getTagValue(com.fasterxml.jackson.databind.JsonNode node, String tag) {
        try {
            com.fasterxml.jackson.databind.JsonNode val = node.path(tag).path("Value");
            if (val.isArray() && val.size() > 0) return val.get(0).asText();
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * Query Orthanc for the actual series and instance counts for a study.
     * Orthanc REST: GET /studies?StudyInstanceUID=<uid>  → array of study IDs
     *               GET /studies/<id>  → { Series: [...] }
     *               GET /series/<id>   → { Instances: [...] }
     * Returns [numberOfSeries, numberOfInstances], defaults to [1,1] on any error.
     */
    private int[] queryOrthancCounts(String studyInstanceUid) {
        try {
            // Find Orthanc internal study ID by StudyInstanceUID
            String searchResp = webClient.get()
                    .uri(orthancUrl + "/studies?StudyInstanceUID=" + studyInstanceUid)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            // Response is array of internal Orthanc study IDs
            com.fasterxml.jackson.databind.JsonNode ids = mapper.readTree(searchResp);
            if (!ids.isArray() || ids.size() == 0) {
                log.debug("Orthanc: study {} not found yet, using counts 1/1", studyInstanceUid);
                return new int[]{1, 1};
            }

            String orthancStudyId = ids.get(0).asText();
            String studyResp = webClient.get()
                    .uri(orthancUrl + "/studies/" + orthancStudyId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            com.fasterxml.jackson.databind.JsonNode study = mapper.readTree(studyResp);
            com.fasterxml.jackson.databind.JsonNode seriesArr = study.path("Series");

            int numSeries = seriesArr.isArray() ? seriesArr.size() : 1;
            int numInstances = 0;

            for (com.fasterxml.jackson.databind.JsonNode seriesId : seriesArr) {
                String seriesResp = webClient.get()
                        .uri(orthancUrl + "/series/" + seriesId.asText())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                com.fasterxml.jackson.databind.JsonNode series = mapper.readTree(seriesResp);
                com.fasterxml.jackson.databind.JsonNode instanceArr = series.path("Instances");
                if (instanceArr.isArray()) numInstances += instanceArr.size();
            }

            if (numInstances == 0) numInstances = 1;
            log.debug("Orthanc counts for {}: series={} instances={}", studyInstanceUid, numSeries, numInstances);
            return new int[]{numSeries, numInstances};

        } catch (Exception e) {
            log.warn("Could not query Orthanc counts for {} (non-fatal): {}", studyInstanceUid, e.getMessage());
            return new int[]{1, 1};
        }
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
