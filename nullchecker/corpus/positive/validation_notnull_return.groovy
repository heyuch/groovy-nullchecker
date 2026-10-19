package corpus.positive

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotNull

/** Rule R01: @NotNull on the method is a non-null contract on the return value. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotNullReturn {
    @NotNull
    static String render() {
        return null
    }
}
