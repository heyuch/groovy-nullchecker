package corpus.positive

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** R11: a nullable value passed to a primitive parameter must be reported. */
@CompileStatic
@NullMarked
class UnboxArgument {

    static void consume(boolean flag) {
    }

    void call() {
        Closure<Boolean> c = { -> null }
        consume(c.call())
    }
}
