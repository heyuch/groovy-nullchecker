package corpus.strict

import groovy.transform.TypeChecked

/** F2: case fall-through reaches the next case, not straight to after the switch. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowSwitchFallthrough {

    int run(int selector) {
        String x = null
        switch (selector) {
            case 0:
                x = null
            case 1:
                x = 'b'
                break
            default:
                x = 'b'
        }
        return x.length()
    }
}
