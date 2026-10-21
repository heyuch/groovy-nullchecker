package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckCanary
import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * The collection mode: with a sink installed, findings must not reach the compiler's error
 * collector, or a build integration could not keep {@code failOnError = false} working.
 */
class NullCheckSinkSpec extends Specification {

    def cleanup() {
        NullCheckSinks.clear()
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
