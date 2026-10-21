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

    int afterSafeNavCheck(@Nullable String s) {
        if (s?.length() == null) {
            return 0
        }
        return s.length()
    }

    enum Kind {
        SPECIAL,
        NORMAL
    }

    static class Details {
        Kind kind
    }

    static class Item {
        Details details
    }

    int afterKindCheck(@Nullable Item item) {
        if (item?.details?.kind != Kind.SPECIAL) {
            return 0
        }
        return item.details.kind.ordinal()
    }
}
