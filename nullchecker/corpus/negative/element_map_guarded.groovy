package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: a checked map half, and a map with nothing nullable in it.
 *
 * A nullable key or value must not turn into "everything read from this map is a finding":
 * checking the half that is read narrows it, and a Map<String, String> reports nothing.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementMapGuarded {
    static int guardedValue(Map<String, @Nullable String> values) {
        int total = 0
        for (Map.Entry<String, String> entry : values) {
            String value = entry.value
            if (value != null) {
                total += value.length()
            }
        }
        values.each { key, value ->
            if (value != null) {
                total += value.length()
            }
        }
        return total
    }

    static int nonNullMap(Map<String, String> values) {
        int total = 0
        for (Map.Entry<String, String> entry : values) {
            total += entry.value.length()
        }
        values.each { key, value -> total += key.length() + value.length() }
        return total
    }
}
