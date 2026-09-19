# Release checklist

1. Choose a version in `gradle.properties`; use a clean, reviewable working tree.
2. Update [CHANGELOG.md](../CHANGELOG.md) with user-visible changes and compatibility requirements.
3. Run `./gradlew clean build` with Java 25.
4. Confirm the client JAR and standalone-host ZIP use the intended version and Minecraft target.
5. Verify the JAR contains `LICENSE_monocle`, bundled dependency notices, font notices, and registered nested libraries. The build performs this check automatically.
6. Review `git diff --check`, automated test output, and any live-test notes. Do not describe simulated Minecraft behavior as live-tested.
7. Install the exact built JAR on every participating worker and update the standalone host when its protocol or coordinator changed.
8. Run a short live smoke test: launch, join, open the GUI, connect a worker, start/pause/resume/cancel a small job, and inspect recovery state.
9. Commit and push the source checkpoint before distributing binaries.
10. Create the release tag only after the smoke test; let GitHub Actions build the release artifacts and checksums from that tag.

Generated `build/` directories are disposable. `clean build` is the supported way to remove stale versions and reproduce current artifacts.
