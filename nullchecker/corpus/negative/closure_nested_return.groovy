package corpus.negative

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** A nested closure's return null must not make the outer closure nullable. */
@CompileStatic
@NullMarked
class ClosureNestedReturn {

    int good() {
        Closure<String> outer = { ->
            Closure<String> inner = { -> null }
            return 'policy_no'
        }
        String value = outer.call()
        return value.length()
    }
}
