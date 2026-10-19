package corpus.positive

import corpus.support.EnsuresNonNullIf
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: a reversed contract cannot hide a real finding.
 *
 * isNullOrEmpty is declared as guaranteeing non-null when it returns true, which is backwards.
 * The fact then lands in the wrong branch, the code after the guard is not narrowed, and the
 * dereference must still be reported. Without this the annotation would be a way to silence
 * code by mislabelling it.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsEnsuresNonNullIfReversed {

    @EnsuresNonNullIf(expression = '#1', result = true)
    static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty()
    }

    static int bad(@Nullable List<String> values) {
        if (GuardsEnsuresNonNullIfReversed.isNullOrEmpty(values)) {
            return 0
        }
        return values.size()
    }
}
