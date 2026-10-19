package corpus.positive

import corpus.support.Contract
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: a contract about the second argument must not narrow the first.
 *
 * "_, null -> fail" proves only the second argument non-null after the call. Reading it as a
 * guarantee about either argument, or about the wrong position, would silence a value that can
 * still be null.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsContractWrongArgument {

    @Contract('_, null -> fail')
    static void requireSecond(Object first, Object second) {
        if (second == null) {
            throw new IllegalArgumentException('second')
        }
    }

    static int firstIsStillNullable(@Nullable String first, String second) {
        GuardsContractWrongArgument.requireSecond(first, second)
        return first.length()
    }
}
