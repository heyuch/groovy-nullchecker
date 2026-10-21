package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ASTNode

/**
 * Reports findings and contains failures of the checker itself.
 *
 * <p>An exception thrown from inside a type-checking extension does not reach the caller:
 * Groovy catches it per class, so the compilation completes and the tool reports whatever it
 * collected -- typically nothing. Every unit of work therefore runs through {@link #guard},
 * which records the failure instead of letting it become silence; the harness turns a recorded
 * failure into a hard error.
 *
 * <p>The reporting function is supplied by the coordinator, so this class does not depend on
 * the DSL and the visitor can be given a reporter instead of the checker.
 */
@CompileStatic
@PackageScope
class NullCheckReporter {

    private static final ThreadLocal<List<String>> INTERNAL_ERRORS = new ThreadLocal<List<String>>()

    private static final int MAX_INTERNAL_ERRORS = 10

    private final Closure reportFunction

    NullCheckReporter(Closure reportFunction) {
        this.reportFunction = reportFunction
    }

    void report(CharSequence message, ASTNode node) {
        reportFunction.call(message, node)
    }

    void guard(String where, Closure body) {
        try {
            body.call()
        } catch (Throwable failure) {
            recordInternalError(where, failure)
        }
    }

    static List<String> getInternalErrors() {
        List<String> errors = INTERNAL_ERRORS.get()
        if (errors == null) {
            return new ArrayList<String>()
        }
        return new ArrayList<String>(errors)
    }

    static void resetInternalErrors() {
        INTERNAL_ERRORS.set(new ArrayList<String>())
    }

    private static void recordInternalError(String where, Throwable failure) {
        List<String> errors = INTERNAL_ERRORS.get()
        if (errors == null) {
            errors = new ArrayList<String>()
            INTERNAL_ERRORS.set(errors)
        }
        if (errors.size() < MAX_INTERNAL_ERRORS) {
            errors.add(where + ': ' + failure.getClass().name + ': ' + failure.message)
        }
        // Compile mode runs the reporter in a compiler worker, while the Gradle report runs in
        // another JVM; a static list cannot cross that boundary. The sidecar keeps a checker
        // bug from looking like a clean result.
        String findingsFile = System.getProperty('groovy.nullchecker.findingsFile')
        if (findingsFile) {
            File errorsFile = new File(findingsFile + '.errors')
            errorsFile.getParentFile()?.mkdirs()
            errorsFile.append("${where}: ${failure.getClass().name}: ${failure.message}\n".toString(), 'UTF-8')
        }
    }
}
