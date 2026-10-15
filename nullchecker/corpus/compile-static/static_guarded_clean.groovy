package corpus.compileStatic

import corpus.support.Nullable
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked

/**
 * Guard N12: a @CompileStatic class that is correctly guarded must stay silent.
 *
 * Pairs with static_deref.groovy. Without it, "statically compiled classes are now
 * checked" could be satisfied by reporting everything in them, which would be just as
 * useless as reporting nothing.
 */
@CompileStatic
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class StaticGuardedClean {

    @Nullable
    private String value

    int guarded() {
        return value == null ? 0 : value.length()
    }

    Integer safeNav() {
        return value?.length()
    }

    int earlyExit() {
        if (value == null) {
            return 0
        }
        return value.length()
    }
}
