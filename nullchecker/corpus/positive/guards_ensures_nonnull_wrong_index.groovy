package corpus.positive

import corpus.support.EnsuresNonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: a contract naming another argument must not narrow this one.
 *
 * The postcondition covers argument #2 and argument #9 (out of range); neither may narrow the
 * first argument, so the dereference must still be reported. Guessing which argument was meant
 * would silence a value that can be null.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsEnsuresNonNullWrongIndex {

    @EnsuresNonNull(expression = '#2')
    static void requireSecond(Object first, Object second) {
    }

    @EnsuresNonNull(expression = '#9')
    static void requireNothing(Object value) {
    }

    static int wrongArgument(@Nullable String first, String second) {
        GuardsEnsuresNonNullWrongIndex.requireSecond(first, second)
        return first.length()
    }

    static int outOfRange(@Nullable String value) {
        GuardsEnsuresNonNullWrongIndex.requireNothing(value)
        return value.length()
    }
}
