import io.github.heyuch.groovy.nullchecker.harness.CorpusGate

/** Run CorpusGate under an experimental Groovy version; exit non-zero on any mismatch. */
def corpus = new File(args[0])
def report = CorpusGate.run(corpus, 'io.github.heyuch.groovy.nullchecker.NullChecker',
        new File(corpus, 'support'))
println "compat: results=${report.results.size()} failures=${report.failures.size()}"
report.failures.take(20).each { failure -> println "compat: FAIL ${failure}" }
if (!report.failures.isEmpty()) {
    System.exit(1)
}
