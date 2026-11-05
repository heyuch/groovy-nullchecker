package corpus.negative

import corpus.support.NullMarked
import corpus.support.Nullable
import groovy.transform.TypeChecked

/** assert must apply access-path and lookup facts, not only variables. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
@NullMarked
class AssertFieldPath {

    @Nullable String value

    int ok() {
        assert this.value != null
        return this.value.length()
    }

    boolean lookup(Map<String, String> values) {
        String key = 'k'
        assert values.get(key) != null
        return values.get(key).isBlank()
    }
}
