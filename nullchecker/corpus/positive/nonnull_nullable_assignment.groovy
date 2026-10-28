package corpus.positive

import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** R12: reassigning a nullable value to a @NonNull local is an assignment violation. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullNullableAssignment {

    @Nullable
    static String nullable() {
        return null
    }

    int run() {
        @NonNull String value = 'ok'
        value = nullable()
        return value.length()
    }
}
