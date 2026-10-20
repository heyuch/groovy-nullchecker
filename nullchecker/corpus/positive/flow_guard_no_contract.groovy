package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: an undeclared always-throwing helper does not narrow.
 *
 * <p>{@code giveUp()} throws, but nothing in its signature says so, so the null check around
 * it is not treated as an early exit and the dereference is reported. Declaring the helper
 * (see {@code negative/flow_no_return.groovy}) is what removes the finding.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowGuardNoContract {

    static void giveUp() {
        throw new IllegalStateException('give up')
    }

    static int guard(@Nullable String value) {
        if (value == null) {
            giveUp()
        }
        return value.length()
    }
}
