package demo.broken

import demo.NonNull
import demo.Nullable
import groovy.transform.TypeChecked

/**
 * Deliberately broken. Kept OUT of src/main/groovy so it never breaks compileGroovy;
 * it is an explicit target of the report task instead, which is how you size up an
 * existing codebase before deciding to make the check a gate.
 *
 * The @TypeChecked annotation is required here because samples/ is not part of the
 * project's compile, so the global configuration script is not applied to it.
 */
@TypeChecked(extensions = 'io.github.heyuch.groovy4.nullchecker.NullChecker')
class BrokenService {

    /** Reported: an @NonNull method must not return null. */
    @NonNull
    String mustNotReturnNull() {
        return null
    }

    /** Reported: an @Nullable parameter is dereferenced without a guard. */
    int derefWithoutGuard(@Nullable String input) {
        return input.length()
    }

    /** Reported: null passed to an @NonNull parameter. */
    int callWithNull() {
        return derefStrictly(null)
    }

    int derefStrictly(@NonNull String input) {
        return input.length()
    }

    /** Reported only in strict mode: nothing is annotated here. */
    int flowSensitive() {
        String s = null
        return s.length()
    }
}
