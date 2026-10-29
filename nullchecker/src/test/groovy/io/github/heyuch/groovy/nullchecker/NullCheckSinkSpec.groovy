package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckCanary
import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import spock.lang.Specification

/**
 * The collection mode: with a sink installed, findings must not reach the compiler's error
 * collector, or a build integration could not keep {@code failOnError = false} working.
 */
class NullCheckSinkSpec extends Specification {

    def cleanup() {
        NullCheckSinks.clear()
        System.clearProperty('groovy.nullchecker.findingsFile')
    }

    def "findings go to the sink instead of the compiler"() {
        given:
        CollectingSink sink = new CollectingSink()
        NullCheckSinks.install(sink)
        File dir = File.createTempDir()
        List<File> files = []
        NullCheckCanary.SAMPLES.each { String name, String source ->
            File sample = new File(dir, name)
            sample.parentFile.mkdirs()
            sample.setText(source, 'UTF-8')
            files.add(sample)
        }
        File annotation = new File(dir, 'canary/Nullable.groovy')
        annotation.setText(NullCheckCanary.NULLABLE_SOURCE, 'UTF-8')
        files.add(annotation)

        when:
        NullCheckHarness.Result result = NullCheckHarness.check(files, NullChecker.name, false)

        then:
        !sink.findings.isEmpty()
        result.findings.isEmpty()
        sink.findings.every { NullCheckFinding finding ->
            finding.file != null && finding.category != null && finding.message != null
        }

        cleanup:
        dir.deleteDir()
    }

    def "currentSink replaces the cached file sink when the findings file changes"() {
        given:
        File first = new File(File.createTempDir(), 'first.tsv')
        File second = new File(File.createTempDir(), 'second.tsv')
        System.setProperty('groovy.nullchecker.findingsFile', first.absolutePath)
        NullCheckSink firstSink = NullCheckSinks.currentSink()
        System.setProperty('groovy.nullchecker.findingsFile', second.absolutePath)

        when:
        NullCheckSink secondSink = NullCheckSinks.currentSink()

        then:
        firstSink instanceof FileFindingsSink
        secondSink instanceof FileFindingsSink
        !firstSink.is(secondSink)
        ((FileFindingsSink) firstSink).file.absolutePath == first.absolutePath
        ((FileFindingsSink) secondSink).file.absolutePath == second.absolutePath

        when:
        firstSink.report(NullCheckFinding.of('/tmp/A.groovy', 1, 'first'))
        secondSink.report(NullCheckFinding.of('/tmp/A.groovy', 2, 'second'))

        then:
        first.getText('UTF-8').contains('first')
        second.getText('UTF-8').contains('second')
    }

    def "an explicitly installed sink is not replaced by the findings property"() {
        given:
        CollectingSink installed = new CollectingSink()
        NullCheckSinks.install(installed)
        System.setProperty('groovy.nullchecker.findingsFile',
                new File(File.createTempDir(), 'ignored.tsv').absolutePath)

        expect:
        NullCheckSinks.currentSink().is(installed)
    }

    def "clearFileSink preserves an explicitly installed file sink"() {
        given:
        File file = new File(File.createTempDir(), 'explicit.tsv')
        FileFindingsSink installed = new FileFindingsSink(file)
        NullCheckSinks.install(installed)

        when:
        NullCheckSinks.clearFileSink()

        then:
        NullCheckSinks.currentSink().is(installed)
    }

    def "clearing removes the installed sink"() {
        given:
        NullCheckSinks.install(new CollectingSink())

        when:
        NullCheckSinks.clear()

        then:
        NullCheckSinks.currentSink() == null
    }

    def "compilation clears the property-created file sink"() {
        given:
        File target = new File(File.createTempDir(), 'findings.tsv')
        System.setProperty('groovy.nullchecker.findingsFile', target.absolutePath)
        NullCheckSinks.currentSink()
        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new NullCheckCustomizer())
        CompilationUnit unit = new CompilationUnit(configuration)
        unit.addSource('Sample.groovy', 'class Sample {}')

        when:
        unit.compile(Phases.OUTPUT)

        then:
        !NullCheckSinks.hasFileSink()
    }

    def "a finding row round-trips through TSV"() {
        given:
        NullCheckFinding finding = NullCheckFinding.of('/tmp/A.groovy', 12,
                'Potential null dereference: \'value\' is @Nullable')

        when:
        NullCheckFinding parsed = NullCheckFinding.fromTsv(finding.toTsv())

        then:
        parsed.file == finding.file
        parsed.line == finding.line
        parsed.category == NullCheckFinding.NULL
        parsed.message == finding.message
    }

    static class CollectingSink implements NullCheckSink {

        List<NullCheckFinding> findings = []

        @Override
        void report(NullCheckFinding finding) {
            findings.add(finding)
        }
    }
}
