package corpus.negative

import groovy.transform.TypeChecked

/** A null check on a map lookup guards the same lookup on the right of &&. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class RepeatedLookupGuard {

    boolean ok(Map<String, String> map) {
        String key = 'k'
        return map.get(key) != null && map.get(key).isBlank()
    }
}
