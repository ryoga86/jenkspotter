<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JenkSpotter Changelog

## [Unreleased]

### Fixed

- The Stages tab showed an empty log for the selected stage (on IntelliJ 2025.2 and possibly other versions); the
  log is shown again and its error lines are highlighted

## [1.0.0] - 2026-11-24

First public release.

### Added

- **Several Jenkins servers** with user name and API token (Basic auth). Tokens live in the IDE password safe, the
  IDE's proxy and certificate settings are used. Each server can be switched on and off and has a connection light
  (green connected, red error such as a failed sign-in, grey not reachable). A sign-in page of a proxy or single
  sign-on in front of Jenkins is reported as such; the API token is only ever sent to the Jenkins itself
- **Builds you care about**: builds you started anywhere (browser, CLI, IDE, found via the queue and the executors),
  pinned and watched jobs, Jenkins views and the builds of the branch checked out in the project. Follow-up builds are
  nested below the build that started them, pending follow-ups of freestyle chains are shown while a build runs.
  Optionally also other people's running builds and the latest finished builds of the whole Jenkins
- **Builds table**: sortable columns that can be switched on and off, reordered and resized, pages of 25 to 200
  builds (a build and its follow-ups stay together), striped rows per build chain. Builds of a Jenkins that cannot be
  polled stay visible, greyed out
- **Build info**: overview (status, start, duration, source, causes, tests, description, parameters), stages with
  nested and parallel stages as a tree and the log of the selected stage, failed tests (go to the test, run it
  locally), changes, artifacts (download), recent builds and the live console log. Builds open in the classic UI,
  Blue Ocean or Pipeline Graph View
- **Why a build failed**: resting the mouse on a failed or unstable build shows the failed stage, the failed tests and
  the error lines of the log; the "Reason" tab shows the same, each line opens the log at that place. Error lines are
  highlighted in logs, F2 / Shift+F2 jump between them
- **Pipeline approvals**: a notification with the proceed button of an `input` step, answer or abort it from the IDE
- **Notifications**: balloons that hide themselves after a configurable time, builds finishing together are combined,
  sounds, an OS notification while the IDE is in the background and a status bar widget
- **Jobs list**: fuzzy search over all jobs including folders and multibranch pipelines, also by Jenkins name
  (`@beta`); grouped below Jenkins and folder or as one list, several sort orders, job types told apart by icon,
  disabled jobs struck through. A double-click on a folder lists only what is inside; a pasted Jenkins URL pins that
  job
- **Go to Jenkins Job** (`Cmd+Alt+Shift+J` on macOS, `Ctrl+Alt+Shift+K` otherwise): fuzzy search popup from anywhere;
  Enter shows the job in the jobs list, Shift+Enter builds it
- **Call graph**: trigger counts behind a job ("2 > 3": triggered by 2 jobs, triggers 3). A click shows every job
  leading to it and everything it triggers, as a graph, a tree or complete paths, with one tab per workflow and
  "Copy as Mermaid Diagram". Alt+Up / Alt+Down jump along follow-ups in the jobs list
- **Filters** as chips with three states (show only, hide, off): per status, mine, current branch, waiting for input,
  pinned, watched, views and "This project" (jobs that build a Git repository of the open project). Regular
  expressions with a builder for people who do not write them, and a folder tree to hide whole folders
- **Build with parameters**: string, text, boolean, choice, password, Active Choices (also dependent ones), checkbox
  lists, credentials and run parameters. Values are remembered per job, Rebuild reuses the previous ones
- Adaptive, network friendly polling (gzip, compact requests, intervals follow the build's estimated duration); the
  job index is read in the background, so the jobs list stays fluent with thousands of jobs

[Unreleased]: https://github.com/ryoga86/jenkspotter/compare/1.0.0...HEAD
[1.0.0]: https://github.com/ryoga86/jenkspotter/commits/1.0.0
