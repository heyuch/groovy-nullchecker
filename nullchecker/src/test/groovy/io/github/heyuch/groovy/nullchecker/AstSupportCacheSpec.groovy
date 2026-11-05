package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import spock.lang.Specification

/** H1-1: annotation caches must not survive a compilation. */
class AstSupportCacheSpec extends Specification {

    private static final String SOURCE = '''package demo

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE])
@interface Nullable {
}

class Sample {
    @Nullable
    String value

    String read() {
        return value
    }
}
'''

    def cleanup() {
        AstSupport.resetCaches()
        NullChecker.resetCaches()
        NullChecker.resetInternalErrors()
        NullCheckSinks.clear()
    }

    def 'annotation caches are empty after each compilation'() {
        when:
        compile()
        then:
        AstSupport.DECL_ANNO_NAMES.isEmpty()
        AstSupport.TYPE_ANNO_NAMES.isEmpty()

        when:
        compile()
        then:
        AstSupport.DECL_ANNO_NAMES.isEmpty()
        AstSupport.TYPE_ANNO_NAMES.isEmpty()
    }

    private static void compile() {
        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new NullCheckCustomizer())
        CompilationUnit unit = new CompilationUnit(configuration)
        unit.addSource('Sample.groovy', SOURCE)
        unit.compile(Phases.OUTPUT)
    }
}
