package lk.gov.moh.dhp.rl.pointer;

import lk.gov.moh.dhp.rl.consent.Consent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PointerRepository against H2.
 * Uses @DataJpaTest which spins up Hibernate + Flyway on H2.
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class PointerRepositoryTest {

    @Autowired
    PointerRepository repo;

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_returnsEmpty_whenNotFound() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void save_thenFindById() {
        Pointer p = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true);
        repo.save(p);

        Optional<Pointer> found = repo.findById("doc-001");
        assertThat(found).isPresent();
        assertThat(found.get().phn()).isEqualTo("PHN001");
    }

    // ── existsByPhn ───────────────────────────────────────────────────────────

    @Test
    void existsByPhn_returnsFalse_whenNone() {
        assertThat(repo.existsByPhn("NONE")).isFalse();
    }

    @Test
    void existsByPhn_returnsTrue_whenPresent() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true));
        assertThat(repo.existsByPhn("PHN001")).isTrue();
    }

    // ── existsInaccessibleByPhn ───────────────────────────────────────────────

    @Test
    void existsInaccessibleByPhn_optedOut_returnsTrue() {
        Pointer optedOut = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, false);
        repo.save(optedOut);
        assertThat(repo.existsInaccessibleByPhn("PHN001")).isTrue();
    }

    @Test
    void existsInaccessibleByPhn_accessible_returnsFalse() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true));
        assertThat(repo.existsInaccessibleByPhn("PHN001")).isFalse();
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_byPhn_returnsMatchingAccessiblePointers() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true));
        repo.save(buildPointer("doc-002", "PHN002", Pointer.Status.CURRENT, true));

        List<Pointer> result = repo.search("PHN001", Pointer.Status.CURRENT,
                null, null, null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("doc-001");
    }

    @Test
    void search_inaccessiblePointer_notReturned() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, false));

        List<Pointer> result = repo.search("PHN001", Pointer.Status.CURRENT,
                null, null, null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void search_superseded_notReturnedForCurrentQuery() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.SUPERSEDED, true));

        List<Pointer> result = repo.search("PHN001", Pointer.Status.CURRENT,
                null, null, null, null, null);

        assertThat(result).isEmpty();
    }

    // ── applyConsentByPhn ─────────────────────────────────────────────────────

    @Test
    void applyConsentByPhn_updatesAllPointers() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true));
        repo.save(buildPointer("doc-002", "PHN001", Pointer.Status.CURRENT, true));

        int updated = repo.applyConsentByPhn("PHN001", false, false, null, Instant.now());

        assertThat(updated).isEqualTo(2);
        List<Pointer> all = repo.findAllByPhn("PHN001");
        // Check list is non-empty before allSatisfy (SonarQube S5961: test emptiness first)
        assertThat(all).isNotEmpty()
                .allSatisfy(p -> assertThat(p.consent().isAccessible()).isFalse());
    }

    @Test
    void applyConsentByPhn_returnsZero_whenNoPhnMatch() {
        int updated = repo.applyConsentByPhn("NOBODY", false, false, null, Instant.now());
        assertThat(updated).isZero();
    }

    // ── findAllByPhn ──────────────────────────────────────────────────────────

    @Test
    void findAllByPhn_returnsAllRegardlessOfConsent() {
        repo.save(buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT, true));
        repo.save(buildPointer("doc-002", "PHN001", Pointer.Status.CURRENT, false));

        List<Pointer> all = repo.findAllByPhn("PHN001");
        assertThat(all).hasSize(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pointer buildPointer(String id, String phn, Pointer.Status status, boolean accessible) {
        return Pointer.builder()
                .id(id)
                .phn(phn)
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/" + id)
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .status(status)
                .consent(accessible ? Consent.defaultAccessible() : Consent.unknown())
                .nationalSynced(false)
                .build();
    }
}
