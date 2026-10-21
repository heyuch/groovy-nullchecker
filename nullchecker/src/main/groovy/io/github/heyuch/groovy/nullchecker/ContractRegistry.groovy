package io.github.heyuch.groovy.nullchecker

import org.jspecify.annotations.Nullable

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.transform.stc.StaticTypesMarker

import static io.github.heyuch.groovy.nullchecker.AstSupport.argumentIndex
import static io.github.heyuch.groovy.nullchecker.AstSupport.booleanMember
import static io.github.heyuch.groovy.nullchecker.AstSupport.findAnnotation
import static io.github.heyuch.groovy.nullchecker.AstSupport.stringMember
import static io.github.heyuch.groovy.nullchecker.AstSupport.stringMembers

/**
 * Resolves what a call promises, from three sources in order of precedence: the project's
 * configuration, annotations on the method, and the built-in table for library methods.
 *
 * <p>One instance per compilation: the declared configuration and the resolved contracts are
 * instance state, so two compilations cannot see each other's answers. Only the pure parsing
 * helpers are static.
 *
 * <p>Extracted from {@link NullChecker} so the resolution rules read as one unit. The AST
 * helpers it needs live in {@link AstSupport}, so it does not depend on the checker;
 * everything here is package-scoped, not public API.
 */
@CompileStatic
@PackageScope
class ContractRegistry {

    private static final String GUARD_TRUE_PROPERTY = 'groovy.nullchecker.guards.true'

    private static final String GUARD_FALSE_PROPERTY = 'groovy.nullchecker.guards.false'

    private static final String NEVER_RETURNS_PROPERTY = 'groovy.nullchecker.neverreturns'

    private static final String CONTRACTS_PROPERTY = 'groovy.nullchecker.contracts'

    private static final Set<String> NO_RETURN_ANNOS = Collections.unmodifiableSet(
            ['NoReturn', 'NeverReturns', 'DoesNotReturn'] as Set<String>)

    private final Set<String> guardsTrue

    private final Set<String> guardsFalse

    private final Set<String> neverReturns

    private final Map<String, Contract> configured

    private final Map<String, Contract> builtIns = new LinkedHashMap<String, Contract>()

    private final Map<MethodNode, Contract> cache = new IdentityHashMap<>()

    /** Reads the project's declarations; one instance per compilation. */
    ContractRegistry() {
        guardsTrue = readGuardEntries(GUARD_TRUE_PROPERTY)
        guardsFalse = readGuardEntries(GUARD_FALSE_PROPERTY)
        neverReturns = readGuardEntries(NEVER_RETURNS_PROPERTY)
        installBuiltIns()
        configured = buildConfigContracts()
    }

    /**
     * The built-in table, as data rather than a branch per method.
     *
     * <p>Entries are keyed by {@code declaringClass#method}, matched through the declaring
     * class's supertypes, or by {@code #method} when the guarantee holds for any owner (the
     * throwing validators). Configuration is registered separately and wins over annotations,
     * which in turn win over this table.
     */
    private void installBuiltIns() {
        for (String name : ['get', 'getAt', 'remove']) {
            registerBuiltIn('java.util.Map#' + name, Contract.lookupResult(0))
        }
        List<String> mapWriters = ['put', 'putIfAbsent', 'replace', 'compute',
                                   'computeIfAbsent', 'computeIfPresent', 'merge']
        for (String name : mapWriters) {
            registerBuiltIn('java.util.Map#' + name, Contract.nullableResult())
        }
        registerBuiltIn('java.util.Map#getOrDefault', Contract.nullableIfArgNullable(1))
        registerBuiltIn('java.util.Objects#nonNull', Contract.whenTrue(0))
        registerBuiltIn('java.util.Objects#isNull', Contract.whenFalse(0))
        // Name-only: these throw for a null argument whichever library declares them.
        registerBuiltIn('#requireNonNull', Contract.afterReturn(0))
        registerBuiltIn('#checkNotNull', Contract.afterReturn(0))
        // The extension methods resolve against the receiver: Iterable/Collection for
        // collections, and the array class for arrays (DGM's Object[] overload covers every
        // reference array; the primitive descriptors cover the rest).
        List<String> partialOwners = ['java.lang.Iterable', 'java.util.Collection',
                                      '[Ljava.lang.Object;', '[I', '[J', '[D', '[F', '[Z',
                                      '[B', '[C', '[S']
        for (String owner : partialOwners) {
            for (String name : ['find', 'findResult', 'max', 'min']) {
                registerBuiltIn(owner + '#' + name, Contract.nullableResult())
            }
        }
    }

