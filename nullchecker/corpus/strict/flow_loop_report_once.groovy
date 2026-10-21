package corpus.strict

import groovy.transform.TypeChecked

/** F2b: a loop body may be analysed more than once, but a finding is reported once. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowLoopReportOnce {

    int run(boolean c) {
        while (c) {
            String y = null
            y.length()
        }
        return 0
    }
}
