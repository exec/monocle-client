# Repository instructions

## Keep player locations and operational data private

- Real base, stash, home, and other private location coordinates must never enter
  repository files or published artifacts. This includes current, abandoned, and
  previously leaked locations, in every dimension, including their Nether or
  Overworld equivalents.
- Keep live coordinates, stash catalogs, inventory reports, telemetry, logs,
  credentials, and host/client runtime databases outside the repository in private
  runtime storage. Do not copy them into source, comments, documentation, tests,
  fixtures, screenshots, issue/PR text, commit messages, or release attachments.
- Use independently invented synthetic coordinates for examples and tests. Label
  them explicitly as synthetic. Preserve only properties the test requires, such
  as height, bounds, spacing, or distance from the world border. Renaming a stash,
  changing a few coordinate digits, or adding a synthetic label to a real location
  does not make operational data safe to publish.
- Treat private stash names and home names as operational data too; use generic
  names in examples. Do not put real private values into a committed privacy
  scanner rule or test fixture to detect them.
- Before committing, pushing, or publishing a release, inspect the complete
  outgoing diff and any included untracked files and artifacts for private data,
  then run `python3 .github/privacy-check.py`. The scanner is a heuristic; a clean
  result does not replace manual inspection, especially for split coordinate
  fields, screenshots, archives, or generated reports.
- If private data is found, remove it from the outgoing material and tell the
  user. If it was already committed or published, identify that exposure explicitly;
  deleting the current file does not erase Git history, clones, or caches.
