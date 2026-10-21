package corpus.negative

import groovy.transform.TypeChecked

/** Guards: first/head throw on empty and any returns boolean, so none of them is nullable. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FirstHeadAny {

    int firstLength(List<String> values) {
        return values.first().length()
    }

    int headLength(List<String> values) {
        return values.head().length()
    }

    boolean anyEmpty(List<String> values) {
        return values.any { String value -> value.isEmpty() }
    }
}
