# Contributing to BidNet

Use one small issue and one reviewed pull request per change. The current single Maven module is sufficient for the foundation stage.

## Local setup

Install JDK 21, Maven, and Git. Point JAVA_HOME to your own JDK installation; do not put a machine-specific installation path in project files.

From the repository root, on either Windows or macOS:

```text
java -version
mvn -version
mvn --batch-mode --no-transfer-progress verify
```

The current ProjectSetupTest is a test-runner smoke check. It does not test auction behavior. Add behavior tests with each new feature. A green CI run means the configured checks passed; a reviewer must still assess the requirements and code.

Do not commit target folders, IDE workspace files, credentials, or local logs. Match filename capitalization exactly. The .gitattributes file controls shared line endings.

## Issue and branch workflow

1. Open an issue with a short goal and observable acceptance criteria.
2. Add it to the BidNet Rebuild project and choose one owner.
3. Create a branch from an up-to-date main. Replace the example issue number and branch name as needed:

```text
git switch main
git pull --ff-only
git switch -c feature/7-bid-validation
```

4. Implement the issue and appropriate tests. Run Maven verify locally.
5. Stage only the intended files, inspect the diff, commit, and push the feature branch.
6. Open a pull request targeting main and include Closes #7 in its description.
7. Ask another teammate to review. Address comments and require both CI jobs to pass before merging.
8. Squash and merge after approval, then update local main before starting another issue.

## Project board

Create a private project named BidNet Rebuild under the repository owner's profile, give all three teammates access, and link the repository. Its visibility can remain private even though the code repository is public.

Use these Status values:

Backlog -> Ready -> In Progress -> In Review -> Done

Enable the built-in closed-issue automation to move closed issues to Done. Add repository issues to the board; project cards alone do not test code.

## First setup issue

Title: Configure the team workflow and cross-platform CI

Acceptance criteria:

- A PR runs Java 21 Maven verify on Windows and macOS.
- The repository defines consistent line endings.
- Pull requests have a template and linked issue.
- The team has documented local build and review instructions.
- After the first successful GitHub run, main requires both CI jobs and one approving review.

## Repository owner setup

Invite the other two teammates as collaborators. After the first CI run creates the check names, protect main using branch protection or a ruleset:

- Require a pull request and one approving review.
- Dismiss stale approvals when new commits are pushed.
- Require both Build and test CI checks.
- Require review conversations to be resolved.
- Disable force pushes and apply the rule to administrators/bypass permissions as appropriate.

The repository is public, so check every proposed commit for information that should remain private. No external AI reviewer is configured by this setup.

## Next issues

1. Define the JSON message contract and test serialization and malformed input.
2. Implement a TCP server and console client with a connection test.
3. Implement auction rules with invalid, boundary, and concurrent-bid tests.
4. Add authenticated sessions and enforce admin permissions on the server.
5. Add live updates, disconnect handling, the JavaFX client, and persistence in separate issues.
6. Add the Maven Wrapper to pin the team's Maven version.

Agree on the protocol together before implementing client and server in parallel. Use synthetic test users and data. Connect to 127.0.0.1 when the server runs on the same machine; use the server machine's reachable address for a multi-computer demonstration.

## CI references

The workflow pins official actions to these releases, verified on 7 November 2026:

- actions/checkout v6.0.2, commit de0fac2e4500dabe0009e67214ff5f5447ce83dd: https://github.com/actions/checkout/releases/tag/v6.0.2
- actions/setup-java v5.2.0, commit be666c2fcd27ec809703dec50e508c2fdc7f6654: https://github.com/actions/setup-java/releases/tag/v5.2.0
- Maven CI guide: https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven

macOS execution must be verified by GitHub Actions; a local Windows build does not validate the macOS job.
