package corpus.pkg.marked

import groovy.transform.TypeChecked

/**
 * Rule R09: the package default makes this field non-null, so strict mode must demand
 * it be initialised. Without this the same marking would mean one thing for returns and
 * parameters and another for fields.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FieldsFromPackage {
    String neverInitialised
}
