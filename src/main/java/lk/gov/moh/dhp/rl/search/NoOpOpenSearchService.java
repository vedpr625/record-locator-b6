package lk.gov.moh.dhp.rl.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fallback when rl.opensearch.enabled is false (the default).
 * Name searches return empty, index calls are silent no-ops.
 * The app operates as a pure PostgreSQL service.
 */
@Service
@ConditionalOnProperty(name = "rl.opensearch.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOpenSearchService extends OpenSearchServicePort {

    private static final Logger log = LoggerFactory.getLogger(NoOpOpenSearchService.class);

    @Override
    public List<String> findPhnsByName(String name) {
        log.debug("OpenSearch disabled — name search for '{}' returning empty", name);
        return List.of();
    }

    @Override
    public void indexPatient(PatientDocument doc) {
        // intentional no-op
    }
}
