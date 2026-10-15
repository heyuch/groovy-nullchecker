package corpus.positive

import corpus.support.MonotonicNonNull
import groovy.transform.TypeChecked

/**
 * Rule R04: a @MonotonicNonNull field may be null until it first holds a value, but
 * must never be set back to null afterwards.
 *
 * The initialization and the invalidating assignment are deliberately in the same
 * method: the checker tracks monotonic state per visited method body, so a field set
 * in one method and nulled in another is a known gap (see PORT.md, deviations).
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class MonotonicNonNullReassign {
    @MonotonicNonNull
    String cached

    void refresh() {
        cached = 'value'
        cached = null
    }
}
