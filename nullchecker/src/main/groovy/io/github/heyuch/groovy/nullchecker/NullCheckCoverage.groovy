package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/**
 * What a check run actually looked at.
 *
 * <p>Exists so that {@code findings = 0} can be told apart from "nothing was checked".
 * The failure this guards against is not a wrong message but silence: a class type the
 * checker cannot reach, a mistyped extension, or a registration phase that never runs
 * all look exactly like clean code.
 *
 * <p>{@link #compileStaticClasses} is the count that matters most in practice. Classes
 * compiled with {@code @CompileStatic} are the ones that were silently skipped until
 * {@link NullCheckCustomizer} declared a registration phase, and they are the majority
 * of a typical Groovy codebase, so a non-zero count is the cheapest evidence that the
 * checker is really running.
 */
@CompileStatic
class NullCheckCoverage {

    /** Source files handed to the checker. */
    int files

    /** Classes registered with the checker; synthetic package-info nodes are excluded. */
    int classes

    /** Of those, how many are compiled statically. */
    int compileStaticClasses

    /** Of those, how many are non-null by default, from a class, outer class or package. */
    int nonNullByDefaultClasses

    /** Methods with a non-void return type, the ones a null return would violate. */
    int methodsWithReturnType

    @Override
    String toString() {
        return String.format('files=%d classes=%d compileStatic=%d nonNullByDefault=%d methodsWithReturnType=%d',
                files, classes, compileStaticClasses, nonNullByDefaultClasses, methodsWithReturnType)
    }
}
