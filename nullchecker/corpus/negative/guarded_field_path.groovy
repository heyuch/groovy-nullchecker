package corpus.negative

import corpus.support.NullMarked
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** A property-path null check guards the same path on the right of &&. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
@NullMarked
class GuardedFieldPath {

    static class Holder {
        @Nullable String dslCode
    }

    boolean ok() {
        Holder data = new Holder()
        return data.dslCode != null && data.dslCode.length() > 50
    }
}
