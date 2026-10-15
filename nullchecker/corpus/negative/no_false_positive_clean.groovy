package corpus.negative

import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * The plain happy path: correctly annotated, correctly guarded code must be
 * completely silent. This is the fixture that catches "the checker never ran".
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NoFalsePositiveClean {
    @NonNull
    String nonNullReturn() {
        return 'value'
    }

    String nonNullReturnWithNullLiteral(@Nullable String fallback) {
        return fallback ?: 'value'
    }

    int nonNullParam(@NonNull String s) {
        return s.length()
    }

    int guarded(@Nullable String s) {
        return s == null ? 0 : s.length()
    }

    void nullAssignments() {
        String mutable = 'a'
        mutable = null
        Object anything = null
        anything = 'b'
    }
}
