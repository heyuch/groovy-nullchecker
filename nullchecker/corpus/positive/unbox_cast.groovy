package corpus.positive

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** R11: an explicit cast to primitive is an unboxing site. */
@CompileStatic
@NullMarked
class UnboxCast {

    boolean call() {
        Closure<Boolean> c = { -> null }
        return (boolean) c.call()
    }
}
