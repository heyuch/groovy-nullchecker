package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic
import io.github.heyuch.groovy.nullchecker.NullCheckCoverage
import io.github.heyuch.groovy.nullchecker.NullCheckCustomizer
import io.github.heyuch.groovy.nullchecker.NullChecker
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

    /**
     * Files the checker can analyse: Groovy sources, plus {@code package-info.java},
     * which is where an idiomatic Groovy project declares package-level annotations.
     * A general {@code *.java} match is deliberately avoided -- a Groovy type checker
     * cannot analyse arbitrary Java, and silently trying to would turn every Java
     * source in the tree into a parse error.
     *
     * @param file candidate file
     * @return true when the file should be compiled for checking
     */
    static boolean isCheckable(File file) {
        file.isFile() && (file.name.endsWith('.groovy') || file.name == 'package-info.java')
    }

    /**
     * What kind of problem a finding is.
     *
     * <p>The checker shares one error collector with Groovy's own static type checker, so a
     * run also reports things that are not nullness problems at all -- most often a method
     * the static checker could not resolve in code that is dynamically dispatched at
     * runtime. Both are worth seeing; only the first kind should fail a build.
     */
    static enum Category {
        /** A nullness rule: dereference, return, assignment, argument. */
        NULL,
        /** An explicitly annotated field that no constructor assigns. */
        INITIALIZATION,
        /** A compiler or type-checker error that is not a nullness finding. */
        RESOLUTION

        String getLabel() {
            name().toLowerCase()
        }
    }

    /** Message fragments that identify a nullness finding. */
    private static final List<String> NULL_FRAGMENTS = [
            'Potential null dereference:',
            'Cannot dereference null',
            'Cannot return null from @NonNull method',
            'Cannot return @Nullable value from @NonNull method',
            'Cannot assign null to @NonNull',
            'Cannot assign null to @MonotonicNonNull',
            'Cannot pass null to @NonNull parameter',
            'Cannot pass @Nullable value to @NonNull parameter',
    ] as List<String>

    /** Categories that fail a run by default: the nullness rules the tool exists for. */
    static final List<String> DEFAULT_FAIL_ON = [Category.NULL.label, Category.INITIALIZATION.label] as List<String>

    /** One reported problem, normalised to a stable single line. */
    static class Finding {
        String path
        int line
        String message
        Category category = Category.RESOLUTION

        @Override
        String toString() {
            "${path}:${line}: ${message}"
        }
    }

    /**
     * Classifies one compiler message.
     *
     * <p>Anything that is not one of the checker's own messages is classed as RESOLUTION:
     * it came from the compiler, so it is a statement about the analysis rather than a
     * nullness claim.
     */
    static Category categoryOf(String message) {
        if (message == null) {
            return Category.RESOLUTION
        }
        for (String fragment : NULL_FRAGMENTS) {
            if (message.contains(fragment)) {
                return Category.NULL
            }
        }
        if (message.contains('@NonNull field')) {
            return Category.INITIALIZATION
        }
        Category.RESOLUTION
    }

    /** Counts findings per category label, in a stable order. */
    static Map<String, Integer> categoryCounts(List<Finding> findings) {
        Map<String, Integer> counts = [:]
        for (Category category : Category.values()) {
            counts[category.label] = 0
        }
        for (Finding finding : findings) {
            String label = (finding.category ?: Category.RESOLUTION).label
            counts[label] = counts[label] + 1
        }
        counts
    }

    /** Findings whose category is one of {@code categories}. */
    static List<Finding> findingsIn(List<Finding> findings, Collection<String> categories) {
        List<Finding> selected = []
        for (Finding finding : findings) {
            String label = (finding.category ?: Category.RESOLUTION).label
            if (categories.contains(label)) {
                selected.add(finding)
            }
        }
        selected
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
        /** What was actually looked at, so "no findings" can be told from "no work". */
        NullCheckCoverage coverage = new NullCheckCoverage()

        boolean isClean() {
            findings.isEmpty()
        }
    }

    /** Outcome of the pre-flight self-check. */
    static class CanaryResult {
        boolean healthy
        List<Finding> findings = []
        String detail = ''

        @Override
        String toString() {
            healthy ? "canary ok (${findings.size()} findings)" : "canary FAILED: ${detail}"
        }
    }

    /**
     * Proves the checker can still see a violation before any real result is trusted.
     *
     * <p>Runs the deliberately broken {@link NullCheckCanary} samples through the ordinary
     * checking path. If the statically compiled sample is not reported, every "no
     * findings" the tool could produce is meaningless, so callers must fail rather than
     * report success.
     *
     * @param extension type-checking extension to run
     * @return whether the checker is demonstrably working
     */
    static CanaryResult runCanary(String extension) {
        File dir = File.createTempFile('nullchecker-canary', '')
        CanaryResult outcome = new CanaryResult()
        try {
            if (!dir.delete() || !dir.mkdirs()) {
                outcome.detail = "could not create a working directory for the canary: ${dir}"
                return outcome
            }
            List<File> samples = []
            NullCheckCanary.SAMPLES.each { String name, String source ->
                File f = new File(dir, name)
                f.parentFile.mkdirs()
                f.setText(source, 'UTF-8')
                samples << f
            }
            File annotation = new File(dir, 'canary/Nullable.groovy')
            annotation.setText(NullCheckCanary.NULLABLE_SOURCE, 'UTF-8')
            samples << annotation

            // The self-check proves the machinery works, so it must not inherit the caller's
            // choice of rule families: with nullness disabled the canary would report nothing
            // and the tool would refuse to run at all.
            String savedChecks = System.getProperty(NullChecker.CHECKS_PROPERTY)
            System.setProperty(NullChecker.CHECKS_PROPERTY, NullChecker.CHECKS_ALL)
            Result result
            try {
                result = check(samples, extension, false)
            } finally {
                if (savedChecks == null) {
                    System.clearProperty(NullChecker.CHECKS_PROPERTY)
                } else {
                    System.setProperty(NullChecker.CHECKS_PROPERTY, savedChecks)
                }
            }
            outcome.findings = result.findings

            def required = result.findings.findAll { Finding f ->
                f.path.endsWith(NullCheckCanary.REQUIRED_SAMPLE)
            }
            if (!required) {
                outcome.detail = "the canary's @CompileStatic sample (${NullCheckCanary.REQUIRED_SAMPLE}) " +
                        'was NOT reported, so this checker cannot currently see violations in ' +
                        'statically compiled classes and any "no findings" would be meaningless. ' +
                        "Canary findings were: ${result.findings ?: 'none'}"
                return outcome
            }
            outcome.healthy = true
            outcome
        } catch (Throwable t) {
            outcome.detail = "the canary could not be run: ${t}"
            outcome
        } finally {
            dir.deleteDir()
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
            NullCheckHarness.classLoader.loadClass(extension)
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "type-checking extension not on the classpath: ${extension}", t)
        }

        // The strict option is passed the way the production checker exposes it. The
        // Groovy 6 reference implementation has no setOptions (it reads its own
        // getOptions()), so fall back to the system property it cannot see either.
        applyStrict(extension, strict)

        CompilerConfiguration configuration = new CompilerConfiguration()
        // NullCheckCustomizer, not a bare ASTTransformationCustomizer: the bare one never
        // reaches @CompileStatic classes, so a statically compiled project would report
        // nothing at all. See NullCheckCustomizer for the measured difference.
        configuration.addCompilationCustomizers(new NullCheckCustomizer(extension))

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
        // Reset so a zero count means "this run saw nothing", not "some earlier run did".
        NullChecker.resetCoverage()
        NullChecker.resetInternalErrors()
        NullChecker.resetCaches()
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
        // Groovy swallows an exception raised inside a type-checking extension: the
        // compilation still finishes, usually with no errors, so the run looks clean.
        // See NullChecker#guarded. A recorded failure means part of the tree was never
        // analysed, so no count from this run is reportable.
        List<String> internalFailures = NullChecker.getInternalErrors()
        if (!internalFailures.isEmpty()) {
            throw new IllegalStateException(
                    "the checker failed internally on ${files.size()} file(s), so its result " +
                    "cannot be trusted: ${internalFailures[0]}")
        }
        result.coverage = NullChecker.lastCoverage
        result.coverage.files = files.size()
        result
    }

    /**
     * Enables or disables strict analysis.
     *
     * <p>Groovy 6 reads {@code options.strict} from extension parameters that Groovy 4
     * cannot pass (see the port notes), so both channels are used: the checker's own
     * API when present, and the system property as the portable fallback.
     *
     * <p>Disabling <em>clears</em> the property rather than setting it to
     * {@code false}. Setting {@code false} would out-rank the strict environment
     * variable, so a developer who turned strict on for the whole shell would find
     * this harness silently overriding it -- the same class of "the check did not run
     * the way you think" bug the corpus exists to catch.
     */
    private static void applyStrict(String extension, boolean strict) {
        if (strict) {
            System.setProperty('groovy.nullchecker.strict', 'true')
        } else {
            System.clearProperty('groovy.nullchecker.strict')
        }
        try {
            Class<?> checker = NullCheckHarness.classLoader.loadClass(extension)
            checker.getMethod('setOptions', Map).invoke(null, [strict: strict])
        } catch (NoSuchMethodException | ClassNotFoundException ignored) {
            // the Groovy 6 reference implementation: the property is all it can see
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
                String text = ((String) matcher.group(3)).trim()
                out << new Finding(path: resolved ?: path,
                        line: Integer.parseInt((String) matcher.group(2)),
                        message: text,
                        category: categoryOf(text))
            }
        }
        out
    }

    private static Finding errorToFinding(Object error, List<File> files) {
        String text = error.toString()
        List<Finding> parsed = parseFindings(text, files)
        parsed ? parsed.first() : new Finding(path: '', line: 0, message: text.trim(),
                category: categoryOf(text))
    }
}
