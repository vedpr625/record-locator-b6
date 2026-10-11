package lk.gov.moh.dhp.rl.ingest;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import lk.gov.moh.dhp.rl.search.OpenSearchServicePort;
import lk.gov.moh.dhp.rl.search.PatientDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PointerFactory — no Spring context.
 */
class PointerFactoryTest {

    PointerRepository repo;
    OpenSearchServicePort openSearch;
    PointerFactory factory;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(PointerRepository.class);
        openSearch = Mockito.mock(OpenSearchServicePort.class);
        factory = new PointerFactory(repo, openSearch, true); // defaultAccessible = true
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_savesPointer() {
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        Pointer result = factory.create(baseRequest());

        assertThat(result.phn()).isEqualTo("PHN001");
        verify(repo).save(any(Pointer.class));
    }

    @Test
    void create_firstPointer_usesDefaultConsent() {
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        Pointer result = factory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isTrue(); // defaultAccessible=true
    }

    @Test
    void create_inheritsPriorConsent() {
        Pointer existing = Pointer.builder()
                .id("old-study")
                .phn("PHN001")
                .resourceType("ImagingStudy")
                .contentUrl("http://orthanc/old")
                .facilityHin("HIN001")
                .facilityName("Hosp")
                .consent(Consent.unknown())  // opted-out
                .nationalSynced(false)
                .build();
        when(repo.findTopByPhnOrderByUpdatedAtDesc("PHN001")).thenReturn(Optional.of(existing));

        Pointer result = factory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isFalse(); // inherited unknown = false
    }

    @Test
    void create_withPatientName_indexesOpenSearch() {
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        factory.create(new NewPointerRequest(
                "study-001", "PHN001", "Perera Kasun", "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null, false));

        verify(openSearch).indexPatient(any(PatientDocument.class));
    }

    @Test
    void create_withNullPatientName_doesNotIndexOpenSearch() {
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        factory.create(baseRequest()); // patientName = null in baseRequest

        verifyNoInteractions(openSearch);
    }

    @Test
    void create_defaultInaccessible_whenFlagFalse() {
        PointerFactory inaccessibleFactory = new PointerFactory(repo, openSearch, false);
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        Pointer result = inaccessibleFactory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isFalse();
    }

    // ── ensure ────────────────────────────────────────────────────────────────

    @Test
    void ensure_existingPointer_touchesAndReturnsExisting() {
        Pointer existing = buildSavedPointer("study-001", "PHN001");
        when(repo.findById("study-001")).thenReturn(Optional.of(existing));

        Pointer result = factory.ensure(baseRequest());

        assertThat(result.id()).isEqualTo("study-001");
        verify(repo).save(existing);
        verify(repo, never()).findTopByPhnOrderByUpdatedAtDesc(anyString());
    }

    @Test
    void ensure_newPointer_createsNew() {
        when(repo.findById("study-001")).thenReturn(Optional.empty());
        when(repo.findTopByPhnOrderByUpdatedAtDesc(anyString())).thenReturn(Optional.empty());

        factory.ensure(baseRequest());

        ArgumentCaptor<Pointer> captor = ArgumentCaptor.forClass(Pointer.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().phn()).isEqualTo("PHN001");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NewPointerRequest baseRequest() {
        return new NewPointerRequest(
                "study-001", "PHN001", null, "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null, false);
    }

    private Pointer buildSavedPointer(String id, String phn) {
        return Pointer.builder()
                .id(id)
                .phn(phn)
                .resourceType("ImagingStudy")
                .contentUrl("http://wado")
                .facilityHin("HIN001")
                .facilityName("Hosp")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
    }
}
