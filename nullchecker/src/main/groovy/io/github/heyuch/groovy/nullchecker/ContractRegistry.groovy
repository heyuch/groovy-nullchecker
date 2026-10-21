package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope
import static io.github.heyuch.groovy.nullchecker.AstSupport.*
import java.util.Arrays
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker

/**
 * Resolves what a call promises, from three sources in order of precedence: the project's
 * configuration, annotations on the method, and the built-in table for library methods.
 *
 * <p>Extracted from {@link NullChecker} so the resolution rules read as one unit. The AST
 * helpers it needs live in {@link AstSupport}, so it does not depend on the checker;
 * everything here is package-scoped, not public API.
 */
@PackageScope
class ContractRegistry {

    private static final String GUARD_TRUE_PROPERTY = 'groovy.nullchecker.guards.true'

    private static final String GUARD_FALSE_PROPERTY = 'groovy.nullchecker.guards.false'

    private static final String NEVER_RETURNS_PROPERTY = 'groovy.nullchecker.neverreturns'

    private static final String CONTRACTS_PROPERTY = 'groovy.nullchecker.contracts'

    private static final Set<String> NO_RETURN_ANNOS = Collections.unmodifiableSet(
            ['NoReturn', 'NeverReturns', 'DoesNotReturn'] as Set<String>)

    private static final Set<String> NONNULL_VALIDATOR_METHODS = Collections.unmodifiableSet(
            ['requireNonNull', 'checkNotNull'] as Set<String>)

    private static final Set<String> MAP_LOOKUP_METHODS = Collections.unmodifiableSet(
            ['get', 'getAt', 'remove', 'put', 'putIfAbsent', 'replace',
             'compute', 'computeIfAbsent', 'computeIfPresent', 'merge'] as Set<String>)

    private static final Set<String> PARTIAL_COLLECTION_METHODS = Collections.unmodifiableSet(
            ['find', 'findResult', 'max', 'min'] as Set<String>)

    private static Set<String> guardsTrue = Collections.emptySet()

    private static Set<String> guardsFalse = Collections.emptySet()

    private static Set<String> neverReturns = Collections.emptySet()

    private static Map<String, Contract> configured = [:]

    private static final Map<MethodNode, Contract> cache = new IdentityHashMap<>()

    /** Re-reads the configured contracts; call once per compilation. */
    static void prepare() {
        guardsTrue = readGuardEntries(GUARD_TRUE_PROPERTY)
        guardsFalse = readGuardEntries(GUARD_FALSE_PROPERTY)
        neverReturns = readGuardEntries(NEVER_RETURNS_PROPERTY)
        configured = buildConfigContracts()
    }

    /** Drops the resolved contracts, so a new compilation cannot see a stale answer. */
    static void reset() {
        configured = [:]
        cache.clear()
    }

    /** How a call's result relates to nullness. */
    /**
     * What a call promises: its result, which arguments it narrows, and whether it returns.
     *
     * <p>One model for every source. Annotations ({@code @Contract}, {@code @EnsuresNonNullIf},
     * {@code @EnsuresNonNull}, {@code @NoReturn}), the project's configuration, and the
     * built-in table for library methods all resolve to this, in that order of precedence
     * (configuration first, then annotations, then built-ins). Keeping one model is what makes
     * a configured contract and an annotated one behave identically.
     */
    /**
     * Parses a JetBrains contract string into the model.
     *
     * <p>Recognised shapes: {@code -> fail} and {@code null -> fail} (narrowing), {@code null ->
     * true|false} (branch facts), and {@code _, !null -> !null} (the result is nullable exactly
     * when that argument is). Anything else returns {@code null} so a lower-priority source --
     * or the built-in table -- can answer instead of an empty contract masking it.
     */
    private static Contract contractFromJetBrains(String specification) {
        if (specification == null) return null
        Contract contract = new Contract()
        boolean recognised = false
        for (ContractClause clause : parseContract(specification)) {
            if (clause.result == 'fail') {
                if (clause.arguments.every { String argument -> argument == '_' || argument.isEmpty() }) {
                    contract.neverReturns = true
                    recognised = true
                } else {
                    for (int i = 0; i < clause.arguments.size(); i++) {
                        if (clause.arguments[i] == 'null') {
                            contract.nonNullAfterReturn.add(i)
                            recognised = true
                        }
                    }
                }
            } else if (clause.result == 'true' || clause.result == 'false') {
                boolean whenTrue = clause.result == 'false'
                for (int i = 0; i < clause.arguments.size(); i++) {
                    if (clause.arguments[i] != 'null') continue
                    (whenTrue ? contract.nonNullWhenTrue : contract.nonNullWhenFalse).add(i)
                    recognised = true
                }
            } else if (clause.result == '!null') {
                List<Integer> nonNullArguments = new ArrayList<Integer>()
                for (int i = 0; i < clause.arguments.size(); i++) {
                    if (clause.arguments[i] == '!null') nonNullArguments.add(i)
                }
                if (nonNullArguments.size() == 1) {
                    contract.result = ResultNullness.NULLABLE_IF_ARG_NULLABLE
                    contract.resultArgument = nonNullArguments.get(0)
                    recognised = true
                } else if (nonNullArguments.isEmpty()) {
                    contract.result = ResultNullness.NON_NULL
                    recognised = true
                }
            } else if (clause.result == 'null'
                    && clause.arguments.every { String argument -> argument == '_' || argument.isEmpty() }) {
                contract.result = ResultNullness.NULLABLE
                recognised = true
            }
        }
        recognised ? contract : null
    }

