package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * Guards declared by the caller.
 *
 * <p>A project's own "is this empty or null" helper is the natural guard, and no fixed list
 * can know its name. The polarity cannot be inferred either, so it is declared -- and that
 * makes a reversed declaration the interesting failure: it would hide a real finding rather
 * than add noise. Every case here is paired with one asserting the check still fires when
 * the declaration does not actually cover the call.
 */
class GuardConfigurationSpec extends Specification {

    static final String EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    static final String ANNOTATION = '''package fixtures

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE])
@interface Nullable {
}
'''

    /** The shape from a real project: a jar-provided helper guards a @Nullable result. */
    static final String GUARDED = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Helper {
    static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty()
    }
}

@CompileStatic
class Guarded {
    static Map<String, String> index(List<String> raw) {
        List<String> codes = load(raw)?.findAll { String value -> value != null }
        if (Helper.isNullOrEmpty(codes)) {
            return [:]
        }
        return [size: codes.size()]
    }

    static List<String> load(List<String> raw) {
        return null
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-guards', '')
        new File(dir, 'Nullable.groovy').setText(ANNOTATION, 'UTF-8')
        new File(dir, 'Guarded.groovy').setText(GUARDED, 'UTF-8')
        clearGuards()
    }

    def cleanup() {
        clearGuards()
        dir.deleteDir()
    }

    def 'without a declaration the helper guards nothing'() {
        when:
        int nulls = countNullFindings()

        then:
        nulls == 1
    }

    def 'declaring the helper as a false-polarity guard silences the finding'() {
        when:
        System.setProperty('groovy.nullchecker.guards.false', 'fixtures.Helper#isNullOrEmpty')
        int nulls = countNullFindings()

        then:
        nulls == 0
    }

    def 'declaring it with the opposite polarity must still report'() {
        when: 'the method says "non-null when true", but isNullOrEmpty means "non-null when false"'
        System.setProperty('groovy.nullchecker.guards.true', 'fixtures.Helper#isNullOrEmpty')
        int nulls = countNullFindings()

        then: 'the reversed declaration cannot be allowed to hide a real finding'
        nulls == 1
    }

    def 'a declaration naming another class does not match'() {
        when:
        System.setProperty('groovy.nullchecker.guards.false', 'fixtures.Other#isNullOrEmpty')
        int nulls = countNullFindings()

        then:
        nulls == 1
    }

    private int countNullFindings() {
        List<File> files = [new File(dir, 'Nullable.groovy'), new File(dir, 'Guarded.groovy')]
        NullCheckHarness.Result result = NullCheckHarness.check(files, EXTENSION, true)
        List<NullCheckHarness.Finding> nulls = NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label])
        nulls.size()
    }

    private static void clearGuards() {
        System.clearProperty('groovy.nullchecker.guards.true')
        System.clearProperty('groovy.nullchecker.guards.false')
    }
}
