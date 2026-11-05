package io.github.heyuch.groovy.nullchecker

import spock.lang.Specification

/** H1-4: one immutable config snapshot per compilation. */
class NullCheckConfigSpec extends Specification {

    def cleanup() {
        System.clearProperty(NullCheckConfig.CHECKS_PROPERTY)
        System.clearProperty(AstSupport.NONNULL_ANNOTATIONS_PROPERTY)
        System.clearProperty(NullCheckConfig.GUARDS_TRUE_PROPERTY)
        System.clearProperty(NullCheckConfig.GUARDS_FALSE_PROPERTY)
        System.clearProperty(NullCheckConfig.NEVER_RETURNS_PROPERTY)
        System.clearProperty(NullCheckConfig.CONTRACTS_PROPERTY)
        System.clearProperty(NullCheckConfig.ASSERTS_TRUE_PROPERTY)
        System.clearProperty(NullCheckConfig.CLASSPATH_PROPERTY)
    }

    def 'two snapshots retain the settings that were active when they were read'() {
        given:
        System.setProperty(NullCheckConfig.GUARDS_TRUE_PROPERTY, 'demo.Helper#first')
        NullCheckConfig first = NullCheckConfig.read([:])
        System.setProperty(NullCheckConfig.GUARDS_TRUE_PROPERTY, 'demo.Helper#second')

        when:
        NullCheckConfig second = NullCheckConfig.read([:])

        then:
        first.guardsTrue == ['demo.Helper#first'] as Set<String>
        second.guardsTrue == ['demo.Helper#second'] as Set<String>
    }

    def 'reads all collaboration settings into one immutable snapshot'() {
        given:
        System.setProperty(AstSupport.NONNULL_ANNOTATIONS_PROPERTY, 'com.example.CustomNonNull')
        System.setProperty(NullCheckConfig.GUARDS_TRUE_PROPERTY, 'demo.Helper#isReady')
        System.setProperty(NullCheckConfig.GUARDS_FALSE_PROPERTY, 'demo.Helper#isNull')
        System.setProperty(NullCheckConfig.NEVER_RETURNS_PROPERTY, 'demo.Helper#fail')
        System.setProperty(NullCheckConfig.ASSERTS_TRUE_PROPERTY, 'demo.Helper#assertTrue')
        System.setProperty(NullCheckConfig.CONTRACTS_PROPERTY, 'demo.Helper#isNull=null -> false')
        System.setProperty(NullCheckConfig.CLASSPATH_PROPERTY, '/tmp/demo.jar')

        when:
        NullCheckConfig config = NullCheckConfig.read([:])

        then:
        config.nonNullAnnotations.contains('CustomNonNull')
        config.guardsTrue == ['demo.Helper#isReady'] as Set<String>
        config.guardsFalse == ['demo.Helper#isNull'] as Set<String>
        config.neverReturns == ['demo.Helper#fail'] as Set<String>
        config.assertsTrue == ['demo.Helper#assertTrue'] as Set<String>
        config.contracts['demo.Helper#isNull'] == 'null -> false'
        config.classpath == '/tmp/demo.jar'
    }
}
