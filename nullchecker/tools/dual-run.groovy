/*
 * Semantic-equivalence probe: run a type-checking extension over the whole corpus and
 * print one stable, diffable line per finding.
 *
 * Written so it can run under EITHER implementation:
 *   - Groovy 4 + this project's ported NullChecker, or
 *   - Groovy 6 + the official groovy.typecheckers.NullChecker.
 *
 * It therefore depends on nothing from this project -- only on the Groovy it is
 * launched with -- and the compiled classes live in a class of their own so their
 * fields resolve under both runtimes.
 *
 * Usage:
 *   groovy dual-run.groovy <corpus-dir> <extension-fqcn> [output-file]
 *
 * Strict mode is requested two ways, because the two implementations read it
 * differently: the system property (works for the port) and, when
 * -Dgate.extensionParams=true is set, an extension parameter 'Ext(strict: true)'
 * (the only channel Groovy 6 offers).
 */

import groovy.transform.CompilationUnitAware
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer

/**
 * Registers the extension with an explicit phase, exactly as the real harness does.
 *
 * <p>A bare {@link ASTTransformationCustomizer} never reaches {@code @CompileStatic}
 * classes, and the failure is silent: those fixtures report nothing under <em>both</em>
 * implementations, which looks like agreement while actually measuring nothing. Wrapping is
 * required for the same reason it is required in the product (see PORT.md §5.1): the
 * registration phase has to be declared, and {@code ASTTransformationCustomizer} cannot be
 * subclassed from Groovy, so this uses composition.
 */
class PhaseCustomizer extends CompilationCustomizer implements CompilationUnitAware {

    private final ASTTransformationCustomizer delegate

    PhaseCustomizer(ASTTransformationCustomizer delegate) {
        super(CompilePhase.CANONICALIZATION)
        this.delegate = delegate
    }

    @Override
    void setCompilationUnit(CompilationUnit unit) {
        if (delegate instanceof CompilationUnitAware) {
            ((CompilationUnitAware) delegate).setCompilationUnit(unit)
        }
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        delegate.call(source, context, classNode)
    }
}

class CorpusProbe {

    /** Must match NullCheckHarness.EXTENSION_PLACEHOLDER. */
    static final String PLACEHOLDER = '@NULLCHECKER_EXTENSION@'

    File corpusDir
    String extension
    List<File> support = []

    /** Compiles one fixture and returns the sorted "path:line: message" lines reported. */
    List<String> check(File fixture, boolean strict) {
        // Clear rather than set to false, so an ambient GROOVY_NULLCHECKER_STRICT cannot
        // be silently overridden by this probe running in its non-strict pass.
        if (strict) {
            System.setProperty('groovy.nullchecker.strict', 'true')
        } else {
            System.clearProperty('groovy.nullchecker.strict')
        }
        String name = strict && System.getProperty('gate.extensionParams') == 'true'
                ? "${extension}(strict: true)"
                : extension

        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new PhaseCustomizer(new ASTTransformationCustomizer(
                [extensions: [name]], Class.forName('groovy.transform.TypeChecked'))))

        CompilationUnit unit = new CompilationUnit(configuration)
        // Sources are added as text so the fixture's extension placeholder can be
        // substituted: one corpus must be runnable by both implementations, otherwise
        // the comparison would report "no findings" everywhere and look like agreement.
        //
        // package-info is included because it is where a fixture declares package-level
        // defaults, and omitting it would make the package fixtures report nothing on
        // both sides -- which would look like agreement rather than a missing input.
        (packageInfoFor(fixture) + [fixture] + support).each { File f ->
            String source = f.getText('UTF-8')
            if (source.contains(PLACEHOLDER)) {
                source = source.replace(PLACEHOLDER, name)
            }
            unit.addSource(f.absolutePath, source)
        }

        String dump
        try {
            unit.compile(Phases.INSTRUCTION_SELECTION)
            def collected = unit.errorCollector?.errors ?: []
            dump = collected ? collected.collect { it.toString() }.join('\n') : ''
        } catch (MultipleCompilationErrorsException e) {
            dump = e.message
        }
        dump.readLines()
                .collect { String line -> line.trim() }
                // .groovy fixtures plus package-info.java, which can also carry an error
                .findAll { String line -> line ==~ /.*(\.groovy|package-info\.java):\s*\d+:.*/ }
                .collect { String line -> line.replace(corpusDir.absolutePath + File.separator, '') }
                .sort()
    }

    /**
     * package-info files governing a fixture's package: the fixture's own directory and
     * its ancestors up to the corpus root, mirroring what the corpus gate does.
     */
    private List<File> packageInfoFor(File fixture) {
        List<File> found = []
        File dir = fixture.parentFile
        while (dir != null && dir.canonicalPath.startsWith(corpusDir.canonicalPath)) {
            ['package-info.java', 'package-info.groovy'].each { String n ->
                File candidate = new File(dir, n)
                if (candidate.isFile()) {
                    found << candidate
                }
            }
            if (dir.canonicalPath == corpusDir.canonicalPath) {
                break
            }
            dir = dir.parentFile
        }
        found
    }

    String render() {
        List<File> fixtures = []
        corpusDir.eachFileRecurse { File f ->
            if (f.isFile() && f.name.endsWith('.groovy') && f.parentFile != new File(corpusDir, 'support')) {
                fixtures << f
            }
        }
        new File(corpusDir, 'support').eachFileRecurse { File f ->
            if (f.isFile() && f.name.endsWith('.groovy')) {
                support << f
            }
        }

        StringBuilder out = new StringBuilder()
        out.append("groovy=${GroovySystem.version}\n")
        out.append("extension=${extension}\n")
        fixtures.sort { it.absolutePath }.each { File fixture ->
            String rel = corpusDir.toPath().relativize(fixture.toPath())
                    .toString().replace(File.separator, '/')
            [false, true].each { boolean strict ->
                List<String> findings = check(fixture, strict)
                out.append("--- ${rel} [${strict ? 'strict' : 'annotations'}] findings=${findings.size()}\n")
                findings.each { String f -> out.append("    ${f}\n") }
            }
        }
        out.toString()
    }
}

if (args.size() < 2) {
    System.err.println('usage: dual-run.groovy <corpus-dir> <extension-fqcn> [output-file]')
    System.exit(2)
}

CorpusProbe probe = new CorpusProbe(corpusDir: new File(args[0]), extension: args[1])
String report = probe.render()

if (args.size() > 2) {
    File output = new File(args[2])
    output.parentFile?.mkdirs()
    output.text = report
    println "wrote ${output.absolutePath}"
} else {
    print report
}
