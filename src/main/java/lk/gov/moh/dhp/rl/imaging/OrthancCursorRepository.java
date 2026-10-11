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

    /** Returns the last persisted sequence number, or 0 if none exists. */
    public long load() {
        try {
            Long seq = jdbc.queryForObject(
                    "SELECT seq FROM orthanc_cursor WHERE id = ?",
                    Long.class, CURSOR_ID);
            return seq != null ? seq : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Upserts the cursor.  Safe to call frequently — cheap single-row upsert. */
    public void save(long seq) {
        jdbc.update("""
                INSERT INTO orthanc_cursor (id, seq, updated)
                VALUES (?, ?, now())
                ON CONFLICT (id) DO UPDATE
                    SET seq = EXCLUDED.seq,
                        updated = now()
                """, CURSOR_ID, seq);
    }
}
