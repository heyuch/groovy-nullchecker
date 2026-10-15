package io.github.heyuch.groovy4.nullchecker.harness

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

/**
 * Runs a type-checking extension over a set of sources without writing bytecode.
 *
 * <p>This class exists because every wrong way of invoking a type checker fails the
 * same way -- silently reporting nothing -- so the invocation is centralised in one
 * place that encodes the correct behaviour:
 *
 * <ul>
 *   <li><b>Right phase.</b> The check runs during
 *       {@link Phases#INSTRUCTION_SELECTION}: earlier phases (SEMANTIC_ANALYSIS,
 *       CANONICALIZATION) produce zero findings even for illegal input, and compiling
 *       into OUTPUT would write class files.</li>
 *   <li><b>String extension.</b> The {@code extensions} member is declared as
 *       {@code Class[]}, but at runtime only a {@code String} is honoured.
 *       Passing {@code Class} or {@code ClassExpression} is silently ignored.</li>
 *   <li><b>Collector fallback.</b> A compilation that returns normally has not
 *       necessarily run cleanly; {@code errorCollector.errors} is inspected as well,
 *       so "collected but not thrown" is still reported as a finding.</li>
 *   <li><b>Fail closed.</b> A missing extension class is an error, never an empty
 *       result.</li>
 * </ul>
 *
 * <p>Deliberately free of {@code System.exit}: it is used both by the CLI driver and
 * by in-process tests.
 */
@CompileStatic
class NullCheckHarness {

    /**
     * Placeholder for the extension class name inside corpus fixtures.
     *
     * <p>Fixtures spell {@code @TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')} and
     * the harness substitutes the real name. Without this the corpus could only ever be
     * run against one implementation, and the Groovy 6 comparison would silently
     * produce "no findings" for every fixture -- because Groovy 6 cannot load a
     * Groovy 4 class name -- which reads exactly like perfect agreement.
     */
    static final String EXTENSION_PLACEHOLDER = '@NULLCHECKER_EXTENSION@'

    /** One reported problem, normalised to a stable single line. */
    static class Finding {
        String path
        int line
        String message

        @Override
        String toString() {
            "${path}:${line}: ${message}"
        }
    }

    /** Result of one compilation. */
    static class Result {
        List<Finding> findings = []
        /** Raw compiler output, kept for diagnostics and snapshotting. */
        String raw = ''
        /** True when compilation threw, i.e. findings came from an exception. */
        boolean threw = false
        /** True when the compiler returned normally but had collected errors. */
        boolean collectedOnly = false

        boolean isClean() {
            findings.isEmpty()
        }
    }

    /**
     * Compiles {@code files} with {@code extension} enabled through {@code @TypeChecked}
     * and returns whatever the checker reported.
     *
     * @param files     sources to check
     * @param extension fully qualified type-checking extension class name
     * @param strict    value of the checker's {@code strict} free variable
     * @return findings plus raw compiler output
     */
    static Result check(List<File> files, String extension, boolean strict) {
        // Probe for the class name eagerly: a typo must be loud, not an empty result.
        try {
            Class.forName(extension, false, NullCheckHarness.classLoader)
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "type-checking extension not on the classpath: ${extension}", t)
        }

        // The strict option is passed the way the production checker exposes it. The
        // Groovy 6 reference implementation has no setOptions (it reads its own
        // getOptions()), so fall back to the system property it cannot see either.
        applyStrict(extension, strict)

        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new ASTTransformationCustomizer(
                [extensions: [extension]], Class.forName('groovy.transform.TypeChecked')))

        CompilationUnit unit = new CompilationUnit(configuration)
        // Sources are added as text so the extension placeholder can be substituted.
        // This keeps one corpus usable by both the port and the Groovy 6 reference.
        files.each { File f ->
            String source = f.getText('UTF-8')
            if (source.contains(EXTENSION_PLACEHOLDER)) {
                source = source.replace(EXTENSION_PLACEHOLDER, extension)
            }
            unit.addSource(f.absolutePath, source)
        }

        Result result = new Result()
        try {
            unit.compile(Phases.INSTRUCTION_SELECTION)
            List errors = (List) (unit.errorCollector?.errors ?: [])
            if (errors) {
                result.collectedOnly = true
                result.raw = errors.collect { Object e -> e.toString() }.join('\n')
                result.findings = errors.collect { Object e -> errorToFinding(e, files) }
            } else {
                result.raw = 'no findings'
            }
        } catch (MultipleCompilationErrorsException e) {
            result.threw = true
            result.raw = e.message
            result.findings = parseFindings(e.message, files)
        }
        result
    }

    /**
     * Enables or disables strict analysis.
     *
     * <p>Groovy 6 reads {@code options.strict} from extension parameters that Groovy 4
     * cannot pass (see the port notes), so both channels are used: the checker's own
     * API when present, and the system property as the portable fallback.
     */
    private static void applyStrict(String extension, boolean strict) {
        System.setProperty('groovy.nullchecker.strict', Boolean.toString(strict))
        try {
            Class<?> checker = Class.forName(extension, false, NullCheckHarness.classLoader)
            checker.getMethod('setOptions', Map).invoke(null, [strict: strict])
        } catch (NoSuchMethodException | ClassNotFoundException ignored) {
            // the Groovy 6 reference implementation: system property is all it can see
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not configure ${extension}", e)
        }
    }

    /**
     * Extracts {@code path: line: message} from a compiler error dump. The dump
     * interleaves each message with a code excerpt, so only the message line is taken.
     *
     * @param message raw compiler output
     * @param files   sources of the compilation, used to recognise paths
     * @return findings in report order
     */
    static List<Finding> parseFindings(String message, List<File> files) {
        Set<String> known = files.collect { File f -> f.absolutePath } as Set<String>
        List<Finding> out = []
        message.readLines().each { String line ->
            // "<path>: <line>: <message>". Anchor on ".groovy: <digits>:" rather than
            // splitting greedily, because paths can themselves contain colons.
            def matcher = (line.trim() =~ /^(.*\.groovy):\s*(\d+):\s*(.*)$/)
            if (matcher.matches()) {
                String path = (String) matcher.group(1)
                String resolved = known.find { String k -> k == path || k.endsWith(File.separator + path) }
                out << new Finding(path: resolved ?: path,
                        line: Integer.parseInt((String) matcher.group(2)),
                        message: ((String) matcher.group(3)).trim())
            }
        }
        out
    }

    private static Finding errorToFinding(Object error, List<File> files) {
        String text = error.toString()
        List<Finding> parsed = parseFindings(text, files)
        parsed ? parsed.first() : new Finding(path: '', line: 0, message: text.trim())
    }
}
