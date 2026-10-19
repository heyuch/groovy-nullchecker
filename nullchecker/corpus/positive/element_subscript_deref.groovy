package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: reading an element of a collection whose element type is @Nullable.
 *
 * Subscript, get() and the DGM accessors all yield the element type, and the annotation on it
 * survives generics inference -- the dereference of any of them must be reported.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementSubscriptDeref {
    static int viaSubscript(List<@Nullable String> names) {
        return names[0].length()
    }

    static int viaGet(List<@Nullable String> names) {
        return names.get(0).length()
    }

    static int viaFirst(List<@Nullable String> names) {
        return names.first().length()
    }
}
