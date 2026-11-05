package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilationUnit

/** Minimal, non-final extension point for report-driven source fixes. */
@CompileStatic
interface NullFixProvider {

    List<NullFixProposal> propose(NullFixContext context)

}

/** Read-only view of one finding and the parsed file it belongs to. */
@CompileStatic
class NullFixContext {

    final File file
    final NullableAnnotator.FixFinding finding
    final CompilationUnit unit
    final List<NullableAnnotator.MethodRef> methods

    NullFixContext(File file, NullableAnnotator.FixFinding finding,
                   CompilationUnit unit, List<NullableAnnotator.MethodRef> methods) {
        this.file = file
        this.finding = finding
        this.unit = unit
        this.methods = methods
    }

}

/** A provider proposes declarations, not source text or side effects. */
@CompileStatic
class NullFixProposal {

    String kind = ''
    String name = ''
    int line
    String description = ''

}
