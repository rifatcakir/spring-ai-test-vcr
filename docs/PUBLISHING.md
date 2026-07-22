# Publishing to Maven Central

Last updated: 2026-07-21

**Status: the build side is prepared and verified. A GPG key now exists and its public
half is published. A `central` server entry exists in local `settings.xml`. Nobody has
actually published anything to Central yet.** Every step below that requires a secret,
a key, or an account is written as something *you* do, by hand, outside of anything an
agent should be doing on your behalf — see "Why this is a manual walkthrough" at the
bottom.

## What's already done (in this repo)

- `LICENSE` — Apache-2.0, full text, at the repo root.
- `pom.xml` — `name`, `description`, `url`, `licenses`, `developers` (including a public
  contact email), `scm` (the metadata Central rejects a release without). Both `TODO`s
  that used to be here are resolved: the repo URL/`scm` are confirmed against the real
  remote, and a contact email is listed.
- The GitHub repository exists and is public: <https://github.com/rifatcakir/spring-ai-test-tools>,
  matching `pom.xml`'s `url`/`scm` exactly. `main` is pushed and up to date.
- CI (`.github/workflows/ci.yml`) is live and green on `main` — unit tests run on every
  push/PR with no Docker required, and a separate scheduled/manual job runs the real
  Testcontainers + Ollama end-to-end proof without blocking ordinary PRs on it.
- A `release` Maven profile (`-Prelease`) adding `maven-source-plugin` (sources jar),
  `maven-javadoc-plugin` (javadoc jar), `maven-gpg-plugin` (artifact signing, unconfigured
  — see below), and `central-publishing-maven-plugin` (upload to the Central Portal).
  Isolated in its own profile so an ordinary `mvn test` / `mvn install` never needs a GPG
  key — nothing about local development changed.
- **Verified**: `mvn -Prelease package -DskipTests` actually runs `javadoc:javadoc`
  end-to-end and produces `target/spring-ai-test-vcr-0.1.0-javadoc.jar` and
  `-sources.jar` alongside the main jar, with **zero Javadoc errors** (66 warnings, all
  "no comment" / "no @param" / "no @return" on getters, setters, and constructors that
  inherit their meaning from the class-level Javadoc — cosmetic, not blocking). This is
  the step most projects find out is broken only when they try to actually release, so it
  was checked here rather than assumed.
- **Version bumped: `0.1.0-SNAPSHOT` → `0.1.0`.** Central rejects SNAPSHOT versions
  outright, so this had to happen before a real release regardless. Verified afterward
  with a fresh `mvn clean verify -Prelease`: 47/47 tests still green, all four required
  artifacts produced and signed under the new, non-SNAPSHOT filenames (see below).
- **GPG key generated and published.** Ed25519/Cv25519 (`sec ed25519` + `ssb cv25519`,
  not RSA — a modern, equally valid choice; this doc's earlier draft suggested RSA 4096
  as *a* safe default, not the only one). Fingerprint
  `E4BD 1A1A 18AE 4942 E018 CC15 4218 547C 6455 F6B9`, UID `rifat cakir
  <rifatcakira@gmail.com>`, expires **2026-07-21 → 2029-07-21** (3 years — note the date
  somewhere durable; a signing key that's quietly expired is a nasty surprise on a future
  release day). The public half was sent to `keyserver.ubuntu.com` and independently
  re-fetched from there to confirm it actually propagated, not just that the send command
  exited zero.
- **A `central` server entry exists in local `settings.xml`** with the right `<id>`. Its
  actual username/password were not inspected or verified as real, valid tokens — only
  that the block is present and correctly named, which is as far as this can be checked
  without reading a credential.
- **Not verified, and cannot be from here**: whether `maven-gpg-plugin`'s `sign` goal
  actually produces a valid signature when Maven runs it (needs an interactive passphrase
  unlock — see step 5 below for why that has to be run by hand), or whether
  `central-publishing-maven-plugin`'s upload succeeds with the current token.

## What you need to do, in order

### 1. Confirm the namespace: `io.github.rifatcakir`

Sonatype's Central Portal grants `io.github.<your-github-username>` namespaces tied to
your GitHub identity.

1. Sign in to <https://central.sonatype.com> **with your GitHub account** (not a separate
   email/password signup). When you authenticate this way, the Portal already knows which
   GitHub account you own, and `io.github.rifatcakir` is granted and shown as **verified
   automatically** — there is nothing further to do for the namespace itself.
2. Double-check under the Portal's "Namespaces" page that `io.github.rifatcakir` shows a
   verified status. If it doesn't for some reason (e.g. you signed up with email/password
   first and only linked GitHub afterward), *then* fall back to the manual path: request
   the namespace, get a **verification key** from the Portal, create a new *public* GitHub
   repository literally named after that key (`github.com/rifatcakir/<verification-key>`),
   wait for Sonatype to detect it, confirm "Verified," then delete that throwaway
   repository — it has no further purpose.

