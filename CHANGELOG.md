<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JenkSpotter Changelog

## [Unreleased]

### Added

- **Connection lights**: every configured Jenkins is shown at the bottom of the Jenkins view and on its node in the
  jobs list as connected, error (reachable, but e.g. authentication failed), not reachable, or disabled. Click for
  enable/disable, poll now and reload job list
- **Jobs list layouts**: below their Jenkins (collapsible), below Jenkins and folder, or one list with the Jenkins
  name after / in front of the job / left out; sort menu: relevance, name, folder path, recently built; pinned jobs on
  top can be switched off
- **Show Recent Builds** in the context menu of a job; how many is set in the settings (default 10)
- **Search by Jenkins name**: words also match the Jenkins name ("beta deploy" finds deploy on "Jenkins Beta"),
  `@beta` or `beta:` requires it
- **Watch a job** without pinning it: its builds are listed (scope "Watched jobs") and notified even when notifications
  are limited to your own builds
- **Go to Jenkins Job** (`Cmd+Alt+Shift+J` on macOS, `Ctrl+Alt+Shift+K` otherwise): fuzzy search popup from anywhere;
  Enter shows and selects the job in the jobs list, Shift+Enter builds it
- **Overview tab** in the build details with status, start, duration, source, causes, Jenkins, tests, description
  and all parameters
- Builds table: "Show > Folder path in job names" to show job names without their folders
- **Status filter** in the builds table: filter icon with one check box per status (running, successful, failed,
  unstable, aborted, queued), "Only Failed and Unstable" as shortcut; replaces the "Only failed" toggle
- **Show/hide per Jenkins** next to its light at the bottom of the Jenkins view hides its jobs and builds (it is still
  polled); 11 icons (eyes, small eye, light bulb, lock, star, ...), check box or menu only ("Show > Show/Hide Toggle
  per Jenkins")
- **All running builds on Jenkins** (builds table "Show" menu): lists other people's builds while they run, from the
  executor listing in one request; they leave the list when finished, are not notified and do not speed up polling.
  New scope "Running now"
- **Job icon** selectable in the jobs list ("Show > Job Icon"): 35 icons in groups, among them play arrows, magic wand,
  graduation cap, hammers, gear, package, deploy, branch, or none
- **"This project" filter chip** at the bottom of the Jenkins view: only jobs that build a Git repository of the open
  project. Jenkins' repository URLs (SCM configuration and last build) are compared with the project's remotes by
  "owner/repo", so https, ssh and Bitbucket URLs match; jobs without a known repository match by name
- **Step in a follow-up chain**: jobs triggered by other jobs show their step in front of an icon ("2 ⛓", the mirror of
  the follow-up mark "⛓ 3"); the tooltip names the job starting the chain and shows the whole chain with this job in
  bold; orange when a job before it is disabled. "Show > Step Icon": same as the follow-up icon (default), text
  "step 2/4", chain link, back arrow, step out, chevron, hierarchy, tree, dependencies, merge or lightning
- **Follow-up display** selectable ("Show > Follow-up Display"): icons with step and count (default), highlight of the
  selected job's chain with a clickable path below the list, a chain line at the left edge (jobs of a chain listed
  together), follow-up jobs nested below the triggering job (expandable), small arrows only, or tooltip only. Disabled
  jobs break the chain: dashed orange line and hollow dot from there on, beige tint instead of green in the highlight,
  "(not triggered)" next to the jobs behind them
- **Jump along a follow-up chain** in the jobs list: Alt+Up selects the job that triggers the selected one, Alt+Down
  the one it triggers (a choice when there are several); the context menu "Follow-up Chain" lists the whole chain
- **Disabled jobs** are shown struck through with "(disabled)" and cannot be started from the IDE. A follow-up chain
  with a disabled job shows how many jobs really run ("2/3", orange), the tooltip marks the disabled job and everything
  below it as not triggered, and pending follow-ups of a running build leave them out
- **Follow-up icon** selectable ("Show > Follow-up Icon"): text arrow or one of 9 icons in front of the follow-up count
- The builds table shows only the Started and Duration columns by default; the rest is in the Overview tab and can
  still be added as columns
- Build info below the builds table can be collapsed to its title row (click it)
- Filter chips and the Jenkins selection moved from the tool window header into the Jenkins view; the connection status
  line below the builds table is gone
- Builds table more compact: "Only failed" is a toolbar toggle, refresh and settings only in the tool window title
- Follow-up builds are drawn with a tree connector instead of an arrow
- Filter chips have no x any more; the "Filter" menu next to them creates filters and lists all existing ones to switch
  off, edit, change or remove
- Builds of a Jenkins that cannot be polled stay visible but greyed out, running ones with an "unknown" icon; the
  tooltip says since when the state is old
- Pinned jobs use the pin icon
- Jenkins view: search field below the Jenkins selection and the toolbar; the view cannot be dragged narrower than its
  toolbar and selection
- Durations without milliseconds

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
