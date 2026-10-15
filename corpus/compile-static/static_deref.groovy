package corpus.compileStatic

import corpus.support.Nullable
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked

/**
 * Rule R05: a @CompileStatic class must be checked too.
 *
 * This fixture is the regression guard for the defect where a bare
 * ASTTransformationCustomizer never reached statically compiled classes, so a project
 * that is entirely @CompileStatic reported zero findings that meant "nothing was
 * checked". It must keep reporting, or the checker is silently useless on exactly the
 * code shape most real Groovy projects have.
 *
 * Both annotations are present because that is a real combination: @CompileStatic for
 * performance, explicit @TypeChecked naming the extension for clarity. The class is
 * still missed if the registration phase is wrong, which is what this pins.
 */
@CompileStatic
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class StaticDeref {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
