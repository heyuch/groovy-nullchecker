package corpus.negative

import corpus.support.NullMarked
import groovy.transform.CompileStatic

/** A bare return inside a closure exits the closure, not the @NonNull method. */
@CompileStatic
@NullMarked
class ClosureBareReturn {

    List<String> withForEach(List<String> codes, Map<String, String> byCode) {
        List<String> result = []
        codes.forEach { String code ->
            String value = byCode[code]
            if (value == null) {
                return
            }
            result.add(value)
        }
        return result
    }
}
