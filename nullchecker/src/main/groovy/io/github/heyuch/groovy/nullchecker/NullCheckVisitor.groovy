package io.github.heyuch.groovy.nullchecker

import static io.github.heyuch.groovy.nullchecker.AstSupport.canBeNull
import static io.github.heyuch.groovy.nullchecker.AstSupport.declAnnoCarrier
import static io.github.heyuch.groovy.nullchecker.AstSupport.getASSERTION_MESSAGE_TYPES
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasMonotonicAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNonNullAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNonNullByDefaultAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNonNullMapValue
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNullCheckAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNullableAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNullableElementType
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNullableTypeUse
import static io.github.heyuch.groovy.nullchecker.AstSupport.inferredNullableTypeUse
import static io.github.heyuch.groovy.nullchecker.AstSupport.isMapLike
import static io.github.heyuch.groovy.nullchecker.AstSupport.isNullExpr
import static io.github.heyuch.groovy.nullchecker.AstSupport.isSafeNavResult
import static io.github.heyuch.groovy.nullchecker.AstSupport.isSubscript
import static io.github.heyuch.groovy.nullchecker.AstSupport.lookupKey
import static io.github.heyuch.groovy.nullchecker.AstSupport.mapEntryTypes
import static io.github.heyuch.groovy.nullchecker.AstSupport.nullableProperty
import static io.github.heyuch.groovy.nullchecker.AstSupport.typeOfExpression
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveType
import static org.codehaus.groovy.syntax.Types.isAssignment

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCall
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.jspecify.annotations.Nullable

/**
 * Applies the nullness rules to one method body.
 *
 * <p>Extracted from the checker so the rules read as one class. It receives its collaborators
 * explicitly -- configuration, package scopes, the contract registry and the reporter -- and
 * holds only the per-method state (nullable locals, guards, element tracking).
 */
@CompileStatic
@PackageScope
class NullCheckVisitor extends CheckingVisitor {

    private final boolean flowSensitive

    private final MethodNode method

    private final boolean methodNonNull

    private final PackageScopes scopes

    private final ContractRegistry contracts

    private final NullCheckReporter reporter

    private final boolean containerLookups

    private final Set<Variable> nullableVars

    NullCheckVisitor(boolean flowSensitive, MethodNode method, boolean methodNonNull,
                     Set<Variable> initialNullable, PackageScopes scopes,
                     ContractRegistry contracts, NullCheckReporter reporter,
                     boolean containerLookups) {
        this.flowSensitive = flowSensitive
        this.method = method
        this.methodNonNull = methodNonNull
        this.scopes = scopes
        this.contracts = contracts
        this.reporter = reporter
        this.containerLookups = containerLookups
        this.nullableVars = new HashSet<Variable>(initialNullable)
    }

    private final Set<Variable> monotonicInitialized = new HashSet<>()
    private final Set<Variable> guardedVars = new HashSet<>()

    @Override
    void visitDeclarationExpression(DeclarationExpression decl) {
        super.visitDeclarationExpression(decl)
        VariableExpression ve = decl.variableExpression
        if (ve == null) {
            return
        }
        if (decl.rightExpression instanceof ConstantExpression) {
            localConstVars.put(ve, decl.rightExpression)
        }
        // PORT: locally declared annotations sit on a different node per Groovy
        // version (see declAnnoCarrier), so query both rather than only 've'.
        AnnotatedNode annotated = declAnnoCarrier(decl, ve)
        if (hasNonNullAnno(annotated) && isNullExpr(decl.rightExpression)) {
            reporter.report("Cannot assign null to @NonNull variable '${ve.name}'", decl)
        }
        // Uninitialized non-primitive declaration (e.g. "String x") is implicitly null
        boolean implicitlyNull = decl.rightExpression instanceof EmptyExpression && !isPrimitiveType(ve.type)
        if (hasNullableAnno(annotated) || isNullExpr(decl.rightExpression) || (flowSensitive && (implicitlyNull || canBeNull(decl.rightExpression) || isKnownNullable(decl.rightExpression)))) {
            nullableVars.add(ve)
        }
    }

