package lk.gov.moh.dhp.rl.audit;

import org.springframework.data.jpa.repository.JpaRepository;

// L-1: public visibility so test classes outside this package can inject and
// query the repository directly (e.g. to assert that a search or retrieve
// event was actually persisted with the expected outcome).
public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
}
