package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: safe navigation `?.` and a fully-safe chain. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsSafeNavigation {
    Integer safeNav(@Nullable String s) {
        return s?.length()
    }

    Integer safeChain(@Nullable String s) {
        return s?.trim()?.length()
    }
}
