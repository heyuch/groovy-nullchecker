package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: a nullable map key is not a nullable entry.
 *
 * Iterating a Map yields Map.Entry, which is never null; the annotation on the first type
 * argument describes the key. Treating the entry as the element would report every entry
 * dereference, which is why maps are excluded from element tracking.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementMapIteration {
    static int keys(Map<@Nullable String, String> values) {
        int seen = 0
        for (Map.Entry entry : values) {
            if (entry.key != null) {
                seen += 1
            }
        }
        return seen
    }
}
