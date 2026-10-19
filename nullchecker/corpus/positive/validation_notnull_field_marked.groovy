package corpus.positive

import corpus.support.NullMarked
import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotNull

/**
 * Rule R09: inside a @NullMarked class the initialisation requirement stands.
 *
 * The marking is a language-level contract, so it outranks the looser guarantee a runtime
 * validation constraint gives; the constraint alone would have suspended the check.
 */
@NullMarked
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotNullFieldMarked {
    @NotNull
    String status
}
