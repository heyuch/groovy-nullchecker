package corpus.negative

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** A Boolean wrapper stays a wrapper; checking it and using Groovy truth is not unboxing. */
@CompileStatic
@NullMarked
class UnboxWrapper {

    int good() {
        Closure<Boolean> c = { -> null }
        Boolean value = c.call()
        if (value != null) {
            return value ? 1 : 0
        }
        return 0
    }
}
