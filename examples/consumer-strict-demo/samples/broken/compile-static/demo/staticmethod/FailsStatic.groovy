package demo.staticmethod

import demo.Nullable
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked

/**
 * Reported: a @CompileStatic class that dereferences a @Nullable field with no guard.
 *
 * Uses explicit @TypeChecked so the file stands alone when passed to the report task;
 * the @CompileStatic annotation is what used to make it invisible.
 */
@CompileStatic
@TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')
class FailsStatic {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
