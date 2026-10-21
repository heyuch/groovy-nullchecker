package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.jspecify.annotations.Nullable

/**
 * Strict-mode transfer: evaluates one CFG node against the visitor's expression rules and
 * refines condition edges with {@link GuardFacts}.
 */
@CompileStatic
@PackageScope
class NullnessFlowTransfer extends FlowTransfer {

    private final NullCheckVisitor visitor

    private final Map<CfgNode, GuardFacts> conditions = new LinkedHashMap<CfgNode, GuardFacts>()

    NullnessFlowTransfer(NullCheckVisitor visitor) {
        this.visitor = visitor
    }

    @Override
    @Nullable FlowState transfer(CfgNode node, FlowState input) {
        visitor.restoreState(input)
        Statement statement = node.statement
        if (statement == null || statement instanceof BlockStatement ||
                statement instanceof SynchronizedStatement || statement instanceof TryCatchStatement ||
                statement instanceof BreakStatement || statement instanceof ContinueStatement) {
            return visitor.captureState()
        }
        if (statement instanceof IfStatement) {
            IfStatement ifStatement = (IfStatement) statement
            conditions.put(node, visitor.visitCondition(ifStatement.booleanExpression.expression))
        } else if (statement instanceof WhileStatement) {
            WhileStatement loop = (WhileStatement) statement
            conditions.put(node, visitor.visitCondition(loop.booleanExpression.expression))
        } else if (statement instanceof DoWhileStatement) {
            DoWhileStatement loop = (DoWhileStatement) statement
            conditions.put(node, visitor.visitCondition(loop.booleanExpression.expression))
        } else if (statement instanceof ForStatement) {
            ForStatement loop = (ForStatement) statement
            loop.collectionExpression.visit(visitor)
        } else if (statement instanceof SwitchStatement) {
            SwitchStatement switchStatement = (SwitchStatement) statement
            switchStatement.expression.visit(visitor)
        } else if (statement instanceof AssertStatement) {
            statement.visit(visitor)
        } else {
            statement.visit(visitor)
            if (statement instanceof ExpressionStatement) {
                ExpressionStatement expressionStatement = (ExpressionStatement) statement
                if (visitor.isNoReturnCall(expressionStatement.expression)) {
                    return null
                }
            }
        }
        return visitor.captureState()
    }

    @Override
    FlowState refine(CfgEdge edge, FlowState state) {
        GuardFacts facts = conditions.get(edge.source)
        if (facts == null) {
            return state
        }
        visitor.restoreState(state)
        if (edge.kind == CfgEdgeKind.TRUE) {
            visitor.applyFacts(facts.whenTrue)
            visitor.applyNonEmptyFacts(facts.nonEmptyTrue)
            visitor.applyLookupFacts(facts.lookupsTrue)
        } else if (edge.kind == CfgEdgeKind.FALSE) {
            visitor.applyFacts(facts.whenFalse)
            visitor.applyNonEmptyFacts(facts.nonEmptyFalse)
            visitor.applyLookupFacts(facts.lookupsFalse)
        }
        return visitor.captureState()
    }

}
