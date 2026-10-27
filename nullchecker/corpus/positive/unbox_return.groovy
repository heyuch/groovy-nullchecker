package corpus.positive

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** R11: a nullable value returned from a primitive method must be reported. */
@CompileStatic
@NullMarked
class UnboxReturn {

    boolean call() {
        Closure<Boolean> c = { -> null }
        return c.call()
    }
}
