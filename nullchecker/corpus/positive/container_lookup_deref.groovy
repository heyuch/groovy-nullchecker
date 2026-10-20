package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: a container lookup that can miss is nullable, whatever the element type says.
 *
 * <p>{@code Map<String, String>} promises its values are non-null, but {@code get} is a partial
 * function: the key may be absent. The collection methods that return no result ({@code find})
 * or cannot answer for an empty collection ({@code max}) have the same shape. A nullable value
 * type makes it nullable too, and {@code containsKey} cannot narrow that case because a present
 * key may hold an explicit null.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ContainerLookupDeref {
    static int ofGet(Map<String, String> values) {
        return values.get('k').length()
    }

    static int ofSubscript(Map<String, String> values) {
        return values['k'].length()
    }

    static int ofFind(List<String> values) {
        return values.find { it == 'a' }.length()
    }

    static int ofMax(List<Integer> values) {
        return values.max().intValue()
    }

    static int ofNullableValues(Map<String, @Nullable String> values) {
        if (values.containsKey('k')) {
            return values.get('k').length()
        }
        return 0
    }
}
