![JenkSpotter - Watching your builds, so you don't have to.](art/JenkSpotter-banner-marketplace.png)

# JenkSpotter

[![Build](https://github.com/ryoga86/jenkspotter/actions/workflows/build.yml/badge.svg)](https://github.com/ryoga86/jenkspotter/actions/workflows/build.yml)

Keeps an eye on your Jenkins builds while you keep working – in IntelliJ IDEA and all other IntelliJ-based IDEs.

JenkSpotter watches the builds you care about, tells you when they finish or wait for your approval, and lets you
start, rebuild, abort and read them without leaving the IDE. It is built for big Jenkins installations: hundreds of
jobs in folders and multibranch pipelines, several servers, and a lot of builds that are none of your business.

## Features

- **Several Jenkins servers**, each with its own user name and API token (Basic auth). Tokens are kept in the IDE
  password safe, never in a settings file. The IDE's proxy and certificate settings are used. Servers can be
  switched on and off without losing their settings
- **Builds you care about**
  - builds **you** started, wherever you started them (browser, CLI, IDE) – found via the build queue and the executors
  - **pinned** jobs and **Jenkins views** of your choice
  - the builds of the **branch checked out** in the project (multibranch job with the name of the repository)
  - **follow-up builds** are nested below the build that started them; while a build runs, the follow-up jobs its
    configuration will trigger are shown as pending
- **Build details** below the table: pipeline **stages**, **failed tests** (jump to the test, run it locally),
  **changes**, **artifacts** (download) and a live **console log**
- **Approvals**: a pipeline waiting for an `input` step raises a notification with the proceed button; answer or
  abort it from the IDE
- **Jobs**: fuzzy search over all jobs including folders and multibranch pipelines (same matcher as
  [RepoLeap](https://github.com/ryoga86/repoleap)), parameterized jobs are marked, the number of follow-up jobs is
  shown, *Job Info* lists parameters, chain and latest builds. Paste a Jenkins URL (classic or Blue Ocean) into the
  search field to pin that job
- **Filters** as chips in the header: regular expressions that **hide** matching jobs or **show only** matching jobs,
  with a builder (starts with, ends with, contains, AND/OR) for people who do not write regular expressions, and a
  **folder tree** to hide whole folders with everything inside. A right click on a job offers filters for it
- **Build with parameters**: string, text, boolean, choice, password, Active Choices (also dependent ones), checkbox
  lists, credentials and run parameters. Values are remembered per job (passwords never), *Rebuild* reuses the
  values of the previous build, *Build Again with Defaults* starts fresh
- **Notifications**: balloons that hide themselves after a configurable time (failures stay by default), builds
  finishing together are combined into one, sounds, an OS notification while the IDE is in the background and a
  status bar widget that also shows the latest build of the current branch
- **Customizable builds table**: show, hide, reorder and resize columns (status, started, duration, source, server,
  parameters, tests, description)
- **Network friendly**: gzip, compact requests, polling intervals that follow the estimated duration of running
  builds and slow down while the IDE is in the background

## Usage

Open the **JenkSpotter** tool window (left: jobs, right: builds). Add your Jenkins servers in
*Settings | Tools | JenkSpotter* (the gear in the tool window header opens it). *Tools | JenkSpotter | Search Jenkins
Jobs…* jumps to the job search.

Create an API token in Jenkins under *your user | Security | API Token* and enter it together with your user name.
*Test Connection* shows under which user Jenkins knows you; builds are recognized as yours by that user id.

Optional Jenkins plugins that unlock more details: *Pipeline: Stage View* (stages), *JUnit* (failed tests),
*Pipeline Graph View* or *Blue Ocean* (for opening builds there).

## Installation

- **JetBrains Marketplace**: in the IDE, *Settings | Plugins | Marketplace*, search for **JenkSpotter**
- **Manually**: download the ZIP from [Releases](https://github.com/ryoga86/jenkspotter/releases) (or build it
  yourself, see below) and use *Settings | Plugins | ⚙ | Install Plugin from Disk…*

Requires IntelliJ Platform **2025.1** or newer and the bundled Git plugin, so it can be installed in every
IntelliJ-based IDE that ships with Git support (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, …).

## Privacy

JenkSpotter does not collect or transmit any data. It connects only to the Jenkins servers you configure. API tokens
are stored in the IDE password safe (macOS Keychain, Windows Credential Manager, Linux Secret Service or the IDE's
KeePass file). If the password safe is set to memory-only, the settings page says so, because tokens are then lost on
restart.

## Building from source

Requirements: JDK 21. Everything else is downloaded by Gradle.

```bash
./gradlew check          # unit tests + headless IDE tests
./gradlew runIde         # start a sandbox IDE with the plugin
./gradlew buildPlugin    # -> build/distributions/JenkSpotter-<version>.zip
./gradlew verifyPlugin   # IntelliJ Plugin Verifier against 2025.1, 2025.2, 2025.3, 2026.2, 2026.3 and the latest EAP
```

`-PbuildSearchableOptions=false` speeds up local builds.

Install the built plugin into your own IDE: put the plugins folder of your IDE into the git-ignored
`local.properties` and run `./gradlew installToIde`, then restart the IDE:

```properties
jenkspotter.ideaPluginsDir=/Users/me/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins
```

### Integration tests against a local Jenkins

`docker/` contains a throw-away Jenkins (JCasC) with folders, a multibranch pipeline, parameterized jobs (including
Active Choices and credentials), a chain of jobs, a pipeline with stages, failing tests, an artifact and an approval
step. Credentials are generated randomly and kept in the git-ignored `docker/.env`:

```bash
python3 docker/setup_env.py
docker compose -f docker/compose.yml up -d --build      # http://localhost:18080, bound to 127.0.0.1 only
set -a; . docker/.env; set +a                            # JENKSPOTTER_IT_URL, JENKINS_TEST_TOKEN, ...
./gradlew test
```

Jenkins needs about a minute after the start before the Active Choices scripts are approved. User `dev` is the one
the tests use; its password and API token are in `docker/.env`. Remove everything with
`docker compose -f docker/compose.yml down -v`. Without the environment variables the integration tests are skipped.

### Project structure

| Path | Content |
|---|---|
| `src/main/kotlin/…/api` | Jenkins REST client, JSON mapping, URL helpers, build form parser |
| `src/main/kotlin/…/core` | polling (`BuildMonitorService`), build store, job index, fuzzy matcher, filters |
| `src/main/kotlin/…/settings` | persistent settings, password safe access, settings page |
| `src/main/kotlin/…/ui` | tool window, builds and jobs panels, dialogs, status bar widget |
| `src/main/kotlin/…/notify` | notifications and sounds |
| `docker/` | local test Jenkins |
| `tools/` | generators for the icons (`generate_icons.py`) and sounds (`generate_sounds.py`) |
| `art/` | plugin icon (`pluginIcon.svg`), Marketplace thumbnail and banner |

The plugin is compiled against 2025.2; the oldest supported line (2025.1) is covered by the plugin verifier. Releases
are described in [RELEASING.md](RELEASING.md).

## Support

JenkSpotter is free and open source. If it saves you time, I'd be happy about a coffee:

<a href="https://ko-fi.com/ryoga86"><img src="https://storage.ko-fi.com/cdn/kofi5.png?v=6" alt="Buy me a coffee at ko-fi.com" height="36"></a>

Bugs and ideas: [GitHub issues](https://github.com/ryoga86/jenkspotter/issues)

## License

[MIT](LICENSE) for the code, see [NOTICE](NOTICE) for the rest. The sounds are synthesized by
`tools/generate_sounds.py`.

JenkSpotter is not affiliated with or endorsed by the Jenkins project. Jenkins® is a registered trademark of
LF Charities Inc.
