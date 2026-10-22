# Release checklist

Minor versions are named releases, beginning with **0.14.0 — The Reckoning Update**.
Set `release_name` in `gradle.properties` to a short name reflecting the milestone,
and use it in the changelog heading and GitHub release title. Patch versions keep
their minor series' name; choose a new name when the minor version changes.
Names are presentation only: tags, dependency versions, protocol identifiers and
artifact filenames continue to use the numeric version. Older releases are not renamed.

1. Choose a version and release name in `gradle.properties`; use a clean, reviewable working tree.
2. Update [CHANGELOG.md](../CHANGELOG.md) with user-visible changes and compatibility requirements.
3. Run `./gradlew clean build` with Java 25.
4. Confirm the client JAR and standalone-host ZIP use the intended version and Minecraft target.
5. Verify the JAR contains `LICENSE_monocle`, bundled dependency notices, font notices, and registered nested libraries. The build performs this check automatically.
6. Review `git diff --check`, automated test output, and any live-test notes. Do not describe simulated Minecraft behavior as live-tested.
7. Install the exact built JAR on every participating worker and update the standalone host when its protocol or coordinator changed.
8. Run a short live smoke test: launch, join, open the GUI, connect a worker, start/pause/resume/cancel a small job, and inspect recovery state.
9. Commit and push the source checkpoint before distributing binaries.
10. Create the release tag only after the smoke test; let GitHub Actions build the release artifacts and checksums from that tag.

Before staging/pushing, run `python3 .github/privacy-check.py` and review the
outgoing diff for locations and credentials. The CI guard catches obvious
runtime files, credential patterns and unlabeled large-coordinate fixtures; it
is heuristic, not a complete secret audit. Keep personal inventory reports,
stash databases and recovery journals local. Redact incident locations and use
synthetic test coordinates. A clean new commit does not remove older published
history, tags, forks, caches or release artifacts; handle any historical exposure
separately rather than claiming it was erased.

Generated `build/` directories are disposable. `clean build` is the supported way to remove stale versions and reproduce current artifacts.
