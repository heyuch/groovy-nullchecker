package corpus.strict

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** F2: a for-each variable receives the collection's nullable element type. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowForNullableElement {

    int run(List<@Nullable String> names) {
        int total = 0
        for (String name : names) {
            total += name.length()
        }
        return total
    }
}
