package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckCli
import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * Selecting which rule families run.
 *
 * <p>Turning a family off is not the same as not failing on it: a project that has decided
 * it cannot satisfy the initialisation requirement yet should stop seeing those findings,
 * not learn to scroll past them. This is what makes a package-level {@code @NullMarked}
 * usable in code whose fields are assigned by a container.
 */
class CheckSelectionSpec extends Specification {

    static final String EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    /** One class that produces one finding from each family. */
    static final String SOURCE = '''package fixtures

import corpus.support.NonNull
import groovy.transform.CompileStatic

@CompileStatic
class Both {
    @NonNull
    String field

    static int deref() {
        String value = null
        return value.length()
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-checks', '')
        new File(dir, 'Both.groovy').setText(SOURCE, 'UTF-8')
        System.clearProperty('groovy.nullchecker.checks')
    }

    def cleanup() {
        System.clearProperty('groovy.nullchecker.checks')
        dir.deleteDir()
    }

    def 'both families run by default'() {
        when:
        NullCheckHarness.Result result = NullCheckHarness.check([new File(dir, 'Both.groovy')], EXTENSION, true)

        then:
        count(result, NullCheckHarness.Category.NULL) == 1
        count(result, NullCheckHarness.Category.INITIALIZATION) == 1
    }

    def 'disabling initialization stops it being reported, not just failing'() {
        given:
        System.setProperty('groovy.nullchecker.checks', 'null')

        when:
        NullCheckHarness.Result result = NullCheckHarness.check([new File(dir, 'Both.groovy')], EXTENSION, true)

        then:
        count(result, NullCheckHarness.Category.NULL) == 1
        count(result, NullCheckHarness.Category.INITIALIZATION) == 0
    }

    def 'disabling nullness leaves only the initialization check'() {
        given:
        System.setProperty('groovy.nullchecker.checks', 'initialization')

        when:
        NullCheckHarness.Result result = NullCheckHarness.check([new File(dir, 'Both.groovy')], EXTENSION, true)

        then:
        count(result, NullCheckHarness.Category.NULL) == 0
        count(result, NullCheckHarness.Category.INITIALIZATION) == 1
    }

    def 'the self-check runs every family regardless of the selection'() {
        given: 'nullness disabled, which the canary itself relies on'
        System.setProperty('groovy.nullchecker.checks', 'initialization')

        when:
        NullCheckHarness.CanaryResult canary = NullCheckHarness.runCanary(EXTENSION)

        then: 'the canary still proves the machinery works rather than reporting a broken tool'
        canary.healthy
    }

    def 'a bare checker rejects an unknown checks value instead of reporting clean'() {
        given:
        System.setProperty('groovy.nullchecker.checks', 'bogus')

        when:
        NullCheckHarness.check([new File(dir, 'Both.groovy')], EXTENSION, true)

        then:
        IllegalStateException failure = thrown()
        failure.message.contains('configuration')
        failure.message.contains('unknown value')
    }

    def 'a typo in --checks is rejected rather than silently disabling everything'() {
        given:
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()

        expect:
        NullCheckCli.run(['--checks', 'bogus', dir.absolutePath], new PrintStream(out), new PrintStream(err)) == 2
        err.toString('UTF-8').contains('--checks')
    }

    def 'selecting nothing is rejected'() {
        given:
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()

        expect:
        NullCheckCli.run(['--checks', ' ', dir.absolutePath], new PrintStream(out), new PrintStream(err)) == 2
    }

    private static int count(NullCheckHarness.Result result, NullCheckHarness.Category category) {
        NullCheckHarness.findingsIn(result.findings, [category.label]).size()
    }
}
