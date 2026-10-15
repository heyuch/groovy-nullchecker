package corpus.strict

import groovy.transform.TypeChecked

/**
 * Rule R10 (alias): a variable becomes null by assignment, not just by its
 * initialiser, and is then dereferenced.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowReassignedNull {
    int run(String input) {
        String s = input
        s = null
        return s.length()
    }
}
