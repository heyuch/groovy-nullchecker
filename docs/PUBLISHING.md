# Releasing

Two artifacts ship from one build:

| Artifact | Coordinates | Built from |
| --- | --- | --- |
| The checker | `io.github.heyuch:groovy-nullchecker` | `nullchecker/` |
| The Gradle plugin | `io.github.heyuch:nullchecker-gradle-plugin`, plus the plugin marker `io.github.heyuch.groovy-nullchecker.gradle.plugin` | `nullchecker-gradle-plugin/` |

They are sibling subprojects of the same build and share a version. The plugin deliberately does
**not** depend on `:nullchecker`: it reaches the checker by coordinate at execution time and never
compiles against its types.

## Version

The version lives in one place: `gradle.properties` → `nullcheckerVersion`. The root
`build.gradle` applies it to both subprojects, so the two cannot drift apart.

This used to be a two-file duplication — `gradle.properties` and the plugin's own `build.gradle` —
kept in sync by hand. Collapsing it was already flagged here as worth doing before the first
release.

The plugin's default `toolVersion` (`NullCheckPlugin.DEFAULT_TOOL_VERSION`) is no longer a
literal either. It is baked into `nullchecker.properties` at build time from the plugin's own
version, which is the same value — so a plugin release can no longer point at a checker version
that does not exist. There is nothing to keep in sync by hand any more.

## Before publishing

```bash
# Rewrite README/examples from gradle.properties and format the sources.
./gradlew spotlessApply

# Both halves of the gate: the checker's corpus gate, and the plugin's TestKit functional tests.
# functionalTest resolves the checker from mavenLocal, so that task installs it first; a clean
# checkout runs this in one go.
./gradlew clean check

# Informational: compare against Apache Groovy 6's NullChecker and diff the results.
# Differences are expected as this checker evolves; they do not fail the build.
./gradlew dualRun
```

## Documentation versions

`README.md`, `README.zh-CN.md`, `docs/PUBLISHING.md` and the consumer examples show the current
version in coordinates, plugin ids, jar names and `toolVersion`. The root Spotless `docs` format
rewrites them from the single source, `gradle.properties` → `nullcheckerVersion`:

```bash
./gradlew spotlessApply
```

This is a text replacement, not a stale gate: `./gradlew check` does not fail when the docs
still show an older version. `./gradlew spotlessCheck` verifies the same replacement if it is
run explicitly. `CHANGELOG.md` and `docs/BACKLOG.md` are deliberately excluded: their versions
are historical records, not current installation instructions.

## Publish to the local Maven repository

Useful for trying the artifacts in another project before releasing:

```bash
./gradlew publishToMavenLocal
```

Consumers then need `mavenLocal()` in **two** places, because Gradle resolves plugins and
dependencies through different repository lists:

```groovy
// settings.gradle — plugins; must be the first statement in the file
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal() }
}

// build.gradle — the checker, which the plugin resolves as a normal dependency
repositories { mavenLocal(); mavenCentral() }
```

The plugin marker requires an explicit version in the `plugins` block:
`id 'io.github.heyuch.groovy-nullchecker' version '0.11.0'`. Without a version Gradle reports
"plugin dependency must include a version number", which reads like the plugin is missing.

Publishing the same version again does not invalidate Gradle's dependency cache. Consumers
must run `./gradlew --refresh-dependencies`.

## Publish to Maven Central

Both artifacts go through the Central Portal. The root `settings.gradle` applies
`com.gradleup.nmcp.settings`, which uploads every publication of the build as **one** bundle to
the publisher API — so the checker, the plugin and its marker now travel together as a single
deployment instead of two. There is no staging repository to close and no
`distributionManagement`; that whole flow belonged to OSSRH, which is shut down.

What the two `build.gradle` files carry:

