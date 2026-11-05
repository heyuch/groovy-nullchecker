package io.github.heyuch.groovy.nullchecker

import spock.lang.Specification

/** H2-4: the findings CLI has an in-process entry point with stable exit codes. */
class NullCheckFindingsCliSpec extends Specification {

    File dir

    def setup() {
        dir = File.createTempDir('nullcheck-findings-cli', '')
    }

    def cleanup() {
        dir.deleteDir()
    }

    def 'help returns zero and prints usage'() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()

        expect:
        NullCheckFindingsCli.run(['--help'], new PrintStream(out), new PrintStream(err)) == 0
        out.toString('UTF-8').contains('--findings')
    }

    def 'unknown and incomplete options return two'() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()
        PrintStream outStream = new PrintStream(out)
        PrintStream errStream = new PrintStream(err)

        expect:
        NullCheckFindingsCli.run(['--bogus'], outStream, errStream) == 2
        NullCheckFindingsCli.run(['--findings'], outStream, errStream) == 2
        NullCheckFindingsCli.run(['--findings', new File(dir, 'missing.tsv').absolutePath],
                outStream, errStream) == 2
    }

    def 'a failing category returns one and a clean resolution returns zero'() {
        given:
        File findings = new File(dir, 'findings.tsv')
        File coverage = new File(dir, 'findings.tsv.coverage')
        coverage.setText('class Demo\n', 'UTF-8')
        findings.setText(NullCheckFindingTsv.toTsv(
                NullCheckFinding.of('/tmp/A.groovy', 1, NullCheckFinding.NULL,
                        'Potential null dereference: value')) + '\n', 'UTF-8')
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()

        expect:
        NullCheckFindingsCli.run(['--findings', findings.absolutePath],
                new PrintStream(out), new PrintStream(err)) == 1

        when:
        findings.setText(NullCheckFindingTsv.toTsv(
                NullCheckFinding.of('/tmp/A.groovy', 1, NullCheckFinding.RESOLUTION,
                        'Cannot resolve something')) + '\n', 'UTF-8')

        then:
        NullCheckFindingsCli.run(['--findings', findings.absolutePath],
                new PrintStream(out), new PrintStream(err)) == 0
    }
}
