package lk.gov.moh.dhp.rl.imaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.ingest.NewPointerRequest;
import lk.gov.moh.dhp.rl.ingest.PointerFactory;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrthancPoller — no Spring context.
 * RestTemplate and ApplicationContext are mocked.
 */
class OrthancPollerTest {

    RestTemplate restTemplate;
    ObjectMapper objectMapper;
    PointerFactory pointerFactory;
    PointerRepository pointerRepository;
    OrthancCursorRepository cursorRepository;
    ApplicationContext applicationContext;
    OrthancPoller poller;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = Mockito.mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        pointerFactory = Mockito.mock(PointerFactory.class);
        pointerRepository = Mockito.mock(PointerRepository.class);
        cursorRepository = Mockito.mock(OrthancCursorRepository.class);
        applicationContext = Mockito.mock(ApplicationContext.class);

        poller = new OrthancPoller(
                restTemplate, objectMapper, pointerFactory, pointerRepository,
                cursorRepository, applicationContext);

        ReflectionTestUtils.setField(poller, "orthancBaseUrl", "http://orthanc:8042");
        ReflectionTestUtils.setField(poller, "facilityHin", "HIN001");
        ReflectionTestUtils.setField(poller, "facilityName", "Test Hospital");

        // Self-reference via ApplicationContext for @Transactional proxy
        when(applicationContext.getBean(OrthancPoller.class)).thenReturn(poller);
    }

    // ── loadCursor ────────────────────────────────────────────────────────────

    @Test
    void loadCursor_setsAtomicLongFromDb() {
        when(cursorRepository.load()).thenReturn(42L);
        poller.loadCursor();

        // cursor should have been set to 42; next poll uses it
        assertThat(ReflectionTestUtils.getField(poller, "since"))
                .hasToString("42");
    }

    // ── poll ──────────────────────────────────────────────────────────────────

    @Test
    void poll_singlePage_done_processesStableStudy() throws Exception {
        String page = """
            {
              "Changes": [
                {"ChangeType":"StableStudy","ID":"orthanc-abc"}
              ],
              "Last": 5,
              "Done": true
            }
            """;
        when(restTemplate.getForObject(contains("/changes"), eq(String.class))).thenReturn(page);

        // processStudy fetches study detail
        String study = studyJson("1.2.3.4", "PHN001", "Perera", "CT");
        when(restTemplate.getForEntity(contains("/studies/orthanc-abc"), eq(String.class)))
                .thenReturn(ResponseEntity.ok(study));
        when(pointerRepository.findById("1.2.3.4")).thenReturn(Optional.empty());

        poller.poll();

        verify(pointerFactory).create(any(NewPointerRequest.class));
        verify(cursorRepository).save(5L);
    }

    @Test
    void poll_orthancUnreachable_returnsGracefully() {
        when(restTemplate.getForObject(contains("/changes"), eq(String.class)))
                .thenThrow(new RuntimeException("connection refused"));

        poller.poll(); // must not throw

        verifyNoInteractions(pointerFactory, pointerRepository);
    }

    @Test
    void poll_nonStableStudyChange_ignored() throws Exception {
        String page = """
            {
              "Changes": [
                {"ChangeType":"NewInstance","ID":"some-instance"}
              ],
              "Last": 3,
              "Done": true
            }
            """;
        when(restTemplate.getForObject(contains("/changes"), eq(String.class))).thenReturn(page);

        poller.poll();

        verifyNoInteractions(pointerFactory);
    }

    // ── processStudy ─────────────────────────────────────────────────────────

    @Test
    void processStudy_missingStudyInstanceUID_skips() throws Exception {
        String study = """
            {"MainDicomTags":{"StudyInstanceUID":""},"PatientMainDicomTags":{"PatientID":"PHN001"}}
            """;
        when(restTemplate.getForEntity(contains("/studies/"), eq(String.class)))
                .thenReturn(ResponseEntity.ok(study));

        poller.processStudy("orthanc-bad");

        verifyNoInteractions(pointerFactory);
    }

    @Test
    void processStudy_existingPointer_updatesUrlIfChanged() throws Exception {
        Pointer existing = Pointer.builder()
                .id("1.2.3.4")
                .phn("PHN001")
                .resourceType("ImagingStudy")
                .contentUrl("http://OLD-url")
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
        when(pointerRepository.findById("1.2.3.4")).thenReturn(Optional.of(existing));
        when(restTemplate.getForEntity(contains("/studies/orthanc-abc"), eq(String.class)))
                .thenReturn(ResponseEntity.ok(studyJson("1.2.3.4", "PHN001", "Perera", "CT")));

        poller.processStudy("orthanc-abc");

        verify(pointerRepository).save(existing);
        // URL was changed from OLD-url to the new WADO URL
        assertThat(existing.contentUrl()).contains("1.2.3.4");
    }

    @Test
    void processStudy_existingPointerSameUrl_noSave() throws Exception {
        String wadoUrl = "http://orthanc:8042/dicom-web/studies/1.2.3.4";
        Pointer existing = Pointer.builder()
                .id("1.2.3.4")
                .phn("PHN001")
                .resourceType("ImagingStudy")
                .contentUrl(wadoUrl) // already up-to-date
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
        when(pointerRepository.findById("1.2.3.4")).thenReturn(Optional.of(existing));
        when(restTemplate.getForEntity(contains("/studies/orthanc-abc"), eq(String.class)))
                .thenReturn(ResponseEntity.ok(studyJson("1.2.3.4", "PHN001", "Perera", "CT")));

        poller.processStudy("orthanc-abc");

        verify(pointerRepository, never()).save(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String studyJson(String uid, String phn, String name, String modality) {
        return """
            {
              "MainDicomTags": {
                "StudyInstanceUID": "%s",
                "ModalitiesInStudy": "%s"
              },
              "PatientMainDicomTags": {
                "PatientID": "%s",
                "PatientName": "%s"
              }
            }
            """.formatted(uid, modality, phn, name);
    }
}
