/*
 * CLI driver for the Groovy 4 null checker.
 *
 * This script is a thin wrapper so the tool can be run from a checkout without
 * building a jar. The real implementation is the compiled class
 * io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli, which ships inside the
 * published artifact and can be used as a mainClass by any build tool -- keeping one
 * implementation rather than two that can drift apart.
 *
 * Usage:
 *   groovy nullcheck.groovy [--strict] [--support <dir>] [--extension <fqcn>]
 *                           [--report <file>] [--quiet] <path>...
 *
 * Exit codes: 0 = clean, 1 = findings, 2 = harness misuse.
 */

import io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli

System.exit(NullCheckCli.run(args as List<String>, System.out, System.err))
