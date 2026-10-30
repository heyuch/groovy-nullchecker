package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.expr.ConstantExpression
import spock.lang.Specification

/** H0-2: a budget diagnostic must reach the run's internal-error channel. */
class NullCheckReporterSpec extends Specification {

    def setup() {
        NullCheckReporter.resetInternalErrors()
    }

    def cleanup() {
        NullCheckReporter.resetInternalErrors()
        System.clearProperty('groovy.nullchecker.findingsFile')
    }

    def 'an explicit category reaches the report function unchanged'() {
        given:
        List<String> categories = []
        NullCheckReporter reporter = new NullCheckReporter(
                { String message, Object node, String category -> categories.add(category) })

        when:
        reporter.report('Cannot dereference null', new ConstantExpression('x'),
                NullCheckCategories.RESOLUTION)

        then:
        categories == [NullCheckCategories.RESOLUTION]
    }

    def 'a budget diagnostic makes the run unclean and writes the sidecar'() {
        given:
        File findings = new File(File.createTempDir(), 'findings.tsv')
        System.setProperty('groovy.nullchecker.findingsFile', findings.absolutePath)
        NullCheckReporter reporter = new NullCheckReporter({ String message, Object node -> })

        when:
        reporter.diagnostic('flow worklist budget exhausted after 10000 step(s)')

        then:
        NullCheckReporter.internalErrors == ['flow worklist budget exhausted after 10000 step(s)']
        new File(findings.absolutePath + '.errors').getText('UTF-8').contains('budget exhausted')
    }
}
