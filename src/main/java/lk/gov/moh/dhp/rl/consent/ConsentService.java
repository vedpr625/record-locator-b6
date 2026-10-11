package lk.gov.moh.dhp.rl.consent;

import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.springframework.stereotype.Service;

/**
 * Everything in this module ultimately answers one question: is this
 * patient's data currently accessible. We never call Bundle 2a on the
 * query path - RL-NFR2's 2 second budget doesn't leave room for a
 * network hop on every search. Instead the webhook keeps the local
 * consent_flag column current and this class just reads it.
 */
@Service
public class ConsentService {

    private final PointerRepository pointerRepository;

    public ConsentService(PointerRepository pointerRepository) {
        this.pointerRepository = pointerRepository;
    }

    // We check for an INaccessible pointer directly rather than inferring
    // opt-out from an empty result set - a patient with no CURRENT documents
    // (all superseded, say) would look identical to an opted-out patient
    // if we went the other way round.
    public boolean isOptedOut(String phn) {
        return pointerRepository.existsInaccessibleByPhn(phn);
    }
}
