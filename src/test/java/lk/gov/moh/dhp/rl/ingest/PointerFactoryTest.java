package lk.gov.moh.dhp.rl.ingest;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.consent.PatientConsent;
import lk.gov.moh.dhp.rl.consent.PatientConsentRepository;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import lk.gov.moh.dhp.rl.search.OpenSearchServicePort;
import lk.gov.moh.dhp.rl.search.PatientDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PointerFactory — no Spring context.
 *
 * Updated for C-4 fix: PointerFactory now takes PatientConsentRepository
 * (not PointerRepository.findTopByPhnOrderByUpdatedAtDesc), and ensure()
 * returns EnsureResult instead of Pointer (H-8 fix).
 */
class PointerFactoryTest {

    PointerRepository repo;
    PatientConsentRepository consentRepo;
    OpenSearchServicePort openSearch;
    PointerFactory factory;

    @BeforeEach
    void setUp() {
        repo = mock(PointerRepository.class);
        consentRepo = mock(PatientConsentRepository.class);
        openSearch = mock(OpenSearchServicePort.class);
        factory = new PointerFactory(repo, consentRepo, openSearch, true); // defaultAccessible = true
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // By default, no consent record in patient_consent table
        when(consentRepo.findByPhn(anyString())).thenReturn(Optional.empty());
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_savesPointer() {
        Pointer result = factory.create(baseRequest());

        assertThat(result.phn()).isEqualTo("PHN001");
        verify(repo).save(any(Pointer.class));
    }

    @Test
    void create_firstPointer_usesDefaultConsent() {
        // No patient_consent row → falls back to defaultAccessible=true
        Pointer result = factory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isTrue();
    }

    @Test
    void create_inheritsConsentFromPatientConsentTable() {
        // C-4: consent is now read from patient_consent table, not from prior pointer rows
        PatientConsent pc = PatientConsent.of("PHN001", false, false, null); // opted out
        when(consentRepo.findByPhn("PHN001")).thenReturn(Optional.of(pc));

        Pointer result = factory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isFalse();
    }

    @Test
    void create_withPatientName_indexesOpenSearch() {
        factory.create(new NewPointerRequest(
                "study-001", "PHN001", "Perera Kasun", "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null, false));

        verify(openSearch).indexPatient(any(PatientDocument.class));
    }

    @Test
    void create_withNullPatientName_doesNotIndexOpenSearch() {
        factory.create(baseRequest()); // patientName = null in baseRequest

        verifyNoInteractions(openSearch);
    }

    @Test
    void create_defaultInaccessible_whenFlagFalse() {
        PointerFactory inaccessibleFactory = new PointerFactory(repo, consentRepo, openSearch, false);

        Pointer result = inaccessibleFactory.create(baseRequest());

        assertThat(result.consent().isAccessible()).isFalse();
    }

    // ── ensure ────────────────────────────────────────────────────────────────

    @Test
    void ensure_existingPointer_touchesAndReturnsUpdatedResult() {
        // H-8: ensure() now returns EnsureResult, not Pointer
        Pointer existing = buildSavedPointer("study-001", "PHN001");
        when(repo.findById("study-001")).thenReturn(Optional.of(existing));

        EnsureResult result = factory.ensure(baseRequest());

        assertThat(result.pointer().id()).isEqualTo("study-001");
        assertThat(result.created()).isFalse(); // already existed → updated
        verify(repo).save(existing);
    }

    @Test
    void ensure_newPointer_createsNewAndReturnCreatedResult() {
        when(repo.findById("study-001")).thenReturn(Optional.empty());

        EnsureResult result = factory.ensure(baseRequest());

        assertThat(result.created()).isTrue(); // brand new
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
