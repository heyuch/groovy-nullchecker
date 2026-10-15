package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.CorpusGate
import io.github.heyuch.groovy.nullchecker.harness.CorpusRules
import spock.lang.Shared
import spock.lang.Specification

/**
 * The corpus gate.
 *
 * <p>A null checker's dangerous failure mode is silence: the check does not run, or
 * runs at the wrong phase, and every input looks clean. So this specification is
 * built around paired fixtures -- every rule has an input that <em>must</em> be
 * reported, and every guard shape has an input that must stay silent -- plus a
 * coverage requirement that refuses a rule no fixture exercises.
 */
class NullCheckerCorpusSpec extends Specification {

    @Shared
    File corpusDir = new File(System.getProperty('corpus.dir'))

    @Shared
    String extension = System.getProperty('nullchecker.extension')

    @Shared
    CorpusGate.Report report = CorpusGate.run(corpusDir, extension, new File(corpusDir, 'support'))

    def 'corpus gate passes: no missing, extra or mismatched fixtures'() {
        expect: 'no fixture failed its declared expectation'
        report.failures.isEmpty()

        and: 'the gate actually compiled something (a gate that runs nothing must not pass)'
        report.results.size() > 20
    }

    def 'every rule in the checker is exercised by at least one fixture'() {
        expect: 'no rule is dark -- an untriggered rule is a rule nobody has seen fire'
        report.uncoveredRules.isEmpty()

        and: 'coverage spans both positive rules and guard families'
        report.ruleCoverage.keySet().any { String r -> r.startsWith('R') }
        report.ruleCoverage.keySet().any { String r -> r.startsWith('N') }
    }

    def 'positive fixtures are reported and negative fixtures stay silent'() {
        expect: 'each declared rule behaved as the manifest demands'
        report.results.every { CorpusGate.FixtureResult fr -> fr.failures().isEmpty() }

        and: 'there is at least one finding for every positive rule id'
        List<String> positiveRules = report.results
                .findAll { CorpusGate.FixtureResult fr -> fr.fixture.rule.startsWith('R') }
                .collect { CorpusGate.FixtureResult fr -> fr.fixture.rule }
        positiveRules.containsAll(CorpusRules.RULES.keySet())
    }

    def 'guard fixtures stay silent in both annotation and strict modes'() {
        given: 'the fixtures whose whole purpose is to prove absence of false positives'
        List<CorpusGate.FixtureResult> guards = report.results.findAll { CorpusGate.FixtureResult fr ->
            CorpusRules.GUARD_RULES.containsKey(fr.fixture.rule)
        }

        expect: 'there is a real guard corpus, not a token one'
        guards.size() >= 10

        and: 'none of them reports anything in either mode'
        guards.every { CorpusGate.FixtureResult fr ->
            fr.modes.every { CorpusGate.ModeResult m -> m.findings == 0 }
        }
    }

    def 'strict-only rules are silent without strict and reported with it'() {
        given: 'fixtures declared strict=only'
        List<CorpusGate.FixtureResult> strictOnly = report.results.findAll { CorpusGate.FixtureResult fr ->
            fr.fixture.strict == 'only'
        }

        expect: 'strict mode is genuinely covered'
        !strictOnly.isEmpty()

        and: 'every one of them is silent in annotation mode and reports in strict mode'
        strictOnly.every { CorpusGate.FixtureResult fr ->
            CorpusGate.ModeResult annotations = fr.modes.find { CorpusGate.ModeResult m -> m.mode == 'annotations' }
            CorpusGate.ModeResult strict = fr.modes.find { CorpusGate.ModeResult m -> m.mode == 'strict' }
            annotations.findings == 0 && strict.findings > 0
        }
    }
}
