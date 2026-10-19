package corpus.positive

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotEmpty

/** Rule R01: @NotEmpty on a method means the return value is non-null. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotEmptyReturn {
    @NotEmpty
    static List<String> render() {
        return null
    }
}
