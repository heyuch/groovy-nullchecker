# Releasing

Two artifacts are published independently:

| Artifact | Coordinates | Built from |
| --- | --- | --- |
| The checker | `io.github.heyuch:groovy4-nullchecker` | this build |
| The Gradle plugin | `io.github.heyuch:nullchecker-gradle-plugin`, plus the plugin marker `io.github.heyuch.groovy4-nullchecker.gradle.plugin` | `nullchecker-gradle-plugin/` (a separate included build) |

The plugin is a separate build on purpose: it depends on the checker and is versioned on its
own, so the checker's build stays a plain library.

## Version

The version lives in two places today and they must agree:

- `gradle.properties` → `nullcheckerVersion` (used by this build)
- `nullchecker-gradle-plugin/build.gradle` → `version`

The plugin's default `toolVersion` (in `NullCheckPlugin`) must also point at the version of
the checker being released.

> Worth collapsing into one source of truth before the first release: the plugin build has no
> access to this build's `gradle.properties`, so they are duplicated rather than derived.

## Before publishing

```bash
# The checker's own verification. This is the gate that matters: every rule must be
# exercised by a fixture that is reported, and every guard form by one that stays silent.
./gradlew clean check

# Compare against Apache Groovy 6's NullChecker over the same corpus, and diff the results.
# One divergence is expected and documented in PORT.md §4.1; anything else is a regression.
./gradlew dualRun

# The plugin, which drives real Gradle builds through TestKit.
./gradlew -p nullchecker-gradle-plugin clean check
```

## Publish to the local Maven repository

Useful for trying the artifacts in another project before releasing:

```bash
./gradlew publishToMavenLocal
./gradlew -p nullchecker-gradle-plugin publishToMavenLocal
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
`id 'io.github.heyuch.groovy4-nullchecker' version '0.1.0'`. Without a version Gradle reports
"plugin dependency must include a version number", which reads like the plugin is missing.

Publishing the same version again does not invalidate Gradle's dependency cache. Consumers
must run `./gradlew --refresh-dependencies`.

## Publish to Maven Central

Both artifacts go through the standard `maven-publish` flow. What still needs to be added to
`build.gradle` for each:

1. **A repository** — Sonatype's OSSRH or the Central Portal:

   ```groovy
   publishing {
       repositories {
           maven {
               name = 'central'
               url = 'https://...'
               credentials { /* from environment or gradle.properties, never committed */ }
           }
       }
   }
   ```

2. **A signing configuration** for the `maven` publication, since Central requires signatures:

   ```groovy
   plugins { id 'signing' }
   signing { sign publishing.publications.maven }
   ```

3. **POM metadata** — Central requires more than the current POM carries: `url`, `licenses`,
   `developers` and `scm`. The Apache 2.0 license is already reflected in the source headers.

Then:

```bash
./gradlew publish
./gradlew -p nullchecker-gradle-plugin publish
```

Credentials must come from the environment or an untracked properties file. Nothing secret
should be committed.

## Gradle Plugin Portal

The plugin marker is already produced by the `java-gradle-plugin` plugin. Publishing to the
Plugin Portal needs the `com.gradle.plugin-publish` plugin and its own credentials; the
artifact coordinates above do not change.

## Consumers before a public release

If the artifacts are not available from any reachable repository, the plugin fails the build
with exit code 2 rather than reporting a clean result. That is intentional: an unresolvable
tool must never look like a passing check.
