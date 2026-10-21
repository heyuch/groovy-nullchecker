package corpus.strict

import groovy.transform.TypeChecked

/** Phase 0: branch states join by may-null union and must-fact intersection. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowBranchMerge {

    int eitherBranch(boolean c) {
        String x = null
        if (c) {
            x = 'a'
        } else {
            x = null
        }
        return x.length()
    }

    int noElse(boolean c) {
        String x = null
        if (c) {
            x = 'a'
        }
        return x.length()
    }

    int safeAfterReturn(boolean c) {
        String x = 'a'
        if (c) {
            x = null
            return 0
        }
        return x.length()
    }

    int unsafeAfterReturn(boolean c) {
        String x = null
        if (c) {
            x = 'a'
            return 0
        }
        return x.length()
    }
}
