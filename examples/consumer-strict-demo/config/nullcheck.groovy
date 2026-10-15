// Global injection for a consumer project -- covers @CompileStatic classes.
//
// WHY THE WRAPPER IS INLINED HERE
// A bare ASTTransformationCustomizer does not reach @CompileStatic classes: they are
// silently skipped, so a project that is entirely statically compiled reports
// "no findings" that actually means "nothing was checked". The fix is to declare the
// registration phase, which needs a CompilationCustomizer wrapper -- and a wrapper
// cannot be taken from the checker jar here, because Gradle compiles configuration
// scripts with a classloader that excludes the project's compile classpath. Even a
// reflective Class.forName fails with ClassNotFoundException. That was verified, not
// assumed, so this script stays self-contained: everything below comes from Groovy
// itself and needs no import from any dependency.
import groovy.transform.CompilationUnitAware
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer

class NullCheckRegistration extends CompilationCustomizer implements CompilationUnitAware {

    private final ASTTransformationCustomizer delegate

    NullCheckRegistration(CompilePhase phase, ASTTransformationCustomizer delegate) {
        super(phase)
        this.delegate = delegate
    }

    @Override
    void setCompilationUnit(CompilationUnit unit) {
        // ASTTransformationCustomizer is CompilationUnitAware; not forwarding this is a
        // convincing way to get a wrapper that looks right and checks nothing.
        if (delegate instanceof CompilationUnitAware) {
            ((CompilationUnitAware) delegate).setCompilationUnit(unit)
        }
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        delegate.call(source, context, classNode)
    }
}

// The extension name MUST be a String: passing a Class or ClassExpression is silently
// ignored, which produces a green build that checked nothing.
def inner = new ASTTransformationCustomizer(
        [extensions: ['io.github.heyuch.groovy4.nullchecker.NullChecker']],
        Class.forName('groovy.transform.TypeChecked'))

configuration.addCompilationCustomizers(
        new NullCheckRegistration(CompilePhase.CANONICALIZATION, inner))
