package corpus.negative

import corpus.support.Contract
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** A false->fail contract narrows the assertion condition after a normal return. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class AssertionContract {

    @Contract('false -> fail')
    static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message)
        }
    }

    @Nullable String outcome

    void run() {
        isTrue(outcome != null && outcome.length() > 0, 'must have a value')
        outcome.length()
    }
}
