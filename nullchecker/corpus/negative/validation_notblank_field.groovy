package corpus.negative

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotBlank

/**
 * Guards: @NotBlank is a validation constraint, so it does not demand initialisation.
 *
 * Being non-null does not mean the constructor assigns it; a bean populated by a framework is
 * valid as long as the value is present when validation runs. Same rule as @NotNull.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotBlankField {
    @NotBlank
    String status

    @NotBlank
    String label
}
