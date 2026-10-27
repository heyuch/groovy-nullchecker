package corpus.negative

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** An escaping closure is not trusted for return-nullness propagation. */
@CompileStatic
@NullMarked
class ClosureEscapedReturn {

    static void sink(Closure value) {
    }

    int good() {
        Closure<String> c = { -> null }
        sink(c)
        String value = c.call()
        return value.length()
    }
}
