package corpus.negative

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** A closure that always returns a value must not make its call result nullable. */
@CompileStatic
@NullMarked
class ClosureReturnNonNull {

    int good() {
        Closure<String> c = { -> 'policy_no' }
        String value = c.call()
        return value.length()
    }
}
