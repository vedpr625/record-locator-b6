package lk.gov.moh.dhp.rl.pointer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PointerRepository extends JpaRepository<Pointer, String> {

    // I-1: findById(String) is already inherited from JpaRepository<Pointer,String>.
    // Declaring it again is harmless but misleading — removed.

    boolean existsByPhn(String phn);

    @Query("select case when count(p) > 0 then true else false end from Pointer p " +
            "where p.phn = :phn and p.consent.accessible = false")
    boolean existsInaccessibleByPhn(@Param("phn") String phn);

    Optional<Pointer> findTopByPhnOrderByUpdatedAtDesc(String phn);

    // Returns ALL pointers for a patient regardless of consent — used by ITI-66
    // (SubmissionSet listing) which applies its own consent filtering via ConsentGate.
    // The old accessible=true filter caused newly-polled pointers (which default to
    // Consent.unknown() / accessible=false on edge with no consent webhook) to be
    // permanently invisible on the List endpoint.
    @Query("select p from Pointer p where p.phn = :phn order by p.indexedAt desc")
    List<Pointer> findAllByPhn(@Param("phn") String phn);

    @Query("""
            select p from Pointer p
            where p.phn = :phn
              and p.consent.accessible = true
              and (:status is null or p.status = :status)
              and (:dateFrom is null or p.indexedAt >= :dateFrom)
              and (:dateTo is null or p.indexedAt < :dateTo)
              and (:docTypeLoinc is null or p.docTypeLoinc = :docTypeLoinc)
              and (:facilityHin is null or p.facilityHin = :facilityHin)
              and (:identifier is null or p.id = :identifier)
            order by p.indexedAt desc
            """)
    List<Pointer> search(
            @Param("phn") String phn,
            @Param("status") Pointer.Status status,
            @Param("dateFrom") java.time.Instant dateFrom,
            @Param("dateTo") java.time.Instant dateTo,
            @Param("docTypeLoinc") String docTypeLoinc,
            @Param("facilityHin") String facilityHin,
            @Param("identifier") String identifier);

    // Separate method for the province-scoped case rather than trying to
    // make provinceHins nullable in the query above - a null collection
    // parameter combined with IN isn't reliably portable across JPA
    // providers, so we just pick the right query in SearchService instead.
    @Query("""
            select p from Pointer p
            where p.phn = :phn
              and p.consent.accessible = true
              and (:status is null or p.status = :status)
              and (:dateFrom is null or p.indexedAt >= :dateFrom)
              and (:dateTo is null or p.indexedAt < :dateTo)
              and (:docTypeLoinc is null or p.docTypeLoinc = :docTypeLoinc)
              and (:identifier is null or p.id = :identifier)
              and p.facilityHin in :facilityHins
            order by p.indexedAt desc
            """)
    List<Pointer> searchWithinFacilities(
            @Param("phn") String phn,
            @Param("status") Pointer.Status status,
            @Param("dateFrom") java.time.Instant dateFrom,
            @Param("dateTo") java.time.Instant dateTo,
            @Param("docTypeLoinc") String docTypeLoinc,
            @Param("identifier") String identifier,
            @Param("facilityHins") List<String> facilityHins);

    // Used by SearchService for multi-PHN OpenSearch results — fetches
    // all accessible pointers for a set of PHNs in one query.
    // H-3: now includes docTypeLoinc, facilityHin, identifier so the
    // name-search path honours all filter parameters, not just date range.
    @Query("""
            select p from Pointer p
            where p.phn in :phns
              and p.consent.accessible = true
              and (:status is null or p.status = :status)
              and (:dateFrom is null or p.indexedAt >= :dateFrom)
              and (:dateTo is null or p.indexedAt < :dateTo)
              and (:docTypeLoinc is null or p.docTypeLoinc = :docTypeLoinc)
              and (:facilityHin is null or p.facilityHin = :facilityHin)
              and (:identifier is null or p.id = :identifier)
            order by p.indexedAt desc
            """)
    List<Pointer> searchByPhns(
            @Param("phns") List<String> phns,
            @Param("status") Pointer.Status status,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            @Param("docTypeLoinc") String docTypeLoinc,
            @Param("facilityHin") String facilityHin,
            @Param("identifier") String identifier);

    List<Pointer> findByUpdatedAtAfter(Instant since);

    List<Pointer> findByNationalSyncedFalse();

    /**
     * Bulk update, not an entity save - this bypasses Pointer's own
     * applyConsent()/updateContentUrl() methods entirely, which is
     * why nationalSynced is set explicitly here too. Forgetting this
     * would mean a patient's location-exclusion request updates the
     * local copy correctly but never tells national to re-sync,
     * leaving stale facility data there indefinitely.
     */
    // H-5: clearAutomatically = true evicts stale Pointer entities from the
    // first-level (L1) cache after the bulk UPDATE so that any subsequent read
    // in the same transaction sees the freshly updated rows rather than the
    // pre-update cached state.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Pointer p set
                p.consent.accessible = :accessible,
                p.consent.locationExcluded = :locationExcluded,
                p.consent.purposeOfUse = :purposeOfUse,
                p.consent.source = 'BUNDLE_2A',
                p.consent.asOf = :asOf,
                p.facilityHin = case when :locationExcluded = true then null else p.facilityHin end,
                p.facilityName = case when :locationExcluded = true then null else p.facilityName end,
                p.nationalSynced = false
            where p.phn = :phn
            """)
    int applyConsentByPhn(
            @Param("phn") String phn,
            @Param("accessible") boolean accessible,
            @Param("locationExcluded") boolean locationExcluded,
            @Param("purposeOfUse") String purposeOfUse,
            @Param("asOf") Instant asOf);
}
