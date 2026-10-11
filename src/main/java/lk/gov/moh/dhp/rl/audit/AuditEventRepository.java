package lk.gov.moh.dhp.rl.audit;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
}
