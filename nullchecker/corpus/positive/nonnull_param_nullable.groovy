package corpus.positive

import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R13: passing an @Nullable value to an @NonNull parameter. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullParamNullable {
    void take(@NonNull String s) {
        s.length()
    }

    void caller(@Nullable String s) {
        take(s)
    }
}
