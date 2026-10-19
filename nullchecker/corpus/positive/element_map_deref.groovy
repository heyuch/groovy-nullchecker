package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: a nullable map key or value, however it is reached.
 *
 * The entry itself is never null, but either half may be. Reading through the map, iterating
 * its entries, its values or its key set, and the pairwise closure of each() all hand out the
 * annotated half.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementMapDeref {
    static int valueOfEntry(Map<String, @Nullable String> values) {
        int total = 0
        for (Map.Entry<String, String> entry : values) {
            total += entry.value.length()
        }
        return total
    }

    static int valueOfGet(Map<String, @Nullable String> values) {
        return values.get('k').length()
    }

    static int valueOfSubscript(Map<String, @Nullable String> values) {
        return values['k'].length()
    }

    static int valueOfValues(Map<String, @Nullable String> values) {
        int total = 0
        for (String value : values.values()) {
            total += value.length()
        }
        return total
    }

    static int valueOfEach(Map<String, @Nullable String> values) {
        int total = 0
        values.each { key, value -> total += value.length() }
        return total
    }

    static int valueOfCollectedEntry(Map<String, @Nullable String> values) {
        int total = 0
        values.collect { it.value.length() }.each { total += it }
        return total
    }

    static int keyOfEntry(Map<@Nullable String, String> values) {
        int total = 0
        for (Map.Entry<String, String> entry : values) {
            total += entry.key.length()
        }
        return total
    }

    static int keyOfKeySet(Map<@Nullable String, String> values) {
        int total = 0
        for (String key : values.keySet()) {
            total += key.length()
        }
        return total
    }

    static int keyOfEach(Map<@Nullable String, String> values) {
        int total = 0
        values.each { key, value -> total += key.length() }
        return total
    }
}
