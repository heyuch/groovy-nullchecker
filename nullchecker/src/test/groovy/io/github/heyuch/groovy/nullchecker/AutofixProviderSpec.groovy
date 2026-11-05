package io.github.heyuch.groovy.nullchecker

import spock.lang.Specification

/** H3-5: a provider can propose a declaration through the non-final SPI. */
class AutofixProviderSpec extends Specification {

    def 'a provider can append a field target without owning source edits'() {
        given:
        File dir = File.createTempDir('nullcheck-provider', '')
        File source = new File(dir, 'A.groovy')
        source.setText('''class A {
    String value = null
}
''', 'UTF-8')
        boolean called = false
        NullFixProvider provider = new NullFixProvider() {

            @Override
            List<NullFixProposal> propose(NullFixContext context) {
                called = true
                return [new NullFixProposal(kind: 'field', name: 'value', line: 2,
                        description: 'field A#value')]
            }

        }
        NullableAnnotator.FixFinding finding = new NullableAnnotator.FixFinding(
                path: source.absolutePath, line: 2, message: 'custom provider target')

        when:
        NullableAnnotator.Result result = NullableAnnotator.runFromFindings(
                [source], [finding], NullableAnnotator.DEFAULT_ANNOTATION, false,
                new PrintStream(new ByteArrayOutputStream()), [provider])
        String migrated = source.getText('UTF-8')

        then:
        called
        result.added == 1
        migrated.contains('@Nullable String value')

        cleanup:
        dir.deleteDir()
    }
}
