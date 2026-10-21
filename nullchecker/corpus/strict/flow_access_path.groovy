package corpus.strict

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** F5: this.value narrows inside the guard and is invalidated by a call. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowAccessPath {

    @Nullable
    String value

    int guarded() {
        if (this.value != null) {
            return this.value.length()
        }
        return 0
    }

    int unguarded() {
        return this.value.length()
    }

    int afterCall() {
        if (this.value != null) {
            reset()
            return this.value.length()
        }
        return 0
    }

    void reset() {
        this.value = null
    }
}
