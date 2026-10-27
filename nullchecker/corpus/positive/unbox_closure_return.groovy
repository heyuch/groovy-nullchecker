package corpus.positive

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** R11: a nullable closure result assigned to a primitive must be reported at the unboxing. */
@CompileStatic
@NullMarked
class UnboxClosureReturn {

    boolean assigned() {
        Closure<Boolean> c = { -> null }
        boolean value = c.call()
        return value
    }
}