    /** Contracts declared by annotations on the method itself. */
    private static Contract annotationContract(MethodNode target) {
        AnnotationNode jetbrains = findAnnotation(target, 'Contract')
        if (jetbrains != null) {
            Contract parsed = contractFromJetBrains(stringMember(jetbrains, 'value'))
            if (parsed != null) return parsed
        }
        AnnotationNode ensuresIf = findAnnotation(target, 'EnsuresNonNullIf')
        if (ensuresIf != null) {
            Boolean expected = booleanMember(ensuresIf, 'result')
            if (expected != null) {
                Contract contract = new Contract()
                for (String reference : stringMembers(ensuresIf, 'expression')) {
                    int index = argumentIndex(reference)
                    if (index < 0) continue
                    (expected ? contract.nonNullWhenTrue : contract.nonNullWhenFalse).add(index)
                }
                if (contract.nonNullWhenTrue || contract.nonNullWhenFalse) return contract
            }
        }
        AnnotationNode ensures = findAnnotation(target, 'EnsuresNonNull')
        if (ensures != null) {
            Contract contract = new Contract()
            for (String reference : stringMembers(ensures, 'expression')) {
                int index = argumentIndex(reference)
                if (index >= 0) contract.nonNullAfterReturn.add(index)
            }
            if (contract.nonNullAfterReturn) return contract
        }
        for (String annotation : NO_RETURN_ANNOS) {
            if (findAnnotation(target, annotation) != null) return Contract.never()
        }
        null
    }

    /**
     * Built-in contracts for library methods whose result nullness is not in their signature.
     *
     * <p>These describe the operation, not the element type: {@code Map.get} misses, {@code
     * find} may find nothing, {@code max} cannot answer an empty collection. The table is the
     * last source, so a project can override any entry through {@code contracts}.
     */
    private static Contract builtInContract(Expression callExpression, MethodNode target) {
        String owner = target.declaringClass?.name
        String name = target.name
        if (owner == 'java.util.Objects') {
            if (name == 'nonNull') return Contract.fact(true)
            if (name == 'isNull') return Contract.fact(false)
        }
        if (name in NONNULL_VALIDATOR_METHODS) {
            return Contract.afterReturn(0)
        }
        if (!(callExpression instanceof MethodCallExpression)) return null
        MethodCallExpression call = (MethodCallExpression) callExpression
        ClassNode receiver = typeOfExpression(call.objectExpression)
        if (isMapLike(receiver)) {
            if (name == 'getOrDefault') {
                Contract contract = new Contract()
                contract.result = ResultNullness.NULLABLE_IF_ARG_NULLABLE
                contract.resultArgument = 1
                contract.builtIn = true
                return contract
            }
            if (name in MAP_LOOKUP_METHODS) {
                Contract contract = new Contract()
                contract.result = ResultNullness.NULLABLE
                contract.builtIn = true
                if (name in ['get', 'getAt', 'remove']) {
                    contract.presence = new PresenceRule(argument: 0)
                }
                return contract
            }
        }
        if (name in PARTIAL_COLLECTION_METHODS && isCollectionLike(receiver)) {
            Contract contract = new Contract()
            contract.result = ResultNullness.NULLABLE
            contract.builtIn = true
            return contract
        }
        null
    }

