<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JenkSpotter Changelog

## [Unreleased]

## [0.8.0] - 2026-11-09

First version in the repository. Highlights:

### Added

- **Several Jenkins servers** with Basic auth (user name + API token). Tokens live in the IDE password safe; the IDE's
  proxy and certificate settings are used. Each server can be switched on and off
- **Builds you care about**: builds you started anywhere (browser, CLI, IDE, found via the queue and the executors),
  pinned jobs, Jenkins views, and builds of the branch checked out in the project. Follow-up builds are nested below
  the build that started them, pending follow-ups of freestyle chains are shown while a build runs
- **Build details**: pipeline stages, failed tests (go to test, run locally), changes, artifacts (download), live
  console log; open builds in the classic UI, Blue Ocean or Pipeline Graph View
- **Pipeline approvals**: a notification with the proceed button of an `input` step, answer or abort it from the IDE
- **Jobs**: fuzzy search (same matcher as RepoLeap) over all jobs incl. folders and multibranch pipelines, parameter
  icon, follow-up job count, job info, pin from a pasted URL
- **Filters**: regular expression chips (hide matching / show only matching) with a builder for people who do not
  write regular expressions, and a folder tree to hide whole folders with everything inside
- **Build with parameters**: string, text, boolean, choice, password, Active Choices (also dependent ones),
  checkbox lists, credentials and run parameters; values are remembered per job, Rebuild reuses the previous ones
- **Notifications**: balloons that hide themselves after a configurable time, builds finishing together are
  combined, sounds, OS notification while the IDE is in the background, status bar widget
- **Customizable builds table**: columns can be switched on and off, reordered and resized
- **Wizarding Mode**: optional Harry Potter flavoured texts and sounds
- Adaptive, network friendly polling (gzip, compact requests, intervals follow the build's estimated duration)
