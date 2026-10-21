package corpus.strict

import groovy.transform.TypeChecked

/** F6: closure bodies are separate CFGs; captured writes join with the zero-execution path. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowClosureCapture {

    int eachMaySkip(List<String> values) {
        String x = null
        values.each { String value -> x = value }
        return x.length()
    }

    int eachGuarded(List<String> values) {
        String x = null
        values.each { x = 'a' }
        if (x != null) {
            return x.length()
        }
        return 0
    }

    int closureReturn(List<String> values) {
        String x = null
        values.each { String value ->
            if (value == null) {
                return
            }
            x = value
        }
        return x.length()
    }
}
