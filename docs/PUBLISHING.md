# Publishing to Maven Central

Last updated: 2026-07-19

**Status: the build side is prepared and verified as far as that's possible without
credentials. Nobody has actually published anything yet, and no GPG key or Sonatype
account exists as far as this document's author knows.** Every step below that requires
a secret, a key, or an account is written as something *you* do, by hand, outside of
anything an agent should be doing on your behalf — see "Why this is a manual walkthrough"
at the bottom.

## What's already done (in this repo)

- `LICENSE` — Apache-2.0, full text, at the repo root.
- `pom.xml` — `name`, `description`, `url`, `licenses`, `developers`, `scm` (the metadata
  Central rejects a release without). Two `TODO` comments are left in `pom.xml` for you to
  resolve before a real release: confirming the inferred GitHub repo URL is actually
  correct, and deciding whether to publish a contact email.
- A `release` Maven profile (`-Prelease`) adding `maven-source-plugin` (sources jar),
  `maven-javadoc-plugin` (javadoc jar), `maven-gpg-plugin` (artifact signing, unconfigured
  — see below), and `central-publishing-maven-plugin` (upload to the Central Portal).
  Isolated in its own profile so an ordinary `mvn test` / `mvn install` never needs a GPG
  key — nothing about local development changed.
- **Verified**: `mvn -Prelease package -DskipTests` actually runs `javadoc:javadoc`
  end-to-end and produces `target/spring-ai-test-vcr-0.1.0-SNAPSHOT-javadoc.jar` and
  `-sources.jar` alongside the main jar, with **zero Javadoc errors** (66 warnings, all
  "no comment" / "no @param" / "no @return" on getters, setters, and constructors that
  inherit their meaning from the class-level Javadoc — cosmetic, not blocking). This is
  the step most projects find out is broken only when they try to actually release, so it
  was checked here rather than assumed.
- **Not verified, and cannot be from here**: whether `maven-gpg-plugin`'s `sign` goal or
  `central-publishing-maven-plugin`'s upload actually succeed — both need a real key and
  real credentials, neither of which exist yet.

## What you need to do, in order

### 1. Confirm the namespace: `io.github.rifatcakir`

Sonatype's Central Portal grants `io.github.<your-github-username>` namespaces tied to
your GitHub identity.

1. Sign in to <https://central.sonatype.com> with your GitHub account (or link it if you
   signed up another way).
2. Request the namespace `io.github.rifatcakir` from the Central Portal's "Namespaces"
   page.
3. **If you signed in with GitHub, this is often approved automatically** — Sonatype can
   verify you own the `rifatcakir` GitHub account directly.
4. If it isn't automatic, the Portal gives you a **verification key**. Create a new
   *public* GitHub repository literally named after that key
   (`github.com/rifatcakir/<verification-key>`), wait for Sonatype to detect it, confirm
   the namespace shows as "Verified," then delete that throwaway repository — it has no
   further purpose.

This step is entirely about your GitHub account, not this codebase. Nothing in this repo
needs to change for it.

### 2. Generate a GPG key pair and publish the public half

Central requires every artifact to be signed. Nothing in this repo can do this for you —
a signing key must be something only you hold.

```bash
# Generate a key. RSA 4096 is the safe conventional choice; use a real passphrase.
gpg --full-generate-key

# Find the key ID you just created.
gpg --list-secret-keys --keyid-format LONG

# Publish the *public* key so Central's own verification can find it. Any of these
# keyservers is fine; keys propagate between them.
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <YOUR_KEY_ID>
```

Keep the private key and its passphrase exactly where you keep other secrets — not in
this repository, not in a commit, not pasted into a chat.

### 3. Get Sonatype token credentials

From <https://central.sonatype.com>, under your account, generate a **user token**
(a username/password pair distinct from your login password, scoped to publishing).

### 4. Put credentials in `~/.m2/settings.xml` — not in this repo

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

Once 1–4 are done:

```bash
# Bump the version first — 0.1.0-SNAPSHOT cannot be released as-is; Central rejects
# SNAPSHOT versions outright. Edit pom.xml's <version> to 0.1.0, commit that separately.

mvn clean verify -Prelease
```

`verify` (not just `package`) matters here: `maven-gpg-plugin`'s `sign` execution is
bound to the `verify` phase deliberately, so this is the first command that will actually
attempt to sign anything — if your key isn't set up right, this is where you'll find out,
before anything is uploaded.

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