    static Contract contractFor(Expression callExpression, MethodNode target) {
        if (target == null) return null
        Contract cached = cache.get(target)
        if (cached != null) {
            return cached.unresolved ? null : cached
        }
        Contract resolved = configuredContract(target)
        if (resolved == null) resolved = annotationContract(target)
        if (resolved == null) resolved = builtInContract(callExpression, target)
        cache.put(target, resolved ?: Contract.absent())
        resolved
    }

    /**
     * Contracts declared by the project, matched by {@code declaringClass#method} and by the
     * same key on any superclass or interface.
     */
    private static Contract configuredContract(MethodNode target) {
        if (configured.isEmpty()) return null
        ClassNode owner = target.declaringClass
        if (owner == null) return null
        Set<String> seen = new HashSet<String>()
        Deque<ClassNode> pending = new ArrayDeque<ClassNode>()
        pending.add(owner)
        while (!pending.isEmpty()) {
            ClassNode current = pending.poll()
            if (current == null || !seen.add(current.name)) continue
            Contract contract = configured.get(current.name + '#' + target.name)
            if (contract != null) return contract
            if (current.superClass != null) pending.add(current.superClass)
            current.interfaces?.each { ClassNode implemented -> pending.add(implemented) }
        }
        null
    }

    private static Map<String, Contract> buildConfigContracts() {
        Map<String, Contract> contracts = new LinkedHashMap<String, Contract>()
        for (String key : guardsTrue) {
            contracts.put(key, Contract.fact(true))
        }
        for (String key : guardsFalse) {
            contracts.put(key, Contract.fact(false))
        }
        for (String key : neverReturns) {
            contracts.put(key, Contract.never())
        }
        for (Map.Entry<String, String> entry : readContractEntries().entrySet()) {
            Contract contract = contractFromJetBrains(entry.value)
            if (contract != null) {
                contracts.put(entry.key, contract)
            }
        }
        contracts
    }

    private static Map<String, String> readContractEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>()
        String raw = System.getProperty(CONTRACTS_PROPERTY)
        if (raw == null) return entries
        for (String part : raw.split(';')) {
            String entry = part.trim()
            if (entry.isEmpty()) continue
            int separator = entry.indexOf('=')
            if (separator <= 0) continue
            entries.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim())
        }
        entries
    }

    /**
     * Whether a call never returns normally.
     *
     * <p>Three declarations say so, all matched by simple name: {@code @NoReturn} (and the two
     * spellings in {@link #NO_RETURN_ANNOS}), JetBrains' {@code @Contract} with a clause whose
     * result is {@code fail} and whose arguments are all {@code _} (any value), and the
     * project's own {@code neverReturns} list. A single failing branch such as
     * {@code "null -> fail"} is not enough: the call still returns for other arguments.
     */
    private static boolean isNoReturnCall(Expression expression) {
        if (expression == null) return false
        def target = expression.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (!(target instanceof MethodNode)) return false
        Contract contract = contractFor(expression, (MethodNode) target)
        contract != null && contract.neverReturns
    }

    /**
     * Parses a JetBrains {@code @Contract} specification.
     *
     * <p>The grammar is {@code arg, arg -> result} clauses separated by {@code ;}. Only two
     * shapes matter here: {@code null -> fail} (the call throws for a null argument) and
     * {@code null -> true|false} (the result reveals whether the argument was null). Anything
     * else -- {@code !null}, {@code _}, {@code param1}, {@code new} -- is left alone rather than
     * interpreted, because a wrong reading would narrow a value that can still be null.
     */
    private static List<ContractClause> parseContract(String specification) {
        List<ContractClause> clauses = new ArrayList<ContractClause>()
        if (specification == null) return clauses
        for (String rawClause : specification.split(';')) {
            String clause = rawClause.trim()
            if (clause.isEmpty()) continue
            int arrow = clause.indexOf('->')
            if (arrow < 0) continue
            String result = clause.substring(arrow + 2).trim()
            if (result.isEmpty()) continue
            List<String> arguments = new ArrayList<String>()
            for (String rawArgument : clause.substring(0, arrow).split(',')) {
                arguments.add(rawArgument.trim())
            }
            clauses.add(new ContractClause(arguments: arguments, result: result))
        }
        clauses
    }

    /** Reads a comma-separated guard list, ignoring blanks and surrounding whitespace. */
    private static Set<String> readGuardEntries(String property) {
        String raw = System.getProperty(property)
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet()
        }
        Set<String> entries = new HashSet<String>()
        for (String part : raw.split(',')) {
            String entry = part.trim()
            if (!entry.isEmpty()) {
                entries.add(entry)
            }
        }
        Collections.unmodifiableSet(entries)
    }
}