package lk.gov.moh.dhp.rl.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "phn", length = 64)
    private String phn;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected AuditEvent() {
        // JPA
    }

    private AuditEvent(String eventType, String phn, String resourceId, String outcome) {
        this.id = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.phn = phn;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.recordedAt = Instant.now();
    }

    static AuditEvent of(String eventType, String phn, String resourceId, String outcome) {
        return new AuditEvent(eventType, phn, resourceId, outcome);
    }

    // L-1: package-private accessors so tests can assert on persisted events
    // without needing to reach into persistence storage directly.
    String getId()         { return id; }
    String getEventType()  { return eventType; }
    String getPhn()        { return phn; }
    String getResourceId() { return resourceId; }
    String getOutcome()    { return outcome; }
    Instant getRecordedAt(){ return recordedAt; }
}
