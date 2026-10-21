package io.github.heyuch.groovy.nullchecker

import static io.github.heyuch.groovy.nullchecker.AstSupport.*
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveType
import static org.codehaus.groovy.syntax.Types.isAssignment

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement

/**
 * The definite-initialisation rule: an explicitly non-null field must be assigned by the
 * declaration, an initialiser block, or every non-delegating constructor.
 *
 * <p>One instance per call; it reads package scopes and reports through the reporter, so it
 * does not depend on the checker.
 */
@CompileStatic
@PackageScope
class InitializationCheck {

    private final PackageScopes scopes

    private final NullCheckReporter reporter

    InitializationCheck(PackageScopes scopes, NullCheckReporter reporter) {
        this.scopes = scopes
        this.reporter = reporter
    }

    /** Packages whose constraints imply non-null but not "assigned in source". */
    private static final Set<String> VALIDATION_ANNO_PACKAGES = Collections.unmodifiableSet([
            'jakarta.validation.constraints',
            'javax.validation.constraints',
            'org.hibernate.validator.constraints'] as Set<String>)

    /**
     * Checks that each explicitly-annotated {@code @NonNull} instance field is definitely
     * initialized: at its declaration, in an instance initializer block, or by every
     * declared constructor (a constructor delegating via {@code this(...)} relies on its
     * delegate). Only performed in {@code strict} mode. Fields that are merely non-null
     * by default (e.g. under {@code @NullMarked} without an explicit annotation) are not
     * checked, so idiomatic Groovy property classes constructed via named arguments stay
     * noise-free. Assignment detection is not path-sensitive: an assignment anywhere in
     * a constructor counts.
     */
    void check(ClassNode cn) {
        if (cn.interface) {
            return
        }
        // A Bean Validation constraint promises the value is not null *when validation
        // runs*; it does not promise the constructor assigned it, and beans populated by an
        // ORM or a deserializer legitimately start empty. A @NullMarked class is different:
        // its marking is a language-level contract, so the requirement stands there.
        boolean markedScope = scopes.isFieldNonNullByDefault(cn)
        List<FieldNode> candidates = cn.fields.findAll { FieldNode field ->
            !field.static && !isPrimitiveType(field.type) && field.initialValueExpression == null &&
                    isNonNullAnnotated(field) && !hasNullableAnno(field) && !hasMonotonicAnno(field) &&
                    !(isValidationConstraint(field) && !markedScope)
        }
        if (!candidates) {
            return
        }
        Set<String> initBlockAssigned = assignedFieldNames(cn.objectInitializerStatements)
        List<ConstructorNode> ctors = cn.declaredConstructors
        List<Set<String>> ctorAssigned = ctors.findAll { ConstructorNode ctor -> !delegatesToThis(ctor) }
                .collect { ConstructorNode ctor -> assignedFieldNames([ctor.code]) }
        for (FieldNode field : candidates) {
            if (field.name in initBlockAssigned) {
                continue
            }
            if (!ctors) {
                reporter.report("@NonNull field '${field.name}' is not initialized", field)
            } else if (ctorAssigned.any { Set<String> assigned -> !(field.name in assigned) }) {
                reporter.report("@NonNull field '${field.name}' is not initialized by all constructors", field)
            }
        }
    }

    boolean delegatesToThis(ConstructorNode ctor) {
        Statement first = ctor.firstStatement
        return first instanceof ExpressionStatement
                && ((ExpressionStatement) first).expression instanceof ConstructorCallExpression
                && ((ConstructorCallExpression) ((ExpressionStatement) first).expression).thisCall
    }

    /**
     * Collects the names of fields assigned (by simple name or via {@code this.name})
     * anywhere within the given statements.
     */
    Set<String> assignedFieldNames(List<Statement> statements) {
        Set<String> names = new HashSet<String>()
        CodeVisitorSupport collector = new CodeVisitorSupport() {

            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                if (isAssignment(expression.operation.type)) {
                    Expression left = expression.leftExpression
                    if (left instanceof VariableExpression) {
                        names.add(((VariableExpression) left).name)
                    } else if (left instanceof PropertyExpression
                            && ((PropertyExpression) left).objectExpression instanceof VariableExpression
                            && ((VariableExpression) ((PropertyExpression) left).objectExpression).thisExpression) {
                        String name = ((PropertyExpression) left).propertyAsString
                        if (name != null) {
                            names.add(name)
                        }
                    }
                }
                super.visitBinaryExpression(expression)
            }

        }
        for (Statement statement : statements) {
            if (statement != null) {
                statement.visit(collector)
            }
        }
        return names
    }

    /**
     * Whether a member counts as non-null: explicitly annotated, or inheriting a
     * package/class-level {@code @NullMarked} default.
     *
     * <p>The definite-initialisation check needs this rather than
     * {@link AstSupport#hasNonNullAnno} alone, otherwise a package marked {@code @NullMarked}
     * would demand non-null returns and parameters but silently not require its fields
     * to be initialised -- the same marking meaning two different things depending on
     * which check happened to look.
     */
    boolean isNonNullAnnotated(AnnotatedNode node) {
        if (hasNonNullAnno(node)) {
            return true
        }
        ClassNode owner = node instanceof FieldNode ? ((FieldNode) node).declaringClass : null
        return owner != null && scopes.isFieldNonNullByDefault(owner)
    }

    /**
     * Whether the node carries a Bean Validation constraint rather than a nullness
     * annotation. Matched by package, because the constraint's simple name is exactly
     * what the annotation lookup deliberately does not distinguish.
     */
    static boolean isValidationConstraint(AnnotatedNode node) {
        if (node == null) {
            return false
        }
        return node.annotations.any { AnnotationNode annotation ->
            annotation.classNode?.packageName in VALIDATION_ANNO_PACKAGES
        }
    }

}
