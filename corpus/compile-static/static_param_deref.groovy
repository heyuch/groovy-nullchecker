package corpus.compileStatic

import corpus.support.Nullable
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked

/** Rule R05 under @CompileStatic only, no explicit @TypeChecked: global injection. */
@CompileStatic
class StaticParamDeref {
    int bad(@Nullable String input) {
        return input.length()
    }
}
