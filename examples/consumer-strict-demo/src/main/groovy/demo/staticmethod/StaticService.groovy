package demo.staticmethod

import demo.Nullable
import groovy.transform.CompileStatic

/**
 * A @CompileStatic class, checked by the global injection in config/nullcheck.groovy.
 *
 * This class is the point: before NullCheckCustomizer, everything here was skipped and
 * the build stayed green no matter what, which is exactly what a real project looks like
 * (they are typically all @CompileStatic).
 *
 * Deliberately correct, so the example's own build stays green. The failing counterpart
 * is in samples/broken/compile-static/.
 */
@CompileStatic
class StaticService {

    @Nullable
    private String cached

    /** Safe: guarded. */
    int guardedLength() {
        return cached == null ? 0 : cached.length()
    }

    /** Safe: safe navigation. */
    Integer safeLength() {
        return cached?.length()
    }
}