    @Override
    void visitBinaryExpression(BinaryExpression expr) {
        int op = expr.operation.type
        if (op == Types.LOGICAL_AND || op == Types.LOGICAL_OR) {
            // apply short-circuit guard semantics wherever the expression appears
            visitCondition(expr)
            return
        }

        super.visitBinaryExpression(expr)

        Expression leftExpr = expr.leftExpression
        Expression rightExpr = expr.rightExpression

        if (isAssignment(expr.operation.type) && leftExpr instanceof VariableExpression) {
            Variable target = findTargetVariable((VariableExpression) leftExpr)
            boolean fieldNonNull = target instanceof AnnotatedNode && hasNonNullAnno((AnnotatedNode) target)
            if (!fieldNonNull && target instanceof FieldNode && !hasNullableAnno((AnnotatedNode) target)) {
                FieldNode field = (FieldNode) target
                fieldNonNull = field.declaringClass != null &&
                    scopes.isFieldNonNullByDefault(field.declaringClass)
            }
            if (fieldNonNull && isNullExpr(rightExpr)) {
                reporter.report("Cannot assign null to @NonNull variable '${leftExpr.name}'", expr)
            }

            // @MonotonicNonNull: once initialized with non-null, cannot assign null again
            if (target instanceof AnnotatedNode && hasMonotonicAnno((AnnotatedNode) target)) {
                if (!isNullExpr(rightExpr)) {
                    monotonicInitialized.add(target)
                } else if (monotonicInitialized.contains(target)) {
                    reporter.report("Cannot assign null to @MonotonicNonNull variable '${leftExpr.name}' after non-null assignment", expr)
                }
            }

            if (isNullExpr(rightExpr)) {
                nullableVars.add(target)
                guardedVars.remove(target)
            } else if (flowSensitive && (canBeNull(rightExpr) || isKnownNullable(rightExpr))) {
                nullableVars.add(target)
                guardedVars.remove(target)
            } else {
                nullableVars.remove(target)
            }
        }
    }

    @Override
    void visitMethodCallExpression(MethodCallExpression call) {
        // A closure passed to an iteration method receives the collection's element,
        // so when that element is nullable the closure's parameter is too. The hint is
        // pushed before visiting the arguments, because that is when the closure body
        // is walked.
        closureHints.add(closureHint(call))
        try {
            super.visitMethodCallExpression(call)
        } finally {
            closureHints.remove(closureHints.size() - 1)
        }
        if (!call.safe && !call.implicitThis) {
            checkDereference(call.objectExpression, call)
        }
        checkMethodArguments(call)
        applyCallNarrowing(call)
    }

    /**
     * Tracks the values an iteration hands out: a {@code for} loop variable, or a
     * closure parameter of an iteration method.
     *
     * <p>The annotation lives on the collection's type argument, which Groovy does not
     * copy onto the variable it hands out. Recording the variable is therefore the only
     * way to make the existing dereference rules see it, and it keeps the rules in one
     * place instead of adding a parallel check.
     *
     * <p>Iterating a map yields {@code Map.Entry}, so the key and the value are tracked
     * separately: the entry itself is never null, but either half may be.
     */
    private final Set<Variable> nullableElements = new HashSet<>()

    private final Map<Variable, MapEntryTypes> entryVariables = new LinkedHashMap<>()

    /** What the call being visited passes to its closure; innermost last. */
    private final List<ClosureHint> closureHints = new ArrayList<>()

    /** What the closure body currently being visited receives; innermost last. */
    private final List<ClosureHint> closureContexts = new ArrayList<>()

    /** Iteration methods whose first closure parameter is the element. */
    private static final Set<String> ELEMENT_ITERATION_METHODS = [
            'each', 'collect', 'collectMany', 'collectEntries', 'find', 'findAll', 'findResult',
            'any', 'every', 'eachWithIndex', 'count', 'sum', 'groupBy', 'unique',
            'takeWhile', 'dropWhile', 'sort'] as Set<String>

    @Override
    void visitForLoop(ForStatement statement) {
        Parameter loopVar = statement.variable
        ClassNode collectionType = typeOfExpression(statement.collectionExpression)
        MapEntryTypes entry = mapEntryTypes(collectionType)
        boolean elementNullable = entry == null && hasNullableElementType(collectionType)
        if (loopVar != null && entry != null) {
            entryVariables.put(loopVar, entry)
        }
        if (loopVar != null && elementNullable) {
            nullableElements.add(loopVar)
        }
        try {
            super.visitForLoop(statement)
        } finally {
            if (loopVar != null) {
                entryVariables.remove(loopVar)
                nullableElements.remove(loopVar)
            }
        }
    }

    @Override
    void visitClosureExpression(ClosureExpression expression) {
        ClosureHint hint = currentHint(closureHints)
        Set<Variable> savedElements = new HashSet<>(nullableElements)
        Map<Variable, MapEntryTypes> savedEntries = new LinkedHashMap<>(entryVariables)
        Parameter[] parameters = expression.parameters
        int count = parameters == null ? 0 : parameters.length
        if (hint != null) {
            MapEntryTypes entryTypes = hint.entryTypes
            if (entryTypes != null) {
                if (hint.pairwise) {
                    // each / eachWithIndex hand out (key, value[, index])
                    if (count > 0 && hasNullableTypeUse(entryTypes.keyType)) {
                        nullableElements.add(parameters[0])
                    }
                    if (count > 1 && hasNullableTypeUse(entryTypes.valueType)) {
                        nullableElements.add(parameters[1])
                    }
                } else if (count > 0) {
                    // collect / find / findAll hand out the entry itself
                    entryVariables.put(parameters[0], entryTypes)
                }
            }
        } else if (hint != null && hint.elementNullable && count > 0) {
            nullableElements.add(parameters[0])
            // sort's comparator receives two elements, both of them elements
            if (count > 1) {
                nullableElements.add(parameters[1])
            }
        }
        closureContexts.add(hint)
        try {
            super.visitClosureExpression(expression)
        } finally {
            closureContexts.remove(closureContexts.size() - 1)
            nullableElements.clear()
            nullableElements.addAll(savedElements)
            entryVariables.clear()
            entryVariables.putAll(savedEntries)
        }
    }

