package corpus.positive

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** N1: a direct local closure returning null makes the call result nullable. */
@CompileStatic
@NullMarked
class ClosureReturnNull {

    int bad() {
        Closure<String> c = { -> null }
        String value = c.call()
        return value.length()
    }
}
