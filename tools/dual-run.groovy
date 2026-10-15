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

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

class CorpusProbe {

    /** Must match NullCheckHarness.EXTENSION_PLACEHOLDER. */
    static final String PLACEHOLDER = '@NULLCHECKER_EXTENSION@'

    File corpusDir
    String extension
    List<File> support = []

    /** Compiles one fixture and returns the sorted "path:line: message" lines reported. */
    List<String> check(File fixture, boolean strict) {
        System.setProperty('groovy.nullchecker.strict', strict.toString())
        String name = strict && System.getProperty('gate.extensionParams') == 'true'
                ? "${extension}(strict: true)"
                : extension

        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new ASTTransformationCustomizer(
                [extensions: [name]], Class.forName('groovy.transform.TypeChecked')))

        CompilationUnit unit = new CompilationUnit(configuration)
        // Sources are added as text so the fixture's extension placeholder can be
        // substituted: one corpus must be runnable by both implementations, otherwise
        // the comparison would report "no findings" everywhere and look like agreement.
        ([fixture] + support).each { File f ->
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
                .findAll { String line -> line ==~ /.*\.groovy:\s*\d+:.*/ }
                .collect { String line -> line.replace(corpusDir.absolutePath + File.separator, '') }
                .sort()
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
