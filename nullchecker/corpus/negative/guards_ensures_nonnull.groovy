package corpus.negative

import corpus.support.EnsuresNonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: an unconditional postcondition, the annotation form of requireNonNull.
 *
 * After the call returns normally the argument is non-null, so the dereference below must
 * stay silent -- and would not without the annotation, which the control fixture pairs it with.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsEnsuresNonNull {

    @EnsuresNonNull(expression = '#1')
    static <T> T require(T value) {
        if (value == null) {
            throw new IllegalArgumentException('null')
        }
        return value
    }

    static int use(@Nullable String value) {
        GuardsEnsuresNonNull.require(value)
        return value.length()
    }
}
