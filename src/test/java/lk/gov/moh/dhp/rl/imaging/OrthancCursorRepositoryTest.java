package lk.gov.moh.dhp.rl.imaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OrthancCursorRepository against real H2.
 * Uses @JdbcTest which configures an in-memory H2, runs Flyway migrations,
 * and provides a JdbcTemplate.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(OrthancCursorRepository.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class OrthancCursorRepositoryTest {

    @Autowired
    OrthancCursorRepository cursorRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void load_returnsZero_whenTableEmpty() {
        assertThat(cursorRepository.load()).isZero();
    }

    @Test
    void save_thenLoad_returnsSavedValue() {
        cursorRepository.save(42L);
        assertThat(cursorRepository.load()).isEqualTo(42L);
    }

    @Test
    void save_twice_upserts() {
        cursorRepository.save(10L);
        cursorRepository.save(99L);
        assertThat(cursorRepository.load()).isEqualTo(99L);
    }

    @Test
    void save_zero_isValid() {
        cursorRepository.save(0L);
        assertThat(cursorRepository.load()).isZero();
    }

    @Test
    void save_largeSeq() {
        long big = Long.MAX_VALUE - 1;
        cursorRepository.save(big);
        assertThat(cursorRepository.load()).isEqualTo(big);
    }
}
