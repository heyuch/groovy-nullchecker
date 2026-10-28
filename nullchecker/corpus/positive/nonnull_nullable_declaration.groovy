package corpus.positive

import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** R12: a @NonNull local must not be initialised from a nullable value. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullNullableDeclaration {

    @Nullable
    static String nullable() {
        return null
    }

    int run() {
        @NonNull String value = nullable()
        return value.length()
    }
}
