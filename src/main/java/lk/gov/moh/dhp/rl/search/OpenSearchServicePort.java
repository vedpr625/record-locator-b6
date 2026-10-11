package lk.gov.moh.dhp.rl.search;

import java.util.List;

/**
 * Port for the OpenSearch integration. Two implementations:
 *   - OpenSearchService   (active when rl.opensearch.enabled=true)
 *   - NoOpOpenSearchService (active by default / when disabled)
 *
 * SearchService depends on this interface, not on either concrete class,
 * so the application context always has exactly one bean of this type
 * regardless of the profile.
 */
public abstract class OpenSearchServicePort {

    /** Returns PHNs of patients whose name fuzzy-matches the given string. */
    public abstract List<String> findPhnsByName(String name);

    /** Upsert a patient document into the name index. Never throws. */
    public abstract void indexPatient(PatientDocument doc);
}
