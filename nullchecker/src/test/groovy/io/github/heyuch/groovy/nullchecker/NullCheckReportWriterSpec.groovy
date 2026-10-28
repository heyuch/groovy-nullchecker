package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckCanary
import io.github.heyuch.groovy.nullchecker.harness.NullCheckCli
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import spock.lang.Specification

/** The report formatter is the single source of text for file and stream targets. */
class NullCheckReportWriterSpec extends Specification {

    def 'renders metadata and finding lines in a stable order'() {
        given:
        NullCheckReport report = new NullCheckReport(
                groovyVersion: '4.0.30',
                extension: 'io.github.heyuch.groovy.nullchecker.NullChecker',
                strict: true,
                files: 2,
                coverage: 'files=2 classes=1',
                failOn: ['null', 'initialization'],
                findings: [
                        NullCheckReportWriter.finding('dir/A.groovy', 7, 'null',
                                "Potential null dereference: 'value'"),
                ],
                raw: 'raw compiler output')

        when:
        String text = NullCheckReportWriter.render(report)

        then:
        text.startsWith('groovy=4.0.30\nextension=io.github.heyuch.groovy.nullchecker.NullChecker\n')
        text.contains('findings=1\n')
        text.contains('coverage=files=2 classes=1\n')
        text.contains('fail-on=null,initialization\n\n')
        text.contains('File: dir/A.groovy\n')
        text.contains('    Loc=.(A.groovy:7) [null] Potential null dereference: \'value\'\n')
        text.endsWith('raw compiler output\n')
    }

    def 'CLI writes the same report to file, stdout and stderr'() {
        given:
        File dir = File.createTempDir('nullchecker-report-cli', '')
        NullCheckCanary.SAMPLES.each { String name, String source ->
            File file = new File(dir, name)
            file.parentFile.mkdirs()
            file.setText(source, 'UTF-8')
        }
        File annotation = new File(dir, 'canary/Nullable.groovy')
        annotation.setText(NullCheckCanary.NULLABLE_SOURCE, 'UTF-8')
        File report = new File(dir, 'report.txt')
        ByteArrayOutputStream stdout = new ByteArrayOutputStream()
        ByteArrayOutputStream stderr = new ByteArrayOutputStream()

        when:
        int status = NullCheckCli.run(['--no-canary', '--report', report.absolutePath,
                                      '--report-stdout', '--report-stderr', dir.absolutePath],
                new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8.name()))

        then:
        status == 1
        String text = report.getText('UTF-8')
        text.contains('findings=2')
        stdout.toString('UTF-8').contains(text)
        stderr.toString('UTF-8') == text

        cleanup:
        dir.deleteDir()
    }

    def 'writes the same text to a file'() {
        given:
        NullCheckReport report = new NullCheckReport(files: 1, findings: [])
        File target = File.createTempFile('nullchecker-report', '.txt')

        when:
        NullCheckReportWriter.write(target.toPath(), report)

        then:
        target.getText('UTF-8') == NullCheckReportWriter.render(report)

        cleanup:
        Files.deleteIfExists(target.toPath())
    }
}