    /** What the call hands to its closure, if anything. */
    private ClosureHint closureHint(MethodCallExpression call) {
        ClosureHint hint = new ClosureHint()
        ClassNode receiver = typeOfExpression(call.objectExpression)
        MapEntryTypes entry = mapEntryTypes(receiver)
        if (entry != null) {
            String name = call.methodAsString
            if (name in ['each', 'eachWithIndex']) {
                hint.entryTypes = entry
                hint.pairwise = true
            } else if (name in ELEMENT_ITERATION_METHODS) {
                hint.entryTypes = entry
            }
            return hint
        }
        if (call.methodAsString in ELEMENT_ITERATION_METHODS && hasNullableElementType(receiver)) {
            hint.elementNullable = true
        }
        return hint
    }

    private static @Nullable ClosureHint currentHint(List<ClosureHint> stack) {
        return stack.isEmpty() ? null : stack.last()
    }

    /** Whether a variable is an element of a collection whose element type is nullable. */
    private boolean isNullableElement(VariableExpression receiver, Variable target) {
        if (nullableElements.contains(target)) {
            return true
        }
        ClosureHint hint = currentHint(closureContexts)
        return receiver.name == 'it' && hint != null && hint.elementNullable
    }

    /** The declared type of {@code entry.key} / {@code entry.value}, when known. */
    private @Nullable ClassNode entryMemberType(Expression receiver, String member) {
        MapEntryTypes types = null
        if (receiver instanceof VariableExpression) {
            VariableExpression variable = (VariableExpression) receiver
            if (variable.name == 'it') {
                ClosureHint hint = currentHint(closureContexts)
                if (hint != null && hint.entryTypes != null && !hint.pairwise) {
                    types = hint.entryTypes
                }
            } else {
                types = entryVariables.get(findTargetVariable(variable))
            }
        }
        if (types == null) {
            return null
        }
        if (member == 'key') {
            return types.keyType
        }
        if (member == 'value') {
            return types.valueType
        }
        return null
    }

    private boolean isNullableEntryProperty(PropertyExpression expression) {
        return hasNullableTypeUse(entryMemberType(expression.objectExpression, expression.propertyAsString))
    }

    /** Whether a call is {@code entry.getKey()} / {@code entry.getValue()} on a nullable half. */
    private boolean isNullableEntryCall(Expression call) {
        if (!(call instanceof MethodCallExpression)) {
            return false
        }
        MethodCallExpression entryCall = (MethodCallExpression) call
        if (entryCall.methodAsString == 'getKey') {
            return hasNullableTypeUse(entryMemberType(entryCall.objectExpression, 'key'))
        }
        if (entryCall.methodAsString == 'getValue') {
            return hasNullableTypeUse(entryMemberType(entryCall.objectExpression, 'value'))
        }
        return false
    }

