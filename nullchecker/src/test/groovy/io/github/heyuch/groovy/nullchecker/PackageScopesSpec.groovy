package io.github.heyuch.groovy.nullchecker

import spock.lang.Specification

/** H2-1: package-info fallback must be visible as an incomplete run. */
class PackageScopesSpec extends Specification {

    def setup() {
        NullCheckReporter.resetInternalErrors()
    }

    def cleanup() {
        NullCheckReporter.resetInternalErrors()
    }

    def 'a package-info lookup failure is recorded as a diagnostic'() {
        given:
        ClassLoader broken = new ClassLoader() {

            @Override
            Class<?> loadClass(String name) throws ClassNotFoundException {
                throw new LinkageError('synthetic package-info failure')
            }

        }
        PackageScopes scopes = new PackageScopes(null, broken)

        when:
        scopes.lookupPackageAnnotations('demo.missing')

        then:
        NullCheckReporter.internalErrors.any { String error ->
            error.contains('package-info lookup failed')
        }
    }
}
