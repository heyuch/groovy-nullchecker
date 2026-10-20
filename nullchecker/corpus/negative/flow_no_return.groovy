package corpus.negative

import corpus.support.Contract
import corpus.support.NoReturn
import corpus.support.NonNull
import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: a call that never returns makes what follows it unreachable.
 *
 * <p>A helper that always throws is invisible in its signature. Once it is declared -- by
 * {@code @Contract("_, _ -> fail")}, by a {@code @NoReturn}-style annotation, or by the
 * project's {@code neverReturns} list -- a fallback {@code return null} after it is code that
 * cannot run, and an {@code if (x == null) giveUp()} narrows like any other early exit.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowNoReturn {

    @Contract('_, _ -> fail')
    static void notExpected(String message, Throwable cause) {
        throw new IllegalStateException(message, cause)
    }

    @NoReturn
    static void giveUp() {
        throw new IllegalStateException('give up')
    }

    @NonNull
    static Object fallbackReturn() {
        try {
            return new Object()
        } catch (Exception e) {
            notExpected('unexpected', e)
        }
        return null
    }

    static int guard(@Nullable String value) {
        if (value == null) {
            giveUp()
        }
        return value.length()
    }
}
