package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Rule R05: an iteration hands out elements, not the collection.
 *
 * A for-each variable and a closure parameter both receive the element type, so with
 * {@code List<@Nullable String>} each of them is nullable and dereferencing one must be
 * reported. The annotation lives on the collection's type argument and Groovy does not copy
 * it onto the variable, which is why the checker records the variable as it enters the scope.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ElementIterationDeref {
    static int forEach(List<@Nullable String> names) {
        int total = 0
        for (String name : names) {
            total += name.length()
        }
        return total
    }

    static int closureWithParameter(List<@Nullable String> names) {
        int total = 0
        names.each { String name -> total += name.length() }
        return total
    }

    static int closureWithImplicitIt(List<@Nullable String> names) {
        int total = 0
        names.each { total += it.length() }
        return total
    }
}