    @Override
    void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        super.visitStaticMethodCallExpression(call)
        checkMethodArguments(call)
        applyCallNarrowing(call)
    }

    @Override
    void visitPropertyExpression(PropertyExpression expression) {
        super.visitPropertyExpression(expression)
        if (!expression.safe) {
            checkDereference(expression.objectExpression, expression)
        }
    }

    @Override
    /**
     * Stops walking a block once a statement cannot complete normally: what follows is
     * unreachable, and reporting it would be reporting code that never runs.
     */
    @Override
    void visitBlockStatement(BlockStatement block) {
        for (Statement statement : block.statements) {
            statement.visit(this)
            if (cannotCompleteNormally(statement)) {
                return
            }
        }
    }

    void visitReturnStatement(ReturnStatement statement) {
        super.visitReturnStatement(statement)
        if (methodNonNull) {
            if (isNullExpr(statement.expression)) {
                reporter.report("Cannot return null from @NonNull method '${method.name}'", statement)
            } else if (isKnownNullable(statement.expression)) {
                reporter.report("Cannot return @Nullable value from @NonNull method '${method.name}'", statement)
            }
        }
    }

    @Override
    void visitIfElse(IfStatement ifElse) {
        GuardFacts facts = visitCondition(ifElse.booleanExpression.expression)
        withGuards(facts.whenTrue) {
            withLookupGuards(facts.lookupsTrue) { ifElse.ifBlock.visit(this) }
        }
        withGuards(facts.whenFalse) {
            withLookupGuards(facts.lookupsFalse) { ifElse.elseBlock.visit(this) }
        }
        // Early exit: if (x == null) return/throw → x is non-null after (and vice versa)
        if (isEarlyExit(ifElse.ifBlock)) {
            applyFacts(facts.whenFalse)
        }
        if (isEarlyExit(ifElse.elseBlock)) {
            applyFacts(facts.whenTrue)
        }
    }

    @Override
    void visitWhileLoop(WhileStatement loop) {
        GuardFacts facts = visitCondition(loop.booleanExpression.expression)
        withGuards(facts.whenTrue) {
            withLookupGuards(facts.lookupsTrue) { loop.loopBlock.visit(this) }
        }
    }

    @Override
    void visitTernaryExpression(TernaryExpression expression) {
        Expression condition = expression.booleanExpression.expression
        GuardFacts facts = visitCondition(condition)
        // for elvis, the true expression is the (already visited) condition
        if (!condition.is(expression.trueExpression) && !expression.booleanExpression.is(expression.trueExpression)) {
            withGuards(facts.whenTrue) {
                withLookupGuards(facts.lookupsTrue) { expression.trueExpression.visit(this) }
            }
        }
        withGuards(facts.whenFalse) {
            withLookupGuards(facts.lookupsFalse) { expression.falseExpression.visit(this) }
        }
    }

    @Override
    void visitAssertStatement(AssertStatement statement) {
        GuardFacts facts = visitCondition(statement.booleanExpression.expression)
        statement.messageExpression?.visit(this)
        // assert x/assert x != null → x is non-null for the rest of the block
        applyFacts(facts.whenTrue)
    }

    /**
     * Visits a condition applying short-circuit guard semantics: in
     * {@code x != null && x.foo()} the right operand is protected by the
     * left operand's null check (and similarly after {@code x == null ||}).
     * Returns the sets of variables known to be non-null when the condition
     * evaluates true and when it evaluates false.
     */
    private GuardFacts visitCondition(Expression condition) {
        return analyzeCondition(condition, true)
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<T>(left)
        result.addAll(right)
        return result
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<T>(left)
        result.retainAll(right)
        return result
    }

    /**
     * Analyzes a condition into guard facts, optionally visiting its nodes
     * along the way ({@code visiting: false} re-analyzes an already visited
     * condition, e.g. when judging ternary branch nullness).
     */
    private GuardFacts analyzeCondition(Expression condition, boolean visiting) {
        GuardFacts facts = new GuardFacts()
        if (condition instanceof NotExpression) {
            GuardFacts inner = analyzeCondition(condition.expression, visiting)
            facts.whenTrue = inner.whenFalse
            facts.whenFalse = inner.whenTrue
        } else if (condition instanceof BooleanExpression) {
            facts = analyzeCondition(condition.expression, visiting)
        } else if (condition instanceof BinaryExpression && condition.operation.type == Types.LOGICAL_AND) {
            GuardFacts left = analyzeCondition(condition.leftExpression, visiting)
            GuardFacts right = visiting
                ? withGuards(left.whenTrue) {
                    withLookupGuards(left.lookupsTrue) {
                        analyzeCondition(condition.rightExpression, true)
                    }
                }
                : analyzeCondition(condition.rightExpression, false)
            facts.whenTrue = union(left.whenTrue, right.whenTrue)
            facts.whenFalse = intersection(left.whenFalse, right.whenFalse)
            facts.lookupsTrue = union(left.lookupsTrue, right.lookupsTrue)
            facts.lookupsFalse = intersection(left.lookupsFalse, right.lookupsFalse)
        } else if (condition instanceof BinaryExpression && condition.operation.type == Types.LOGICAL_OR) {
            GuardFacts left = analyzeCondition(condition.leftExpression, visiting)
            GuardFacts right = visiting
                ? withGuards(left.whenFalse) {
                    withLookupGuards(left.lookupsFalse) {
                        analyzeCondition(condition.rightExpression, true)
                    }
                }
                : analyzeCondition(condition.rightExpression, false)
            facts.whenTrue = intersection(left.whenTrue, right.whenTrue)
            facts.whenFalse = union(left.whenFalse, right.whenFalse)
            facts.lookupsTrue = intersection(left.lookupsTrue, right.lookupsTrue)
            facts.lookupsFalse = union(left.lookupsFalse, right.lookupsFalse)
        } else {
            if (visiting) {
                condition.visit(this)
            }
            facts = analyzeLeafCondition(condition)
        }
        return facts
    }

    /**
     * Analyzes a leaf condition (null comparison, instanceof check, Groovy-truth
     * variable, or {@code Objects.nonNull/isNull} call) into guard facts.
     */
    private GuardFacts analyzeLeafCondition(Expression condition) {
        GuardFacts facts = new GuardFacts()
        if (condition instanceof BinaryExpression) {
            int op = condition.operation.type
            if (op == Types.KEYWORD_INSTANCEOF) {
                Variable var = guardableVariable(condition.leftExpression)
                if (var != null) {
                    facts.whenTrue.add(var)
                }
            } else if (op == Types.COMPARE_NOT_INSTANCEOF) {
                Variable var = guardableVariable(condition.leftExpression)
                if (var != null) {
                    facts.whenFalse.add(var)
                }
            } else {
                boolean isNotEqual = (op == Types.COMPARE_NOT_EQUAL || op == Types.COMPARE_NOT_IDENTICAL)
                boolean isEqual = (op == Types.COMPARE_EQUAL || op == Types.COMPARE_IDENTICAL)
                if (isNotEqual || isEqual) {
                    Variable var = null
                    if (isNullExpr(condition.rightExpression)) {
                        var = guardableVariable(condition.leftExpression)
                    } else if (isNullExpr(condition.leftExpression)) {
                        var = guardableVariable(condition.rightExpression)
                    }
                    if (var != null) {
                        (isNotEqual ? facts.whenTrue : facts.whenFalse).add(var)
                    }
                }
            }
        } else if (condition instanceof VariableExpression) {
            // Groovy truth: a truthy reference is non-null
            Variable var = guardableVariable(condition)
            if (var != null) {
                facts.whenTrue.add(var)
            }
        } else if (condition instanceof MethodCallExpression || condition instanceof StaticMethodCallExpression) {
            Object target = condition.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
            if (target instanceof MethodNode) {
                applyContractGuard(target, (MethodCall) condition, facts)
                if (condition instanceof MethodCallExpression) {
                    applyContainsKeyGuard((MethodCallExpression) condition, facts)
                }
            }
        }
        return facts
    }

    /**
     * Applies a contract to a condition: the arguments it proves non-null in one of the
     * two branches.
     *
     * <p>{@code @EnsuresNonNullIf}, JetBrains' {@code @Contract("null -> true|false")},
     * the project's {@code guardNotNullWhenTrue/False} lists and the built-in
     * {@code Objects.nonNull/isNull} all resolve to the same model, so they narrow
     * identically. References the contract cannot interpret are ignored rather than
     * guessed at.
     */
    private void applyContractGuard(MethodNode target, MethodCall condition, GuardFacts facts) {
        Expression callArgs = condition.arguments
        if (!(callArgs instanceof TupleExpression)) {
            return
        }
        TupleExpression args = (TupleExpression) callArgs
        if (!args.expressions) {
            return
        }
        Contract contract = contracts.contractFor(target)
        if (contract == null) {
            return
        }
        for (int index : contract.nonNullWhenTrue) {
            if (index >= args.expressions.size()) {
                continue
            }
            addArgumentFact(args.getExpression(index), true, facts)
        }
        for (int index : contract.nonNullWhenFalse) {
            if (index >= args.expressions.size()) {
                continue
            }
            addArgumentFact(args.getExpression(index), false, facts)
        }
    }

    private void addArgumentFact(Expression argument, boolean whenTrue, GuardFacts facts) {
        Variable var = guardableVariable(argument)
        if (var == null) {
            return
        }
        if (whenTrue) {
            facts.whenTrue.add(var)
        } else {
            facts.whenFalse.add(var)
        }
    }

    private @Nullable Variable guardableVariable(Expression expr) {
        if (expr instanceof VariableExpression && !expr.isThisExpression() && !expr.isSuperExpression()) {
            return findTargetVariable(expr)
        }
        return null
    }

    private <T> T withGuards(Set<Variable> vars, Closure<T> body) {
        if (!vars) {
            return body()
        }
        Set<Variable> saved = new HashSet<Variable>(guardedVars)
        guardedVars.addAll(vars)
        T result = body()
        guardedVars.clear()
        guardedVars.addAll(saved)
        return result
    }

    private void applyFacts(Set<Variable> vars) {
        for (Variable current : vars) {
            nullableVars.remove(current)
            guardedVars.add(current)
        }
    }

    /** Lookup facts proved by {@code containsKey}, scoped to the branch that proved them. */
    private final Deque<Set<String>> lookupGuards = new ArrayDeque<>()

    private <T> T withLookupGuards(Set<String> keys, Closure<T> body) {
        if (!keys) {
            return body()
        }
        lookupGuards.push(new HashSet<String>(keys))
        try {
            return body()
        } finally {
            lookupGuards.pop()
        }
    }

    private boolean isLookupGuarded(Expression container, Expression key) {
        if (lookupGuards.isEmpty()) {
            return false
        }
        String expected = lookupKey(container, key)
        if (expected == null) {
            return false
        }
        for (Set<String> frame : lookupGuards) {
            if (frame.contains(expected)) {
                return true
            }
        }
        return false
    }

    /**
     * Recognises {@code map.containsKey(key)}: a true result proves the key is
     * present, which makes a later {@code get(key)} non-null -- but only when the
     * map's values cannot themselves be null. With nullable values, a present key
     * may still hold an explicit null.
     */
    private void applyContainsKeyGuard(MethodCallExpression condition, GuardFacts facts) {
        if (condition.methodAsString != 'containsKey') {
            return
        }
        if (!isMapLike(typeOfExpression(condition.objectExpression))) {
            return
        }
        Expression callArgs = condition.arguments
        if (!(callArgs instanceof TupleExpression)) {
            return
        }
        TupleExpression args = (TupleExpression) callArgs
        if (args.expressions.size() != 1) {
            return
        }
        String key = lookupKey(condition.objectExpression, args.getExpression(0))
        if (key != null) {
            facts.lookupsTrue.add(key)
        }
    }

    /**
     * Whether a call returns null because a lookup can miss: {@code Map.get} and
     * friends, and the collection methods that return no result ({@code find}) or
     * cannot answer for an empty collection ({@code max}, {@code min}).
     *
     * <p>The element type says whether a <em>stored</em> value may be null. These
     * methods are partial regardless of that, which is why the rule lives here rather
     * than being folded into the type argument's default.
     */
    private boolean isNullableContainerLookup(Expression expression) {
        if (!(expression instanceof MethodCallExpression)) {
            return false
        }
        MethodCallExpression call = (MethodCallExpression) expression
        Object target = ((Expression) call).getNodeMetaData((Object) StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (!(target instanceof MethodNode)) {
            return false
        }
        Contract contract = contracts.contractFor((MethodNode) target)
        if (contract == null) {
            return false
        }
        // The switch turns off the built-in family; an explicit contract still applies.
        if (contract.builtIn && !containerLookups) {
            return false
        }
        if (contract.result == ResultNullness.NON_NULL) {
            return false
        }
        List<Expression> args
        if (call.arguments instanceof TupleExpression) {
            args = ((TupleExpression) call.arguments).expressions
        } else {
            args = Collections.<Expression>emptyList()
        }
        if (contract.result == ResultNullness.NULLABLE_IF_ARG_NULLABLE) {
            int index = contract.resultArgument
            return index >= 0 && index < args.size() && isKnownNullable(args.get(index))
        }
        PresenceRule presence = contract.presence
        if (contract.result == ResultNullness.NULLABLE && presence != null) {
            int index = presence.argument
            ClassNode receiver = typeOfExpression(call.objectExpression)
            if (index < args.size() && hasNonNullMapValue(receiver)
                    && isLookupGuarded(call.objectExpression, args.get(index))) {
                return false
            }
        }
        return true
    }

    private boolean isNullableMapSubscript(Expression expression) {
        if (!containerLookups) {
            return false
        }
        if (!isSubscript(expression)) {
            return false
        }
        BinaryExpression subscript = (BinaryExpression) expression
        ClassNode receiver = typeOfExpression(subscript.leftExpression)
        if (!isMapLike(receiver)) {
            return false
        }
        if (!hasNonNullMapValue(receiver)) {
            return true
        }
        return !isLookupGuarded(subscript.leftExpression, subscript.rightExpression)
    }

    /**
     * Applies narrowing for calls that guarantee an argument is non-null when
     * they complete normally: {@code Objects.requireNonNull} and Guava-style
     * {@code checkNotNull} throw for a null argument, {@code assertNotNull}
     * fails for one, and a fluent {@code assertThat(x).isNotNull()} chain
     * fails when {@code x} is null. Like annotations, the methods are matched
     * by simple name, so any library following the conventions is recognized.
     */
    private void applyCallNarrowing(MethodCall call) {
        if (call instanceof MethodCallExpression && call.methodAsString == 'isNotNull') {
            applyFluentNarrowing((MethodCallExpression) call)
            return
        }
        Object target = ((Expression) call).getNodeMetaData((Object) StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        Expression callArgs = call.arguments
        if (!(target instanceof MethodNode) || !(callArgs instanceof TupleExpression)) {
            return
        }
        TupleExpression args = (TupleExpression) callArgs
        if (!args.expressions) {
            return
        }
        // JUnit 4 takes (message, actual) while JUnit 5 and TestNG take (actual, message),
        // so narrow the arguments matching non-message parameters rather than assuming a position
        MethodNode targetMethod = (MethodNode) target
        if (targetMethod.name == 'assertNotNull') {
            Parameter[] params = targetMethod.parameters
            int limit = Math.min(args.expressions.size(), params.length)
            for (int i = 0; i < limit; i++) {
                if (!(params[i].type.name in ASSERTION_MESSAGE_TYPES)) {
                    narrowToNonNull(args.getExpression(i))
                }
            }
            return
        }
        Contract contract = contracts.contractFor((MethodNode) target)
        if (contract == null) {
            return
        }
        for (int index : contract.nonNullAfterReturn) {
            if (index >= args.expressions.size()) {
                continue
            }
            narrowToNonNull(args.getExpression(index))
        }
    }

    /**
     * Narrows the value asserted by a fluent {@code assertThat(x)...isNotNull()}
     * chain, looking through any intermediate calls ({@code describedAs}, etc.).
     */
    private void applyFluentNarrowing(MethodCallExpression call) {
        Expression receiver = call.objectExpression
        while (receiver instanceof MethodCallExpression
                && ((MethodCallExpression) receiver).methodAsString != 'assertThat') {
            receiver = ((MethodCallExpression) receiver).objectExpression
        }
        boolean assertThat = (receiver instanceof MethodCallExpression
                && ((MethodCallExpression) receiver).methodAsString == 'assertThat')
                || (receiver instanceof StaticMethodCallExpression
                && ((StaticMethodCallExpression) receiver).method == 'assertThat')
        if (!assertThat) {
            return
        }
        Expression callArgs = ((MethodCall) receiver).arguments
        if (callArgs instanceof TupleExpression) {
            TupleExpression args = (TupleExpression) callArgs
            if (args.expressions.size() == 1) {
                narrowToNonNull(args.getExpression(0))
            }
        }
    }

    private void narrowToNonNull(Expression expr) {
        Variable var = guardableVariable(expr)
        if (var != null) {
            // PORT: upstream uses Set.of(var), which is Java 9+. See the constant block
            // above for why the Java 8 equivalent is used here anyway. The null case is
            // already excluded by the guard.
            applyFacts(Collections.singleton(var))
        }
    }

    private void checkDereference(Expression receiver, Expression context) {
        if (isNullExpr(receiver)) {
            reporter.report('Cannot dereference null', context)
            return
        }
        if (receiver instanceof VariableExpression) {
            if (receiver.isThisExpression() || receiver.isSuperExpression()) {
                return
            }
            Variable target = findTargetVariable(receiver)
            boolean isMonotonicAndInitialized = target instanceof AnnotatedNode && hasMonotonicAnno(target) && monotonicInitialized.contains(target)
            if (target instanceof AnnotatedNode && hasNullableAnno(target) && !guardedVars.contains(target) && !isMonotonicAndInitialized) {
                reporter.report("Potential null dereference: '${receiver.name}' is @Nullable", context)
            } else if (isNullableElement(receiver, target) && !guardedVars.contains(target)) {
                reporter.report("Potential null dereference: '${receiver.name}' may be null", context)
            } else if (flowSensitive && nullableVars.contains(target) && !guardedVars.contains(target)) {
                reporter.report("Potential null dereference: '${receiver.name}' may be null", context)
            }
        } else if (isSafeNavResult(receiver)) {
            reporter.report("Potential null dereference: '${receiver.text}' may be null", context)
        } else if (isNullableEntryCall(receiver)) {
            reporter.report("Potential null dereference: '${receiver.text}' may be null", context)
        } else if (receiver instanceof MethodCallExpression && isNullableContainerLookup(receiver)) {
            reporter.report("Potential null dereference: '${receiver.methodAsString}()' may return null", context)
        } else if (receiver instanceof MethodCallExpression || receiver instanceof StaticMethodCallExpression) {
            Object targetMethod = receiver.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
            if (targetMethod instanceof MethodNode && (hasNullableAnno(targetMethod) || inferredNullableTypeUse(receiver))) {
                reporter.report("Potential null dereference: '${targetMethod.name}()' may return null", context)
            }
        } else if (isSubscript(receiver)
                && (inferredNullableTypeUse(receiver) || isNullableMapSubscript(receiver))) {
            reporter.report("Potential null dereference: '${receiver.text}' may be null", context)
        } else if (receiver instanceof PropertyExpression) {
            if (nullableProperty(receiver) || isNullableEntryProperty(receiver)) {
                reporter.report("Potential null dereference: '${receiver.propertyAsString}' may be null", context)
            }
        } else if (receiver instanceof CastExpression) {
            // casts are transparent to nullness
            checkDereference(receiver.expression, context)
        }
    }

    private void checkMethodArguments(MethodCall call) {
        Object target = ((Expression) call).getNodeMetaData((Object) StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (!(target instanceof MethodNode)) {
            return
        }
        MethodNode targetMethod = (MethodNode) target
        Expression callArgs = call.arguments
        if (!(callArgs instanceof TupleExpression)) {
            return
        }
        TupleExpression args = (TupleExpression) callArgs
        Parameter[] params = targetMethod.parameters
        // @NullCheck/@ParametersAreNonnullByDefault/@NonNullByDefault on method or class makes non-primitive params effectively @NonNull
        ClassNode declaringClass = targetMethod.declaringClass
        boolean nullChecked = hasNullCheckAnno(targetMethod) || hasNonNullByDefaultAnno(targetMethod) ||
            (declaringClass != null && (hasNullCheckAnno(declaringClass) ||
                    scopes.isParameterNonNullByDefault(declaringClass)))
        int limit = Math.min(args.expressions.size(), params.length)
        for (int i = 0; i < limit; i++) {
            Expression arg = args.getExpression(i)
            boolean paramIsNonNull = hasNonNullAnno(params[i]) || (nullChecked && !isPrimitiveType(params[i].type) && !hasNullableAnno(params[i]))
            if (paramIsNonNull) {
                if (isNullExpr(arg)) {
                    reporter.report("Cannot pass null to @NonNull parameter '${params[i].name}' of '${targetMethod.name}'", (ASTNode) call)
                } else if (isKnownNullable(arg)) {
                    reporter.report("Cannot pass @Nullable value to @NonNull parameter '${params[i].name}' of '${targetMethod.name}'", (ASTNode) call)
                }
            }
        }
    }

    /**
     * Determines whether an expression is known to produce a possibly-null value:
     * a {@code @Nullable} (or flow-inferred nullable) unguarded variable, a safe-navigation
     * result, a {@code @Nullable}-returning method call or property read, or a
     * ternary/elvis with a nullable branch. Casts are transparent to nullness.
     */
    private boolean isKnownNullable(Expression expr) {
        if (expr instanceof VariableExpression) {
            Variable target = findTargetVariable(expr)
            if (guardedVars.contains(target)) {
                return false
            }
            if (isNullableElement(expr, target)) {
                return true
            }
            if (target instanceof AnnotatedNode && hasNullableAnno(target)) {
                return true
            }
            return nullableVars.contains(target)
        }
        if (expr instanceof CastExpression) {
            return isKnownNullable(expr.expression)
        }
        if (isSafeNavResult(expr)) {
            return true
        }
        if (expr instanceof MethodCallExpression || expr instanceof StaticMethodCallExpression) {
            if (isNullableEntryCall(expr)) {
                return true
            }
            if (expr instanceof MethodCallExpression && isNullableContainerLookup(expr)) {
                return true
            }
            Object target = expr.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
            if (!(target instanceof MethodNode)) {
                return false
            }
            return hasNullableAnno((MethodNode) target) || inferredNullableTypeUse(expr)
        }
        if (isSubscript(expr)) {
            return inferredNullableTypeUse(expr) || isNullableMapSubscript(expr)
        }
        if (expr instanceof PropertyExpression) {
            return nullableProperty(expr) || isNullableEntryProperty(expr)
        }
        if (expr instanceof TernaryExpression) {
            // judge each branch under the condition's guard facts, so that
            // e.g. "s != null ? s : 'default'" is not considered nullable
            GuardFacts facts = analyzeCondition(expr.booleanExpression.expression, false)
            // elvis (x ?: y) is null only if its fallback is: a truthy x is non-null
            boolean trueNullable = !(expr instanceof ElvisOperatorExpression) &&
                withGuards(facts.whenTrue) { isNullExpr(expr.trueExpression) || isKnownNullable(expr.trueExpression) }
            boolean falseNullable =
                withGuards(facts.whenFalse) { isNullExpr(expr.falseExpression) || isKnownNullable(expr.falseExpression) }
            return trueNullable || falseNullable
        }
        return false
    }

    private boolean isEarlyExit(Statement stmt) {
        return cannotCompleteNormally(stmt)
    }

    /**
     * Whether a statement can never complete normally, which makes everything after it in the
     * same block unreachable.
     *
     * <p>This exists for one real shape: a helper that always throws being called at the end of
     * a catch block, followed by a fallback {@code return null}. Without it the fallback looks
     * reachable and is reported as returning null from a non-null method, even though it can
     * never run. {@code break} and {@code continue} count here for the same reason they count
     * as early exits in guard analysis: control leaves the current statement sequence.
     */
    private boolean cannotCompleteNormally(Statement statement) {
        if (statement == null) {
            return false
        }
        if (statement instanceof ReturnStatement || statement instanceof ThrowStatement) {
            return true
        }
        if (statement instanceof BreakStatement || statement instanceof ContinueStatement) {
            return true
        }
        if (statement instanceof ExpressionStatement) {
            return contracts.isNoReturnCall(statement.expression)
        }
        if (statement instanceof BlockStatement) {
            for (Statement child : statement.statements) {
                if (cannotCompleteNormally(child)) {
                    return true
                }
            }
            return false
        }
        if (statement instanceof IfStatement) {
            return statement.elseBlock != null
                    && cannotCompleteNormally(statement.ifBlock)
                    && cannotCompleteNormally(statement.elseBlock)
        }
        if (statement instanceof TryCatchStatement) {
            if (!cannotCompleteNormally(statement.tryStatement)) {
                return false
            }
            for (CatchStatement clause : statement.catchStatements) {
                if (!cannotCompleteNormally(clause.code)) {
                    return false
                }
            }
            if (statement.finallyStatement != null && cannotCompleteNormally(statement.finallyStatement)) {
                return true
            }
            return true
        }
        if (statement instanceof SynchronizedStatement) {
            return cannotCompleteNormally(statement.code)
        }
        return false
    }

}
