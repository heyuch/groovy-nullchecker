package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/** Report-driven fixes for the two high-confidence finding messages. */
class ReportAutofixSpec extends Specification {

    File dir
    File source

    def setup() {
        dir = File.createTempDir('nullchecker-autofix', '')
        write('Annotations.groovy', '''package demo

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE, ElementType.PACKAGE])
@interface NullMarked {
}

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE])
@interface Nullable {
}
''')
        source = write('FixTarget.groovy', '''package demo

@NullMarked
class FixTarget {
    @Nullable
    String nullable() {
        return null
    }

    String badReturn() {
        return nullable()
    }

    void consume(String value) {
        if (value == null) {
            return
        }
        value.length()
    }

    void caller() {
        consume(nullable())
    }
}
''')
    }

    def cleanup() {
        dir.deleteDir()
    }

    def 'dry run proposes both fixes without changing the source'() {
        given:
        String before = source.getText('UTF-8')
        List<NullableAnnotator.FixFinding> findings = findings()

        when:
        NullableAnnotator.Result result = NullableAnnotator.runFromFindings(
                [new File(dir, 'Annotations.groovy'), source], findings,
                NullableAnnotator.DEFAULT_ANNOTATION, true, new PrintStream(new ByteArrayOutputStream()))

        then:
        result.added == 2
        source.getText('UTF-8') == before
    }

    def 'apply adds the method and guarded parameter annotations'() {
        given:
        List<NullableAnnotator.FixFinding> findings = findings()

        when:
        NullableAnnotator.Result result = NullableAnnotator.runFromFindings(
                [new File(dir, 'Annotations.groovy'), source], findings,
                NullableAnnotator.DEFAULT_ANNOTATION, false, new PrintStream(new ByteArrayOutputStream()))
        String migrated = source.getText('UTF-8')

        then:
        result.added == 2
        migrated.contains('@Nullable String badReturn()')
        migrated.contains('void consume(@Nullable String value)')
    }

    def 'does not add @Nullable to an unguarded parameter'() {
        given:
        File unguarded = write('Unguarded.groovy', '''package demo

@NullMarked
class Unguarded {
    void consume(String value) {
        value.length()
    }

    void caller() {
        consume(nullable())
    }

    static String nullable() {
        return null
    }
}
''')
        String before = unguarded.getText('UTF-8')
        NullCheckHarness.Result check = NullCheckHarness.check(
                [new File(dir, 'Annotations.groovy'), unguarded],
                'io.github.heyuch.groovy.nullchecker.NullChecker', true)
        List<NullableAnnotator.FixFinding> findings = check.findings.collect { NullCheckHarness.Finding f ->
            new NullableAnnotator.FixFinding(path: f.path, line: f.line,
                    category: f.category.label, message: f.message)
        }

        when:
        NullableAnnotator.Result result = NullableAnnotator.runFromFindings(
                [new File(dir, 'Annotations.groovy'), unguarded], findings,
                NullableAnnotator.DEFAULT_ANNOTATION, false, new PrintStream(new ByteArrayOutputStream()))

        then:
        result.added == 0
        unguarded.getText('UTF-8') == before
    }

    private List<NullableAnnotator.FixFinding> findings() {
        NullCheckHarness.Result result = NullCheckHarness.check(
                [new File(dir, 'Annotations.groovy'), source],
                'io.github.heyuch.groovy.nullchecker.NullChecker', true)
        return result.findings.collect { NullCheckHarness.Finding finding ->
            new NullableAnnotator.FixFinding(path: finding.path, line: finding.line,
                    category: finding.category.label, message: finding.message)
        }
    }

    private File write(String name, String content) {
        File file = new File(dir, name)
        file.parentFile.mkdirs()
        file.setText(content, 'UTF-8')
        return file
    }
}