    private void registerBuiltIn(String key, Contract contract) {
        contract.builtIn = true
        builtIns.put(key, contract)
    }
    /**
     * Parses a JetBrains contract string into the model.
     *
     * <p>Recognised shapes: {@code -> fail} and {@code null -> fail} (narrowing), {@code null ->
     * true|false} (branch facts), and {@code _, !null -> !null} (the result is nullable exactly
     * when that argument is). Anything else returns {@code null} so a lower-priority source --
     * or the built-in table -- can answer instead of an empty contract masking it.
     */
    private static @Nullable Contract contractFromJetBrains(@Nullable String specification) {
        if (specification == null) {
            return null
        }
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
                    if (clause.arguments[i] != 'null') {
                        continue
                    }
                    (whenTrue ? contract.nonNullWhenTrue : contract.nonNullWhenFalse).add(i)
                    recognised = true
                }
            } else if (clause.result == '!null') {
                List<Integer> nonNullArguments = new ArrayList<Integer>()
                for (int i = 0; i < clause.arguments.size(); i++) {
                    if (clause.arguments[i] == '!null') {
                        nonNullArguments.add(i)
                    }
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
        return recognised ? contract : null
    }

    /** Contracts declared by annotations on the method itself. */
    private @Nullable Contract annotationContract(MethodNode target) {
        AnnotationNode jetbrains = findAnnotation(target, 'Contract')
        if (jetbrains != null) {
            Contract parsed = contractFromJetBrains(stringMember(jetbrains, 'value'))
            if (parsed != null) {
                return parsed
            }
        }
        AnnotationNode ensuresIf = findAnnotation(target, 'EnsuresNonNullIf')
        if (ensuresIf != null) {
            Boolean expected = booleanMember(ensuresIf, 'result')
            if (expected != null) {
                Contract contract = new Contract()
                for (String reference : stringMembers(ensuresIf, 'expression')) {
                    int index = argumentIndex(reference)
                    if (index < 0) {
                        continue
                    }
                    (expected ? contract.nonNullWhenTrue : contract.nonNullWhenFalse).add(index)
                }
                if (!contract.nonNullWhenTrue.isEmpty() || !contract.nonNullWhenFalse.isEmpty()) {
                    return contract
                }
            }
        }
        AnnotationNode ensures = findAnnotation(target, 'EnsuresNonNull')
        if (ensures != null) {
            Contract contract = new Contract()
            for (String reference : stringMembers(ensures, 'expression')) {
                int index = argumentIndex(reference)
                if (index >= 0) {
                    contract.nonNullAfterReturn.add(index)
                }
            }
            if (!contract.nonNullAfterReturn.isEmpty()) {
                return contract
            }
        }
        for (String annotation : NO_RETURN_ANNOS) {
            if (findAnnotation(target, annotation) != null) {
                return Contract.never()
            }
        }
        return null
    }

    @Nullable Contract contractFor(@Nullable MethodNode target) {
        if (target == null) {
            return null
        }
        Contract cached = cache.get(target)
        if (cached != null) {
            return cached.unresolved ? null : cached
        }
        Contract resolved = lookup(configured, target)
        if (resolved == null) {
            resolved = annotationContract(target)
        }
        if (resolved == null) {
            resolved = lookup(builtIns, target)
        }
        cache.put(target, resolved ?: Contract.absent())
        return resolved
    }

    /**
     * Looks a method up in one source.
     *
     * <p>{@code declaringClass#method} is matched through the declaring class's supertypes, so
     * a registration on {@code java.util.Map} covers {@code HashMap.get}. A {@code #method}
     * entry matches any owner and is consulted after the exact walk, so a more specific entry
     * always wins.
     */
    private static @Nullable Contract lookup(@Nullable Map<String, Contract> table, MethodNode target) {
        if (table == null || table.isEmpty()) {
            return null
        }
        ClassNode owner = target.declaringClass
        if (owner != null) {
            Set<String> seen = new HashSet<String>()
            Deque<ClassNode> pending = new ArrayDeque<ClassNode>()
            pending.add(owner)
            while (!pending.isEmpty()) {
                ClassNode current = pending.poll()
                if (current == null || !seen.add(current.name)) {
                    continue
                }
                Contract contract = table.get(current.name + '#' + target.name)
                if (contract != null) {
                    return contract
                }
                if (current.superClass != null) {
                    pending.add(current.superClass)
                }
                current.interfaces?.each { ClassNode implemented -> pending.add(implemented) }
            }
        }
        return table.get('#' + target.name)
    }

    private Map<String, Contract> buildConfigContracts() {
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
        return contracts
    }

    private static Map<String, String> readContractEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>()
        String raw = System.getProperty(CONTRACTS_PROPERTY)
        if (raw == null) {
            return entries
        }
        for (String part : raw.split(';')) {
            String entry = part.trim()
            if (entry.isEmpty()) {
                continue
            }
            int separator = entry.indexOf('=')
            if (separator <= 0) {
                continue
            }
            entries.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim())
        }
        return entries
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
    boolean isNoReturnCall(Expression expression) {
        if (expression == null) {
            return false
        }
        Object target = expression.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (!(target instanceof MethodNode)) {
            return false
        }
        Contract contract = contractFor((MethodNode) target)
        return contract != null && contract.neverReturns
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
        if (specification == null) {
            return clauses
        }
        for (String rawClause : specification.split(';')) {
            String clause = rawClause.trim()
            if (clause.isEmpty()) {
                continue
            }
            int arrow = clause.indexOf('->')
            if (arrow < 0) {
                continue
            }
            String result = clause.substring(arrow + 2).trim()
            if (result.isEmpty()) {
                continue
            }
            List<String> arguments = new ArrayList<String>()
            for (String rawArgument : clause.substring(0, arrow).split(',')) {
                arguments.add(rawArgument.trim())
            }
            clauses.add(new ContractClause(arguments: arguments, result: result))
        }
        return clauses
    }

    /** Reads a comma-separated guard list, ignoring blanks and surrounding whitespace. */
    private static Set<String> readGuardEntries(String property) {
        String raw = System.getProperty(property)
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.<String> emptySet()
        }
        Set<String> entries = new HashSet<String>()
        for (String part : raw.split(',')) {
            String entry = part.trim()
            if (!entry.isEmpty()) {
                entries.add(entry)
            }
        }
        return Collections.unmodifiableSet(entries)
    }

}
