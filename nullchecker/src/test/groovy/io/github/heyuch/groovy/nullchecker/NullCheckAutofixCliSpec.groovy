package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckAutofixCli
import spock.lang.Specification

/** H3-5: a missing/invalid provider fails before touching source. */
class NullCheckAutofixCliSpec extends Specification {

    def 'an unknown provider class is rejected'() {
        given:
        File dir = File.createTempDir('nullcheck-autofix-cli', '')
        File source = new File(dir, 'A.groovy')
        source.setText('class A {}\n', 'UTF-8')
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream err = new ByteArrayOutputStream()

        expect:
        NullCheckAutofixCli.run(['--dry-run', '--provider', 'does.not.Exist', source.absolutePath],
                new PrintStream(out), new PrintStream(err)) == 2
        err.toString('UTF-8').contains('cannot load provider')

        cleanup:
        dir.deleteDir()
    }
}