Don't create a throwaway verification-key repository pre-emptively — it's only needed if
step 1's GitHub sign-in doesn't already leave the namespace verified, which is the common
case, not the exception.

This step is entirely about your GitHub account, not this codebase. Nothing in this repo
needs to change for it.

### 2. Generate a GPG key pair and publish the public half — done

Fingerprint `E4BD 1A1A 18AE 4942 E018 CC15 4218 547C 6455 F6B9` (key ID
`4218547C6455F6B9`), UID `rifat cakir <rifatcakira@gmail.com>`, Ed25519/Cv25519,
expires 2029-07-21. Sent to `keyserver.ubuntu.com` and confirmed retrievable from there.

One optional follow-up: `keys.openpgp.org` is a separate, independently-run keyserver
that doesn't automatically mirror `keyserver.ubuntu.com` — if Central's verification (or
a future consumer verifying a signature) checks that server specifically and can't find
the key, send it there too:

```bash
gpg --keyserver keys.openpgp.org --send-keys 4218547C6455F6B9
```

`keys.openpgp.org` additionally requires confirming the UID's email via a link it emails
you before the identity (not just the raw key) shows up in searches there — the key
itself still becomes fetchable immediately either way.

Keep the private key and its passphrase exactly where you keep other secrets — not in
this repository, not in a commit, not pasted into a chat. A revocation certificate was
generated alongside the key (GnuPG does this automatically) — store that somewhere
recoverable too, separately from the key itself; it's the only way to invalidate this
key later if it's ever lost or compromised.

### 3. Get Sonatype token credentials

From <https://central.sonatype.com>, under your account, generate a **user token**
(a username/password pair distinct from your login password, scoped to publishing).

### 4. Put credentials in `~/.m2/settings.xml` — not in this repo

**A `central`-id server entry already exists in local `settings.xml`.** Its presence and
`<id>` were checked; the actual username/password were deliberately not read or verified
as real tokens (nothing here should be inspecting credential values). If it's not filled
in with real Sonatype token values yet, or needs redoing, here's the shape:

`pom.xml`'s `central-publishing-maven-plugin` configuration references
`<publishingServerId>central</publishingServerId>`, which means Maven looks for a
`<server>` with `id` `central` in **your local `settings.xml`**, never in the repo. Add
an entry shaped like this (placeholders — fill in your own token values, do not commit
this file anywhere):

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_SONATYPE_TOKEN_USERNAME</username>
      <password>YOUR_SONATYPE_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

If you'd rather not put a plaintext token on disk at all, Maven supports encrypting
`settings.xml` passwords with `mvn --encrypt-password` and a master password — worth
doing if this machine is shared or backed up somewhere you don't fully control.

### 5. A real release build

Once 1–4 are done — and the version bump below is already committed:

```bash
mvn clean verify -Prelease
```

The version is already `0.1.0`, not `0.1.0-SNAPSHOT` — Central rejects SNAPSHOT versions
outright, so this was bumped (`mvn versions:set -DnewVersion=0.1.0`) and verified ahead of
time, rather than left as a step to remember mid-release.

`verify` (not just `package`) matters here: `maven-gpg-plugin`'s `sign` execution is
bound to the `verify` phase deliberately, so this is the first command that will actually
attempt to sign anything — if your key isn't set up right, this is where you'll find out,
before anything is uploaded.

**Run this one yourself, in your own terminal.** Signing needs to unlock the private key,
which — unless `gpg-agent` already has it cached from something else you did moments
before — means a `pinentry` prompt for the key's passphrase, either a small GUI dialog or
a terminal prompt depending on how GnuPG is configured on this machine. That prompt has
to reach *you* interactively; it can't be answered from here, and nothing about this
workflow should be typing a passphrase anywhere on your behalf. If it hangs or a dialog
appears, that's `pinentry` waiting for you, not a stuck command.

Then, to actually upload:

```bash
mvn deploy -Prelease
```

`autoPublish` is set to `false` in `pom.xml` on purpose: this uploads and validates a
bundle on the Central Portal, but does **not** publish it automatically. Go to
<https://central.sonatype.com>, find the pending deployment, review it, and click
"Publish" yourself. Once published, a version on Central is permanent — it cannot be
deleted or overwritten, only superseded by a new version. That irreversibility is exactly
why the manual click exists as the last gate rather than automating it away.

## Why this is a manual walkthrough

Every credential, key, and irreversible action from here on is something only you should
create and hold — an agent generating a GPG key, inventing Sonatype credentials, writing
real values into `settings.xml`, or running an actual `mvn deploy` would mean secrets
passing through a chat transcript and an irreversible public action (a permanent Central
release) taken without you directly in the loop at the moment it happens. This document
exists so you have the exact steps and commands; running them is deliberately left to you.
