package corpus.strict

import groovy.transform.TypeChecked

/** F2: for may run zero times; do-while runs once; switch joins its case outcomes. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowLoopSwitch {

    int whileZero(boolean c) {
        String x = null
        while (c) {
            x = 'a'
        }
        return x.length()
    }

    int doOnce() {
        String x = null
        do {
            x = 'a'
        } while (false)
        return x.length()
    }

    int forZero(List<String> values) {
        String x = null
        for (String value : values) {
            x = 'a'
        }
        return x.length()
    }

    int switchCase(int selector) {
        String x = null
        switch (selector) {
            case 0:
                x = null
                break
            default:
                x = 'a'
        }
        return x.length()
    }
}
