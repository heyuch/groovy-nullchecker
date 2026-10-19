package corpus.negative

import corpus.support.EnsuresNonNullIf
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: a conditional postcondition declared on the helper itself.
 *
 * The contract travels with the method, so a project's own isNullOrEmpty needs neither a
 * configuration entry nor a name on a fixed list. Both polarities are covered, and so is the
 * negated form, which must swap the facts rather than lose them.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsEnsuresNonNullIf {

    @EnsuresNonNullIf(expression = '#1', result = false)
    static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty()
    }

    @EnsuresNonNullIf(expression = '#1', result = true)
    static boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty()
    }

    static int onFalse(@Nullable List<String> values) {
        if (GuardsEnsuresNonNullIf.isNullOrEmpty(values)) {
            return 0
        }
        return values.size()
    }

    static int onTrue(@Nullable List<String> values) {
        if (GuardsEnsuresNonNullIf.isNotEmpty(values)) {
            return values.size()
        }
        return 0
    }

    static int negated(@Nullable List<String> values) {
        if (!GuardsEnsuresNonNullIf.isNullOrEmpty(values)) {
            return values.size()
        }
        return 0
    }
}
