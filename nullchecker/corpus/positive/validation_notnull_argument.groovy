package corpus.positive

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotNull

/**
 * Rule R07: Bean Validation's @NotNull is a non-null contract on the parameter.
 *
 * The constraint is enforced at validation time, so passing null violates it. Honouring it
 * is the reason it is not simply ignored: only the *initialisation* requirement is
 * suspended for validation constraints, not the contract itself.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotNullArgument {
    static void save(@NotNull String value) {
    }

    static void call() {
        save(null)
    }
}
