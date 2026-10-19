package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * Guards declared by the caller.
 *
 * <p>A project's own "is this empty or null" helper is the natural guard, and no fixed list
 * can know its name. The polarity cannot be inferred either, so it is declared -- and that
 * makes a reversed declaration the interesting failure: it would hide a real finding rather
 * than add noise. Every case that silences something is paired with one asserting the check
 * still fires when the declaration does not actually cover the call.
 *
 * <p>Each fixture is a separate source file so a case compiles only the classes it means to
 * measure; sharing one compilation would mix their findings together.
 */
class GuardConfigurationSpec extends Specification {

    static final String EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    static final String NULLABLE = '''package fixtures

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

    static final String HELPER = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Helper {
    static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty()
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty()
    }
}
'''

    /** The straight-line shape: guard, then dereference. */
    static final String GUARDED = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Guarded {
    static Map<String, Integer> index(List<String> raw) {
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

    /**
     * The loop shape: `if (isBlank(x)) continue` then a dereference.
     *
     * <p>This is the shape a real codebase produced. It needs two things at once -- continue
     * counting as a path exit, and the helper being recognised -- so it pins their
     * interaction rather than either one alone.
     */
    static final String LOOP_GUARDED = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class LoopGuarded {
    static String firstChar(List<String> texts) {
        for (String text : texts) {
            String body = text?.trim()
            if (Helper.isBlank(body)) {
                continue
            }
            return body.charAt(0) as String
        }
        return ''
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-guards', '')
        write('Nullable.groovy', NULLABLE)
        write('Helper.groovy', HELPER)
        write('Guarded.groovy', GUARDED)
        write('LoopGuarded.groovy', LOOP_GUARDED)
        clearGuards()
    }

    def cleanup() {
        clearGuards()
        dir.deleteDir()
    }

    def 'without a declaration the helper guards nothing'() {
        expect:
        countNulls(check('Guarded.groovy')) == 1
    }

    def 'declaring the helper as a false-polarity guard silences the finding'() {
        given:
        System.setProperty('groovy.nullchecker.guards.false', 'fixtures.Helper#isNullOrEmpty')

        expect:
        countNulls(check('Guarded.groovy')) == 0
    }

    def 'declaring it with the opposite polarity must still report'() {
        given: 'the method says "non-null when true", but isNullOrEmpty means "non-null when false"'
        System.setProperty('groovy.nullchecker.guards.true', 'fixtures.Helper#isNullOrEmpty')

        expect: 'the reversed declaration cannot be allowed to hide a real finding'
        countNulls(check('Guarded.groovy')) == 1
    }

    def 'a declaration naming another class does not match'() {
        given:
        System.setProperty('groovy.nullchecker.guards.false', 'fixtures.Other#isNullOrEmpty')

        expect:
        countNulls(check('Guarded.groovy')) == 1
    }

    def 'a declared guard also narrows across a continue'() {
        given:
        System.setProperty('groovy.nullchecker.guards.false', 'fixtures.Helper#isBlank')

        expect:
        countNulls(check('LoopGuarded.groovy')) == 0
    }

    def 'without the declaration the same loop still reports'() {
        expect: 'continue narrows, but an unrecognised helper is still just a method call'
        countNulls(check('LoopGuarded.groovy')) == 1
    }

    private NullCheckHarness.Result check(String source) {
        NullCheckHarness.check([new File(dir, 'Nullable.groovy'),
                                new File(dir, 'Helper.groovy'),
                                new File(dir, source)], EXTENSION, true)
    }

    private static int countNulls(NullCheckHarness.Result result) {
        List<NullCheckHarness.Finding> nulls = NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label])
        nulls.size()
    }

    private void write(String name, String content) {
        new File(dir, name).setText(content, 'UTF-8')
    }

    private static void clearGuards() {
        System.clearProperty('groovy.nullchecker.guards.true')
        System.clearProperty('groovy.nullchecker.guards.false')
    }
}
