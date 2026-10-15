// Global injection of the null checker, applied with:
//   groovyOptions.configurationScript = file('config/nullcheck.groovy')
//
// NOTE: the extensions member is declared Class[] in the annotation, but only a
// String is honoured at runtime. Passing a Class here is silently ignored, which
// produces a green build that checked nothing.
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

configuration.addCompilationCustomizers(new ASTTransformationCustomizer(
        [extensions: ['io.github.heyuch.groovy4.nullchecker.NullChecker']],
        Class.forName('groovy.transform.TypeChecked')))
