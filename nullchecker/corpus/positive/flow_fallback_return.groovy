package corpus.positive

import corpus.support.NonNull
import groovy.transform.TypeChecked

/**
 * Rule R01: without a declaration, an always-throwing helper looks like an ordinary call.
 *
 * <p>The catch block completes normally as far as the checker can tell, so the fallback
 * {@code return null} is reachable and is reported. This is the control for
 * {@code negative/flow_no_return.groovy}: the declaration is what silences it, not the shape
 * of the code.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowFallbackReturn {

    static void notExpected(String message, Throwable cause) {
        throw new IllegalStateException(message, cause)
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
}
