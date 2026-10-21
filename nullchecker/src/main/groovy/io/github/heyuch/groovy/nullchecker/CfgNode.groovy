package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.jspecify.annotations.Nullable

/** One statement-level CFG node. Entry and exit nodes have no statement. */
@CompileStatic
@PackageScope
class CfgNode {

    final int id

    final @Nullable Statement statement

    final @Nullable CatchStatement catchStatement

    final List<CfgEdge> outgoing = new ArrayList<CfgEdge>()

    final List<CfgEdge> incoming = new ArrayList<CfgEdge>()

    CfgNode(int id, @Nullable Statement statement) {
        this(id, statement, null)
    }

    CfgNode(int id, @Nullable Statement statement, @Nullable CatchStatement catchStatement) {
        this.id = id
        this.statement = statement
        this.catchStatement = catchStatement
    }

    void connect(CfgNode target, CfgEdgeKind kind) {
        CfgEdge edge = new CfgEdge(this, target, kind)
        outgoing.add(edge)
        target.incoming.add(edge)
    }

    boolean isEntry() {
        return statement == null && incoming.isEmpty()
    }

    boolean isExit() {
        return statement == null && outgoing.isEmpty()
    }

}
