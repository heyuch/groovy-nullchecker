package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckCli
import spock.lang.Specification

/** H3-2: option values must not silently consume the next option. */
class NullCheckCliSpec extends Specification {

    def 'an option that looks like a value is rejected'() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()

        expect:
        NullCheckCli.run(['--report', '--strict', 'src'], new PrintStream(out), new PrintStream(err)) == 2
        err.toString('UTF-8').contains('--report')
    }

    def 'a missing value is rejected'() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()

        expect:
        NullCheckCli.run(['--support'], new PrintStream(out), new PrintStream(err)) == 2
        err.toString('UTF-8').contains('--support')
    }
}
