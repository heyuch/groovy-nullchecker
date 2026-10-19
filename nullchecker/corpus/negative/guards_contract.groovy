package corpus.negative

import corpus.support.Contract
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: JetBrains' @Contract, in the two shapes that say something about an argument.
 *
 * "null -> fail" means the call throws on a null argument, so reaching the next statement
 * proves it was not null. "null -> true|false" means the result reveals whether the argument
 * was null, which narrows the branch that result rules out.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsContract {

    @Contract('null -> fail')
    static <T> T require(T value) {
        if (value == null) {
            throw new IllegalArgumentException('null')
        }
        return value
    }

    @Contract('null -> false')
    static boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty()
    }

    @Contract('null -> true')
    static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty()
    }

    static int failingForm(@Nullable String value) {
        GuardsContract.require(value)
        return value.length()
    }

    static int falseForm(@Nullable List<String> values) {
        if (GuardsContract.isNotEmpty(values)) {
            return values.size()
        }
        return 0
    }

    static int trueForm(@Nullable List<String> values) {
        if (GuardsContract.isNullOrEmpty(values)) {
            return 0
        }
        return values.size()
    }
}
