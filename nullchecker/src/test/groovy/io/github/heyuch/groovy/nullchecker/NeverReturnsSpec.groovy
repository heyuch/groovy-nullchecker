package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * Methods declared to never return.
 *
 * <p>Two declaration channels are pinned here: a neutral annotation whose simple name the
 * checker recognises, and the caller's {@code neverReturns} list, which needs no change to the
 * library. Both must narrow a null check the way a {@code throw} does, and a declaration that
 * names another class must not.
 */
class NeverReturnsSpec extends Specification {

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

    static final String CONTRACT = '''package fixtures

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD])
@interface Contract {
    String value()
}
'''

    static final String PANIC = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Panic {
    @Contract('-> fail')
    static void abort() {
        throw new IllegalStateException('abort')
    }

    static void giveUp() {
        throw new IllegalStateException('give up')
    }
}
'''

    static final String GUARDED = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Guarded {
    static int length(@Nullable String value) {
        if (value == null) {
            Panic.giveUp()
        }
        return value.length()
    }
}
'''

    static final String ANNOTATED = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Annotated {
    static int length(@Nullable String value) {
        if (value == null) {
            Panic.abort()
        }
        return value.length()
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-never', '')
        write('Nullable.groovy', NULLABLE)
        write('Contract.groovy', CONTRACT)
        write('Panic.groovy', PANIC)
        write('Guarded.groovy', GUARDED)
        write('Annotated.groovy', ANNOTATED)
        System.clearProperty('groovy.nullchecker.neverreturns')
    }

    def cleanup() {
        System.clearProperty('groovy.nullchecker.neverreturns')
        dir.deleteDir()
    }

    def 'without a declaration an always-throwing helper does not narrow'() {
        expect:
        countNulls(check('Guarded.groovy')) == 1
    }

    def 'the caller can declare the method in neverReturns'() {
        given:
        System.setProperty('groovy.nullchecker.neverreturns', 'fixtures.Panic#giveUp')

        expect:
        countNulls(check('Guarded.groovy')) == 0
    }

    def 'a declaration naming another class does not match'() {
        given:
        System.setProperty('groovy.nullchecker.neverreturns', 'fixtures.Other#giveUp')

        expect:
        countNulls(check('Guarded.groovy')) == 1
    }

    def 'a contract with no arguments and result fail means it always throws'() {
        expect: 'an empty argument list is valid @Contract syntax, and means any call fails'
        countNulls(check('Annotated.groovy')) == 0
    }

    private NullCheckHarness.Result check(String source) {
        NullCheckHarness.check([new File(dir, 'Nullable.groovy'),
                                new File(dir, 'Contract.groovy'),
                                new File(dir, 'Panic.groovy'),
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
}