1. **Signing** for every publication. Central requires a signature beside every file. The key
   comes from `MAVEN_GPG_KEY` (ASCII-armoured secret key) and the passphrase from
   `MAVEN_GPG_PASSPHRASE`. With no key present, signing is skipped entirely, so
   `publishToMavenLocal` still works on a machine that has never held the key.

2. **POM metadata** — `url`, `licenses`, `developers` and `scm`. Central rejects a bundle whose
   POM is missing any of them. The plugin has no hand-written publication (see below), so its
   metadata goes through `publications.configureEach`.

3. **`-sources.jar` and `-javadoc.jar`**, also mandatory. Note the javadoc jar here is close to
   empty: the javadoc task reads `.java` only, and this project's main sources are Groovy.
   Validation only requires the file to exist, so it passes. Feeding the `groovydoc` task into
   the jar would give it real content if that is ever wanted.

Then, with `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` holding a
[user token](https://central.sonatype.com/usertoken):

```bash
./gradlew publishAggregationToCentralPortal
```

The publishing type defaults to `AUTOMATIC`, which publishes the moment validation passes.
Coordinates on Central are permanent and cannot be withdrawn, so to look at the bundle before
it goes live:

```bash
./gradlew publishAggregationToCentralPortal -PcentralPublishingType=USER_MANAGED
```

That leaves the deployment sitting in `VALIDATED` until it is released from the portal UI.

Nothing secret is ever committed: the credentials are read from the environment, and an empty
value fails loudly at the portal rather than silently publishing something unverifiable.

## Gradle Plugin Portal

`nullchecker-gradle-plugin` also goes to the Plugin Portal, which is a separate registry with
its own credentials (`GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`):

```bash
./gradlew :nullchecker-gradle-plugin:publishPlugins
```

The plugin marker is already produced by the `java-gradle-plugin` plugin, so the coordinates
above do not change. The first submission is reviewed by hand and takes days to appear.

## Automation

`.github/workflows/release.yml` runs all of the above when a GitHub Release is published. The
secrets sit on the `release` environment, restricted to `v*` tags, rather than on the
repository: the signing key can publish artifacts that can never be withdrawn, so it should
only reach a runner that is deploying a tag.

The workflow also checks that the release tag matches `nullcheckerVersion`. The other half of the
old check — comparing that property against a `version` hardcoded in the plugin's own
`build.gradle` — is gone with the duplication it guarded: both modules now read the same property,
so they cannot disagree. The tag check stays, because the tag can still disagree with the
property.

## Release assets

A GitHub Release automatically carries the two source archives and nothing else. The release
workflow additionally builds and attaches the jars, because that is what a user can act on:
`./gradlew dist` stages them in `build/dist/` and the workflow uploads them to the Release.

| Asset | Usable how |
| --- | --- |
| `groovy-nullchecker-<v>.jar` | dropped straight onto a compile classpath — no repository, no coordinates |
| `groovy-nullchecker-<v>-sources.jar` | for an IDE, or reading the port in context |
| `groovy-nullchecker-<v>-javadoc.jar` | API reference |
| `nullchecker-gradle-plugin-<v>.jar` | **not** used directly: Gradle needs a plugin marker in a repository, so the plugin is installed by id |
| `nullchecker-gradle-plugin-<v>-{sources,javadoc}.jar` | same caveat |

`.asc` signatures and `.module` metadata are deliberately not attached. They only mean
something to a consumer resolving through a Maven repository; on a Release page they invite
someone to drop a `.module` file into a `libs/` directory, where it does nothing.

The plugin module's javadoc jar is empty: Gradle's `javadoc` task reads Java sources, and that
module is written in Groovy. The jar still exists, which is what Central requires. The
checker's javadoc jar has the same limitation but is non-empty because the module also
contains one Java source.

## Consumers before a public release

If the artifacts are not available from any reachable repository, the plugin fails the build
with exit code 2 rather than reporting a clean result. That is intentional: an unresolvable
tool must never look like a passing check.
