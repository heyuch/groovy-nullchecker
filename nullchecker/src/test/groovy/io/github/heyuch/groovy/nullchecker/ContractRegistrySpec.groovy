package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * The contract registry: one model, three sources, configuration first.
 *
 * <p>Configuration must be able to override both an annotation and a built-in entry, because
 * that is the only way to correct a library whose declaration is wrong. A malformed entry is
 * ignored rather than guessed at, so a typo cannot silently narrow something.
 */
class ContractRegistrySpec extends Specification {

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

    static final String SPRING_CONTRACT = '''package org.springframework.lang

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

    static final String SPRING_GUARD = '''package fixtures

import groovy.transform.CompileStatic
import org.springframework.lang.Contract

@CompileStatic
class SpringGuard {
    @Contract('null -> false')
    static boolean isNotEmpty(List<String> values) {
        return values != null && !values.isEmpty()
    }

    static int size(@Nullable List<String> values) {
        if (isNotEmpty(values)) {
            return values.size()
        }
        return 0
    }
}
'''

    static final String HELPER = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Helper {
    @Contract('null -> false')
    static boolean isNotEmpty(List<String> values) {
        return values != null && !values.isEmpty()
    }
}
'''

    static final String PLAIN = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Plain {
    static void giveUp() {
        throw new IllegalStateException('give up')
    }
}
'''

    static final String MAP_USE = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class MapUse {
    static int length(Map<String, String> values) {
        return values.get('k').length()
    }
}
'''

    static final String GUARD_USE = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class GuardUse {
    static int size(@Nullable List<String> values) {
        if (Helper.isNotEmpty(values)) {
            return values.size()
        }
        return 0
    }
}
'''

    static final String PLAIN_GUARD_USE = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class PlainGuardUse {
    static int length(@Nullable String value) {
        if (value == null) {
            Plain.giveUp()
        }
        return value.length()
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-contracts', '')
        write('Nullable.groovy', NULLABLE)
        write('Contract.groovy', CONTRACT)
        write('org/springframework/lang/Contract.groovy', SPRING_CONTRACT)
        write('SpringGuard.groovy', SPRING_GUARD)
        write('Helper.groovy', HELPER)
        write('Plain.groovy', PLAIN)
        write('MapUse.groovy', MAP_USE)
        write('GuardUse.groovy', GUARD_USE)
        write('PlainGuardUse.groovy', PLAIN_GUARD_USE)
        System.clearProperty('groovy.nullchecker.contracts')
    }

    def cleanup() {
        System.clearProperty('groovy.nullchecker.contracts')
        dir.deleteDir()
    }

    def 'an annotation contract narrows by itself'() {
        expect:
        countNulls(check('GuardUse.groovy')) == 0
    }

    def 'a Spring Contract annotation is recognised by its simple name'() {
        expect:
        countNulls(check('SpringGuard.groovy')) == 0
    }

    def 'a configured contract overrides the annotation'() {
        given:
        System.setProperty('groovy.nullchecker.contracts', 'fixtures.Helper#isNotEmpty=null -> true')

        expect: 'the configured polarity is the one applied, so the guarded branch is not narrowed'
        countNulls(check('GuardUse.groovy')) == 1
    }

    def 'a configured contract overrides a built-in entry'() {
        given:
        System.setProperty('groovy.nullchecker.contracts', 'java.util.Map#get=-> !null')

        expect: 'a project that knows the key is present can say so'
        countNulls(check('MapUse.groovy')) == 0
    }

    def 'a configured contract works for a method without annotations'() {
        given:
        System.setProperty('groovy.nullchecker.contracts', 'fixtures.Plain#giveUp=-> fail')

        expect:
        countNulls(check('PlainGuardUse.groovy')) == 0
    }

    def 'a malformed contract is ignored, not guessed at'() {
        given:
        System.setProperty('groovy.nullchecker.contracts', 'fixtures.Plain#giveUp=something else')

        expect: 'the built-in rule still reports the map lookup'
        countNulls(check('MapUse.groovy')) == 1
    }

    private NullCheckHarness.Result check(String source) {
        NullCheckHarness.check([new File(dir, 'Nullable.groovy'),
                                new File(dir, 'Contract.groovy'),
                                new File(dir, 'org/springframework/lang/Contract.groovy'),
                                new File(dir, 'SpringGuard.groovy'),
                                new File(dir, 'Helper.groovy'),
                                new File(dir, 'Plain.groovy'),
                                new File(dir, source)], EXTENSION, true)
    }

    private static int countNulls(NullCheckHarness.Result result) {
        List<NullCheckHarness.Finding> nulls = NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label])
        nulls.size()
    }

    private void write(String name, String content) {
        File file = new File(dir, name)
        file.parentFile.mkdirs()
        file.setText(content, 'UTF-8')
    }
}
