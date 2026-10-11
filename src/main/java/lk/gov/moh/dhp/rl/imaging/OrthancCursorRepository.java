package lk.gov.moh.dhp.rl.imaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists the Orthanc /changes cursor to the DB so that a process restart
 * resumes from where it left off instead of replaying all studies from 0.
 *
 * Uses a single-row "orthanc_cursor" table (V5 migration).
 * Plain JdbcTemplate keeps this deliberately thin — no JPA overhead for
 * one integer that is written every 30 s.
 */
@Repository
public class OrthancCursorRepository {

    private static final String CURSOR_ID = "default";

    private final JdbcTemplate jdbc;

    public OrthancCursorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the last persisted sequence number, or 0 if none exists.
     *
     * <p>L-3: the catch is narrowed to {@link org.springframework.dao.EmptyResultDataAccessException}
     * so programming errors (wrong SQL, misconfigured datasource) bubble up as
     * normal exceptions rather than silently returning 0 and replaying the full
     * Orthanc change log from the beginning.
     * {@link org.springframework.dao.DataAccessException} is the Spring hierarchy
     * root and covers both the empty-result case and transient DB errors; other
     * {@code RuntimeException} subtypes (e.g. NullPointerException in test code)
     * are intentionally NOT caught here.
     */
    public long load() {
        try {
            Long seq = jdbc.queryForObject(
                    "SELECT seq FROM orthanc_cursor WHERE id = ?",
                    Long.class, CURSOR_ID);
            return seq != null ? seq : 0L;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // No cursor row yet — first run, start from 0.
            return 0L;
        }
    }

    /**
     * Upserts the cursor.  Safe to call frequently — cheap single-row upsert.
     *
     * <p>Uses try-UPDATE-then-INSERT (two statements) rather than the
     * PostgreSQL-specific {@code ON CONFLICT … DO UPDATE} or the H2-specific
     * {@code MERGE … KEY (id)} form.  Either UPDATE or INSERT will run in every
     * normal call; both are idempotent on concurrent calls because the row's
     * primary key prevents duplicate inserts.
     * This pattern is portable across PostgreSQL and H2.
     */
    public void save(long seq) {
        int updated = jdbc.update(
                "UPDATE orthanc_cursor SET seq = ?, updated = now() WHERE id = ?",
                seq, CURSOR_ID);
        if (updated == 0) {
            // Row does not exist yet — first ever save.  Ignore duplicate key on the
            // unlikely race where two threads reach this point simultaneously.
            try {
                jdbc.update(
                        "INSERT INTO orthanc_cursor (id, seq, updated) VALUES (?, ?, now())",
                        CURSOR_ID, seq);
            } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                // Another thread inserted first; the UPDATE on the next save will win.
            }
        }
    }
}
