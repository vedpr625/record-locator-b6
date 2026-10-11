package lk.gov.moh.dhp.rl.ingest;

import lk.gov.moh.dhp.rl.pointer.Pointer;

/**
 * Result of {@link PointerFactory#ensure(NewPointerRequest)}, distinguishing a
 * newly-created pointer from one that already existed and was touched.
 *
 * <p>H-8: avoids the separate {@code existsById} call in
 * {@link PointerEventController} — the create/update decision is made inside
 * {@code ensure()} where the {@code findById} result is already available,
 * reducing the round-trip count from 3 to 2.
 */
public record EnsureResult(Pointer pointer, boolean created) {

    /** Convenience: pointer was brand-new. */
    static EnsureResult created(Pointer p)  { return new EnsureResult(p, true);  }

    /** Convenience: pointer already existed and was touched. */
    static EnsureResult updated(Pointer p)  { return new EnsureResult(p, false); }
}
