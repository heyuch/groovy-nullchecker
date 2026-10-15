package corpus.positive

import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R03: returning an @Nullable value from an @NonNull method. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullReturnNullableValue {
    @Nullable
    String maybe() {
        return null
    }

    @NonNull
    String mustNotReturnNullable() {
        return maybe()
    }
}
