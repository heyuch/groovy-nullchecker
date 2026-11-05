package corpus.negative

import corpus.support.NullMarked
import corpus.support.Nullable
import groovy.transform.CompileStatic

/** A guarded nullable field stays non-null while evaluating a call argument. */
@CompileStatic
@NullMarked
class GuardedNullableFieldArgument {

    void checkChain(@Nullable CallSite call) {
        if (call == null || call.receiver == null) {
            return
        }
        CallSite origin = factoryOf(call.receiver)
        if (origin == null) {
            return
        }
    }

    private @Nullable CallSite factoryOf(Expression receiver) {
        return null
    }
}

class CallSite {
    @Nullable Expression receiver
}

class Expression {
}
