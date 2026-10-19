package corpus.negative

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotNull

/**
 * Guards: a validation constraint does not require initialisation in source.
 *
 * A bean populated by an ORM or a deserializer is valid as long as the value is non-null
 * when validation runs. Requiring definite initialisation for every such field reported
 * every mapped field of every data object as uninitialised in strict mode.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotNullField {
    @NotNull
    String status

    @NotNull
    String label
}
