package demo.broken

import demo.NonNull
import groovy.transform.TypeChecked

/**
 * Nothing is annotated here except the field, so strict mode is what reports these.
 * Run with -Pnullcheck.strict=true.
 */
@TypeChecked(extensions = 'io.github.heyuch.groovy4.nullchecker.NullChecker')
class BrokenFlowSensitive {

    /** Reported only in strict mode: never initialised. */
    @NonNull
    String neverAssigned
}
