package demo

import groovy.transform.CompileStatic

/**
 * Reported: a @CompileStatic class dereferencing a @Nullable field with no guard.
 *
 * @CompileStatic is the whole point -- these classes used to be skipped silently, which is
 * why a statically compiled project reported "no findings" that meant "nothing was checked".
 */
@CompileStatic
class Violation {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
