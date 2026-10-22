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
    private final RecordLocatorService recordLocatorService;
    private final Random random = new Random();

    // accessionNumber → studyInstanceUID (in-memory, demo only)
    private final Map<String, String> completedStudies = new ConcurrentHashMap<>();

    public StudyService(WebClient.Builder webClientBuilder, MppsService mppsService,
                        HhmisService hhmisService, RecordLocatorService recordLocatorService) {
        this.webClient = webClientBuilder.build();
        this.mppsService = mppsService;
        this.hhmisService = hhmisService;
        this.recordLocatorService = recordLocatorService;
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
                               String patientName, String modality,
                               String orderStudyInstanceUid) throws Exception {
        // 1. Pick sample JPEG by modality
        File sampleFile = pickSampleFile(modality);
        byte[] jpegBytes = Files.readAllBytes(sampleFile.toPath());

        // 2. UIDs. The StudyInstanceUID must be the one from the order (MWL item) so that
        //    HHMIS, MPPS and the archive all refer to the same study; only generate one
        //    as a last resort.
        String studyUid;
        if (orderStudyInstanceUid != null && !orderStudyInstanceUid.isBlank()) {
            studyUid = orderStudyInstanceUid.trim();
        } else {
            studyUid = generateUid();
            log.warn("No StudyInstanceUID on the worklist item for acc={}; generated {} " +
                     "(HHMIS may not recognise it)", accessionNumber, studyUid);
        }
        String seriesUid  = generateUid();
        String instanceUid = generateUid();

        // 3. Convert JPEG → DICOM in memory
        byte[] dicomBytes = convertToDicom(jpegBytes, studyUid, seriesUid, instanceUid,
                accessionNumber, phn, patientName, modality, sampleFile.getName());

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

        // 9. Register a pointer at the national Record Locator (non-fatal)
        recordLocatorService.registerPointer(studyUid, phn, patientName, modality);

        return studyUid;
    }

    private File pickSampleFile(String modality) throws Exception {
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
        log.info("using sample image: {}", chosen.getName());
        return chosen;
    }

    private byte[] convertToDicom(byte[] jpegBytes,
                                   String studyUid, String seriesUid, String instanceUid,
                                   String accessionNumber, String phn,
                                   String patientName, String modality,
                                   String imageName) throws Exception {
        // Use dcm4che Attributes to build DICOM dataset
        org.dcm4che3.data.Attributes attrs = new org.dcm4che3.data.Attributes();

        attrs.setString(org.dcm4che3.data.Tag.SpecificCharacterSet, org.dcm4che3.data.VR.CS, "ISO_IR 192");

        // Patient module (birth date / sex are type-2: present but empty, we do not know them here)
        attrs.setNull(org.dcm4che3.data.Tag.PatientBirthDate, org.dcm4che3.data.VR.DA);
        attrs.setNull(org.dcm4che3.data.Tag.PatientSex,       org.dcm4che3.data.VR.CS);
        attrs.setString(org.dcm4che3.data.Tag.PatientID,   org.dcm4che3.data.VR.LO, phn);
        attrs.setString(org.dcm4che3.data.Tag.PatientName, org.dcm4che3.data.VR.PN, patientName);

        // Study module
        attrs.setString(org.dcm4che3.data.Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI, studyUid);
        attrs.setString(org.dcm4che3.data.Tag.AccessionNumber,  org.dcm4che3.data.VR.SH, accessionNumber);
        attrs.setString(org.dcm4che3.data.Tag.StudyDate, org.dcm4che3.data.VR.DA, todayDicom());
        attrs.setString(org.dcm4che3.data.Tag.StudyTime, org.dcm4che3.data.VR.TM, nowTimeDicom());
        attrs.setString(org.dcm4che3.data.Tag.InstitutionName, org.dcm4che3.data.VR.LO, hospitalName);
        attrs.setString(org.dcm4che3.data.Tag.StudyID, org.dcm4che3.data.VR.SH, "1");
        attrs.setNull(org.dcm4che3.data.Tag.ReferringPhysicianName, org.dcm4che3.data.VR.PN);

        // View / body part taken from the sample file name: *-ap.jpg, *-lat.jpg (default: AP)
        String lower = imageName == null ? "" : imageName.toLowerCase();
        boolean lateral = lower.contains("lat");
        String viewDesc = lateral ? "Lateral" : "AP";
        attrs.setString(org.dcm4che3.data.Tag.StudyDescription,  org.dcm4che3.data.VR.LO, modality + " " + viewDesc);
        attrs.setString(org.dcm4che3.data.Tag.SeriesDescription, org.dcm4che3.data.VR.LO, modality + " " + viewDesc);
        attrs.setString(org.dcm4che3.data.Tag.BodyPartExamined,  org.dcm4che3.data.VR.CS, "WHOLEBODY");
        if (!"US".equals(modality) && !"MR".equals(modality)) {
            attrs.setString(org.dcm4che3.data.Tag.ViewPosition, org.dcm4che3.data.VR.CS, lateral ? "LL" : "AP");
        }
        attrs.setString(org.dcm4che3.data.Tag.PatientOrientation, org.dcm4che3.data.VR.CS, lateral ? "A\\F" : "L\\F");
        attrs.setString(org.dcm4che3.data.Tag.Manufacturer, org.dcm4che3.data.VR.LO, "RIS Demo Modality");
        attrs.setString(org.dcm4che3.data.Tag.StationName,  org.dcm4che3.data.VR.SH, "HOSPITAL-RIS");
        attrs.setString(org.dcm4che3.data.Tag.ImageType, org.dcm4che3.data.VR.CS, "ORIGINAL", "PRIMARY");
        attrs.setString(org.dcm4che3.data.Tag.ContentDate, org.dcm4che3.data.VR.DA, todayDicom());
        attrs.setString(org.dcm4che3.data.Tag.ContentTime, org.dcm4che3.data.VR.TM, nowTimeDicom());
        attrs.setString(org.dcm4che3.data.Tag.SeriesDate,  org.dcm4che3.data.VR.DA, todayDicom());
        attrs.setString(org.dcm4che3.data.Tag.SeriesTime,  org.dcm4che3.data.VR.TM, nowTimeDicom());

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

        // DICOM "JPEG Baseline" must be baseline (sequential) JPEG; re-encode if the
        // sample image is progressive, otherwise Orthanc/viewers may fail to decode it.
        jpegBytes = ensureBaselineJpeg(jpegBytes);

        // Read JPEG dimensions and channel count for image attributes
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(jpegBytes));
        if (img == null) {
            throw new Exception("Sample image could not be decoded as a JPEG");
        }
        boolean gray = img.getRaster().getNumBands() == 1;
        attrs.setInt(org.dcm4che3.data.Tag.Rows,             org.dcm4che3.data.VR.US, img.getHeight());
        attrs.setInt(org.dcm4che3.data.Tag.Columns,          org.dcm4che3.data.VR.US, img.getWidth());
        attrs.setInt(org.dcm4che3.data.Tag.BitsAllocated,    org.dcm4che3.data.VR.US, 8);
        attrs.setInt(org.dcm4che3.data.Tag.BitsStored,       org.dcm4che3.data.VR.US, 8);
        attrs.setInt(org.dcm4che3.data.Tag.HighBit,          org.dcm4che3.data.VR.US, 7);
        attrs.setInt(org.dcm4che3.data.Tag.SamplesPerPixel,  org.dcm4che3.data.VR.US, gray ? 1 : 3);
        attrs.setInt(org.dcm4che3.data.Tag.PixelRepresentation, org.dcm4che3.data.VR.US, 0);
        attrs.setString(org.dcm4che3.data.Tag.PhotometricInterpretation, org.dcm4che3.data.VR.CS,
                gray ? "MONOCHROME2" : "YBR_FULL_422");
        if (!gray) {
            attrs.setInt(org.dcm4che3.data.Tag.PlanarConfiguration, org.dcm4che3.data.VR.US, 0);
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

    /** True if the JPEG uses a progressive frame header (SOF2). */
    private static boolean isProgressiveJpeg(byte[] b) {
        int i = 2; // skip SOI
        while (i + 3 < b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xC2) return true;                       // progressive DCT
            if (marker == 0xC0 || marker == 0xC1) return false;    // baseline / extended sequential
            if (marker == 0xDA) return false;                      // start of scan, no SOF found
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 2 + len;
        }
        return false;
    }

    /** Re-encode progressive JPEGs as baseline so they are valid for JPEG Baseline (1.2.840.10008.1.2.4.50). */
    private static byte[] ensureBaselineJpeg(byte[] jpegBytes) throws Exception {
        if (!isProgressiveJpeg(jpegBytes)) return jpegBytes;

        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(jpegBytes));
        if (src == null) throw new Exception("Sample image could not be decoded as a JPEG");

        // JPEG writer needs plain gray or RGB (no alpha)
        java.awt.image.BufferedImage out = src;
        if (src.getType() != java.awt.image.BufferedImage.TYPE_BYTE_GRAY
                && src.getType() != java.awt.image.BufferedImage.TYPE_3BYTE_BGR) {
            out = new java.awt.image.BufferedImage(src.getWidth(), src.getHeight(),
                    src.getRaster().getNumBands() == 1
                            ? java.awt.image.BufferedImage.TYPE_BYTE_GRAY
                            : java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
            java.awt.Graphics2D g = out.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }

        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DISABLED);
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.95f);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(out, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
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
            case "CR" -> org.dcm4che3.data.UID.ComputedRadiographyImageStorage;
            default   -> org.dcm4che3.data.UID.DigitalXRayImageStorageForPresentation; // DX and others
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
