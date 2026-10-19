package corpus.positive

import groovy.transform.TypeChecked
import jakarta.validation.constraints.NotBlank

/**
 * Rule R07: @NotBlank rejects null before checking content, so it is a non-null contract.
 *
 * The same reasoning covers @NotEmpty; the other constraints (@Size, @Pattern, @Email) accept
 * null and must not narrow anything.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ValidationNotBlankArgument {
    static void save(@NotBlank String value) {
    }

    static void call() {
        save(null)
    }
}
