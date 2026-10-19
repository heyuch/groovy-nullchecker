package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: a spread property yields a List, so its result is never null.
 *
 * Groovy marks `items*.code` safe because it tolerates a null receiver (the result is an
 * empty list), not because the result can be null. Reading that flag as safe navigation
 * made the result nullable and reported the following call, which is what this pins.
 * Both shapes are covered: dereferenced directly, and passed through a local variable.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class SpreadPropertyResult {
    static class Item {
        @Nullable String code
    }

    static List<String> direct(List<Item> items) {
        return items*.code.findAll { String value -> value != null }
    }

    static List<String> viaLocal(List<Item> items) {
        List<String> codes = items*.code
        return codes.findAll { String value -> value != null }
    }

    static int sizes(List<Item> items) {
        return items*.code.size()
    }
}
