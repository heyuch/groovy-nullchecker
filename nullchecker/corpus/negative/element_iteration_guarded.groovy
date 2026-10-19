package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: elements of a nullable-element collection that are checked, or never dereferenced.
 *
 * The element being nullable must not turn into "everything here is a finding": a null check
 * narrows it, safe navigation avoids the dereference, and a collection of non-null elements
 * reports nothing at all.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementIterationGuarded {
    static int guardedLoop(List<@Nullable String> names) {
        int total = 0
        for (String name : names) {
            if (name != null) {
                total += name.length()
            }
        }
        return total
    }

    static int guardedClosure(List<@Nullable String> names) {
        int total = 0
        names.each { String name ->
            if (name != null) {
                total += name.length()
            }
        }
        return total
    }

    static int implicitItWithGuard(List<@Nullable String> names) {
        int total = 0
        names.each { total += (it?.length() ?: 0) }
        return total
    }

    static int nonNullElements(List<String> names) {
        int total = 0
        for (String name : names) {
            total += name.length()
        }
        names.each { total += it.length() }
        return total
    }
}
