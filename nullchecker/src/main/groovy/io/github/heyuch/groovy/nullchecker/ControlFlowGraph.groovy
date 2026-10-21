package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement

/**
 * Statement-level control-flow graph. It is deliberately structural: expression state and
 * reporting are supplied by the flow transfer, not by this builder.
 */
@CompileStatic
@PackageScope
class ControlFlowGraph {

    final CfgNode entry

    final CfgNode exit

    final List<CfgNode> nodes = new ArrayList<CfgNode>()

    private int nextId

    private ControlFlowGraph(Statement body) {
        entry = newNode(null)
        exit = newNode(null)
        CfgNode first = buildStatement(body, exit, exit, entry)
        entry.connect(first ?: exit, CfgEdgeKind.NORMAL)
    }

    static ControlFlowGraph build(Statement body) {
        return new ControlFlowGraph(body)
    }

    private CfgNode newNode(Statement statement) {
        CfgNode node = new CfgNode(nextId++, statement)
        nodes.add(node)
        return node
    }

    private CfgNode buildStatement(Statement statement, CfgNode after,
                                   CfgNode breakTarget, CfgNode continueTarget) {
        if (statement == null) {
            return after
        }
        if (statement instanceof BlockStatement) {
            return buildBlock(statement, after, breakTarget, continueTarget)
        }
        if (statement instanceof DoWhileStatement) {
            return buildDoWhile(statement, after)
        }
        CfgNode node = newNode(statement)
        if (statement instanceof IfStatement) {
            IfStatement ifStatement = (IfStatement) statement
            CfgNode thenEntry = buildStatement(ifStatement.ifBlock, after, breakTarget, continueTarget)
            CfgNode elseEntry = buildStatement(ifStatement.elseBlock, after, breakTarget, continueTarget)
            node.connect(thenEntry ?: after, CfgEdgeKind.TRUE)
            node.connect(elseEntry ?: after, CfgEdgeKind.FALSE)
        } else if (statement instanceof WhileStatement) {
            WhileStatement loop = (WhileStatement) statement
            CfgNode body = buildStatement(loop.loopBlock, node, after, node)
            node.connect(body ?: node, CfgEdgeKind.TRUE)
            node.connect(after, CfgEdgeKind.FALSE)
        } else if (statement instanceof ForStatement) {
            ForStatement loop = (ForStatement) statement
            CfgNode body = buildStatement(loop.loopBlock, node, after, node)
            node.connect(body ?: node, CfgEdgeKind.TRUE)
            node.connect(after, CfgEdgeKind.FALSE)
        } else if (statement instanceof SwitchStatement) {
            connectSwitch((SwitchStatement) statement, node, after, continueTarget)
        } else if (statement instanceof TryCatchStatement) {
            connectTry((TryCatchStatement) statement, node, after, breakTarget, continueTarget)
        } else if (statement instanceof SynchronizedStatement) {
            SynchronizedStatement sync = (SynchronizedStatement) statement
            CfgNode body = buildStatement(sync.code, after, breakTarget, continueTarget)
            node.connect(body ?: after, CfgEdgeKind.NORMAL)
        } else if (statement instanceof ReturnStatement || statement instanceof ThrowStatement) {
            node.connect(exit, CfgEdgeKind.NORMAL)
        } else if (statement instanceof BreakStatement) {
            node.connect(breakTarget ?: exit, CfgEdgeKind.NORMAL)
        } else if (statement instanceof ContinueStatement) {
            node.connect(continueTarget ?: exit, CfgEdgeKind.NORMAL)
        } else if (statement instanceof AssertStatement) {
            node.connect(after, CfgEdgeKind.TRUE)
            node.connect(exit, CfgEdgeKind.EXCEPTION)
        } else {
            node.connect(after, CfgEdgeKind.NORMAL)
        }
        return node
    }

    private CfgNode buildBlock(BlockStatement block, CfgNode after,
                               CfgNode breakTarget, CfgNode continueTarget) {
        CfgNode node = newNode(block)
        CfgNode next = after
        for (int index = block.statements.size() - 1; index >= 0; index--) {
            next = buildStatement(block.statements.get(index), next, breakTarget, continueTarget)
        }
        node.connect(next ?: after, CfgEdgeKind.NORMAL)
        return node
    }

    private CfgNode buildDoWhile(DoWhileStatement loop, CfgNode after) {
        CfgNode condition = newNode(loop)
        CfgNode body = buildStatement(loop.loopBlock, condition, after, condition)
        condition.connect(body ?: condition, CfgEdgeKind.TRUE)
        condition.connect(after, CfgEdgeKind.FALSE)
        return body ?: condition
    }

    private void connectSwitch(SwitchStatement statement, CfgNode node, CfgNode after,
                              CfgNode continueTarget) {
        CfgNode next = after
        if (statement.defaultStatement != null) {
            next = buildStatement(statement.defaultStatement, after, after, continueTarget)
            node.connect(next ?: after, CfgEdgeKind.NORMAL)
        }
        List<CaseStatement> cases = statement.caseStatements
        for (int index = cases.size() - 1; index >= 0; index--) {
            CfgNode entry = buildStatement(cases.get(index).code, next, after, continueTarget)
            node.connect(entry ?: next, CfgEdgeKind.NORMAL)
            next = entry ?: next
        }
        if (cases.isEmpty() && statement.defaultStatement == null) {
            node.connect(after, CfgEdgeKind.FALSE)
        }
    }

    private void connectTry(TryCatchStatement statement, CfgNode node, CfgNode after,
                         CfgNode breakTarget, CfgNode continueTarget) {
        CfgNode finallyEntry = statement.finallyStatement == null ? after
                : buildStatement(statement.finallyStatement, after, breakTarget, continueTarget)
        CfgNode tryEntry = buildStatement(statement.tryStatement, finallyEntry, breakTarget, continueTarget)
        node.connect(tryEntry ?: finallyEntry, CfgEdgeKind.NORMAL)
        for (CatchStatement catchStatement : statement.catchStatements) {
            CfgNode catchEntry = newNode(catchStatement.code, catchStatement)
            CfgNode catchBody = buildStatement(catchStatement.code, finallyEntry, breakTarget, continueTarget)
            catchEntry.connect(catchBody ?: finallyEntry, CfgEdgeKind.NORMAL)
            node.connect(catchEntry, CfgEdgeKind.EXCEPTION)
        }
    }

    private CfgNode newNode(Statement statement, CatchStatement catchStatement) {
        CfgNode node = new CfgNode(nextId++, statement, catchStatement)
        nodes.add(node)
        return node
    }

}
