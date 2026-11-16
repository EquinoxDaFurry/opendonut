# Contributing to OpenDonut

Thanks for looking. This document covers how to build and test the project, and the standards a change has to
meet before it can be merged. The short version: **prefer a correct, tested, boring change over a clever
untested one**, and say plainly what a change does not do.

---

## Building and testing

Requirements:

* **JDK 25 or newer.** Minecraft 26.3 and Velocity 4.3.1 both require it. Set `JAVA_HOME` before running Gradle:
  ```bash
  export JAVA_HOME=/path/to/jdk-25        # Windows: $env:JAVA_HOME = 'C:\Java\jdk25'
  ```
* No Gradle installation: the wrapper downloads the pinned 9.8.2 distribution.

```bash
./gradlew build              # compiles everything and runs the tests
./gradlew test               # tests only
./gradlew :common:test --tests '*RegionLookupTest*'
./gradlew :common:toolsJar   # the administrator CLI
./gradlew artifacts          # all three artifacts, no tests
```

Artifacts land in `build/libs/` of each module. `./gradlew build` must be clean **including warnings** — the build
enables `-Xlint:all` deliberately, and a new warning is treated as a defect to fix or suppress with a comment
explaining why.

## Where code belongs

| Change | Module |
|---|---|
| Configuration schema, validation, coordinate arithmetic, region lookup, seed handling, bridge protocol | `common` |
| Minecraft-facing behaviour: ticks, packets, commands, teleports, world checks | `fabric-mod` |
| Proxy-facing behaviour: routing decisions, backend verification, connection requests, proxy commands | `velocity-plugin` |

**`common` must never import Minecraft, Fabric or Velocity classes.** It is loaded by both hosts *and* runs as a
standalone CLI; a single Minecraft import would break the second use and make the region engine untestable. If a
change needs `net.minecraft` in `common`, it belongs in `fabric-mod` instead.

## Test requirements

A pull request that changes behaviour needs tests for that behaviour. Concretely:

* **Region and coordinate changes** need cases for the boundaries, not just the middle: one block either side of
  every edge, negative coordinates, and the shared-edge case. `RegionLookupTest` and
  `SlabRegionIndexConsistencyTest` show the patterns.
* **Anything in the index** is additionally cross-checked against `LinearRegionIndex`, the deliberately naive
  reference implementation. If you change the index, the randomised consistency test must still pass; if it
  fails, the failure prints the seed that reproduces it.
* **Validation changes** need a test asserting on the `IssueCode` and the reported `path`, not on the message
  text — messages are meant to be improved, codes are not.
* **Protocol changes** need a round trip for every message type, and a negative case for each way a frame can be
  wrong. `BridgeCodecTest` flips every single bit of a frame, which is the level of paranoia a channel that
  clients can write to deserves.
* **A new shipped configuration file** (in `config/` or `examples/`) must be covered by
  `ShippedConfigurationsTest`. Shipped configurations are documentation; documentation that does not parse is
  worse than none.

Fixed regression tests are preferred over new abstractions. If you fix a bug, add the test that would have caught
it — and if the bug was in the test's expectations rather than the code, say so in the commit message.

## Code standards

* **Comment the why, not the what.** `// increment i` is noise; `// the guard exists because a failed transfer
  would otherwise re-trigger every tick` is the kind of comment this codebase is written in. Assume the reader
  knows Java and does not know this problem domain.
* **Javadoc on public types and non-obvious methods**, including `@param`, `@return` and `@throws`. Document the
  contract and the failure behaviour. The build's `-Xlint` settings are not the only standard: unused imports
  should not exist, and a `@SuppressWarnings` needs a reason.
* **Prefer explaining a limitation to hiding it.** "This does not synchronise inventories" belongs in the javadoc
  of the class that does not do it, and in `docs/architecture.md`.
* **Immutability by default.** Records for value types, `List.copyOf` for collections that leave a class, final
  fields. The configuration is shared between threads; nothing in it may be mutable.
* **No silent failure.** Catch blocks either handle the problem or log it with enough context to act on. An empty
  catch, or a swallowed `InterruptedException`, will be sent back in review.
* **Fail with a message an operator can act on.** Name the file, the value and the fix. Compare the error messages
  in `ConfigParser` and `BridgeSecret` for the intended tone.
* **Do not invent dependency versions.** Every version in `gradle.properties` was verified against upstream
  metadata; see `docs/architecture.md` for the method and add your evidence when you bump one.
* **Match the surrounding style**: tabs for indentation, braces on the same line, one class per file, and the
  existing ordering of members (fields, constructor, static factories, public API, private helpers).

## Changing configuration schema

Adding a key is a compatible change; renaming or removing one is not. When you add a key:

1. Add it to the model record, with validation in the compact constructor.
2. Parse it in `ConfigParser`, add the key name to the relevant `*_KEYS` set (otherwise it is reported as unknown),
   and record its line in the `SourceMap` so findings can point at it.
3. Document it in `config/opendonut.example.yml` **and** `docs/configuration.md`.
4. Add tests for the default and for a bad value.

Unknown keys are reported as warnings by default, which means a forgotten step 2 shows up as a warning in a test
rather than as silence.

## Changing the bridge protocol

The protocol is versioned on the wire. Adding a message type is compatible; changing the meaning or the field
order of an existing one is not, and requires bumping `BridgeCodec.PROTOCOL_VERSION` and documenting the
migration. Any change here needs:

* a round trip test for the message,
* a negative test for a malformed payload of that type,
* an update to the frame layout table in `docs/architecture.md` if the envelope changes.

Remember that both sides must be updated together: a backend on the old build is rejected with
`UNSUPPORTED_VERSION` rather than misread, which is the intended behaviour.

## Local integration testing

`tests/README.md` describes the manual procedure: two Fabric backends, a Velocity proxy, and the specific
observations that constitute a pass. It needs a real Minecraft client, so it cannot be automated here. If your
change touches the transfer path, say in your pull request which of those observations you performed and what you
saw — or say that you could not run them, which is fine and better than implying otherwise.

## Commits and pull requests

* One logical change per commit. A commit that reformats a file *and* fixes a bug is hard to review.
* Write commit messages in the imperative mood and explain the reason: "Reject empty regions, which were silently
  accepted because `Integer` comparison used reference equality" tells a reviewer what happened and why.
* In the pull request, state what you changed, how you tested it, and what you did **not** do. If a change is a
  step towards something bigger, describe the intermediate state honestly.
* Keep refactors separate from behaviour changes.

## Reporting bugs

Use the issue template. For routing problems, include:

```bash
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml
```

and the `OpenDonut` log lines plus `/opendonut` output from both the proxy and the affected backends.

**Never paste the bridge secret or `forwarding.secret`.** The fingerprints logged at startup are the intended way
to compare two machines.

## License

By contributing you agree that your contribution is licensed under the MIT License, as stated in
[LICENSE](LICENSE).
