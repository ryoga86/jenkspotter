# Releasing JenkSpotter

Internal checklist for releases on the [JetBrains Marketplace](https://plugins.jetbrains.com). The setup is the same
as for [RepoLeap](https://github.com/ryoga86/repoleap).

## Status

| Item          | Value                                                                                                   |
|---------------|---------------------------------------------------------------------------------------------------------|
| Name / ID     | `JenkSpotter` / `de.pdenis.jenkspotter` (Marketplace ID: known after the first upload)                  |
| Version       | `1.0.0` (`gradle.properties` → `pluginVersion`)                                                         |
| Compatibility | since-build 251 (2025.1), no until-build; needs the bundled Git plugin                                  |
| Vendor        | Peter Denis, mail@pdenis.de (`gradle.properties` → `pluginVendor` + `plugin.xml`)                       |
| License       | MIT (`LICENSE`), see `NOTICE` for the artwork and the sounds                                            |
| Signing       | Same key as RepoLeap (`~/.jetbrains-signing/repoleap/`), the build reads `jenkspotter.signing.*` from `~/.gradle/gradle.properties` |
| CI            | `.github/workflows/build.yml` + `release.yml`                                                           |

## Branches

- **`main`** = released state. README on `main` is what visitors see. Only releases (merge of `develop`) and docs
  about the current state go here.
- **`develop`** = next version. Development happens here; Dependabot pull requests target it as well
  (`target-branch` in `.github/dependabot.yml`).
- CI (`build.yml`) runs on pushes to both branches and on every pull request. A draft release is only created on
  `main`.

## First release (has to be uploaded manually)

The Marketplace only accepts uploads via Gradle/CI for plugins that exist already, so 1.0.0 goes through the web
form.

### One-time preparation

1. **Git identity for this repository**: the global git config has the work address, which would end up in the
   public history. Set the GitHub noreply address in this repository:
   ```bash
   git config user.name "Peter Denis" && git config user.email "13853329+ryoga86@users.noreply.github.com"
   ```
2. **Local signing**: add to `~/.gradle/gradle.properties` (same files and password as `repoleap.signing.*`):
   ```properties
   jenkspotter.signing.certificateChainFile = /Users/<you>/.jetbrains-signing/repoleap/chain.crt
   jenkspotter.signing.privateKeyFile = /Users/<you>/.jetbrains-signing/repoleap/private_encrypted.pem
   jenkspotter.signing.password = <content of password.txt>
   ```
3. **Branch `main`**: merge everything into `develop`, then create `main` from it and push. Make `main` the default
   branch (*Settings | General | Default branch*) like in RepoLeap.
4. **Repository public** (*Settings | General | Danger Zone | Change visibility*): the plugin description links to
   the GitHub issues, the Marketplace page links to the source code, and README badges and the Ko-fi button only
   work for a public repository. `docker/.env` and `local.properties` are git-ignored and were never committed.
5. **GitHub Actions** (*Settings | Actions | General | Workflow permissions*): **Read and write permissions** and
   **Allow GitHub Actions to create and approve pull requests** (for draft releases and changelog pull requests).
6. **Repository secrets** (*Settings | Secrets and variables | Actions*), same values as in RepoLeap:

   | Secret                 | Content                                                                         |
   |------------------------|---------------------------------------------------------------------------------|
   | `PUBLISH_TOKEN`        | Marketplace token (https://plugins.jetbrains.com/author/me/tokens), per account |
   | `CERTIFICATE_CHAIN`    | content of `~/.jetbrains-signing/repoleap/chain.crt`                            |
   | `PRIVATE_KEY`          | content of `~/.jetbrains-signing/repoleap/private_encrypted.pem`                |
   | `PRIVATE_KEY_PASSWORD` | content of `~/.jetbrains-signing/repoleap/password.txt`                         |

7. **Screenshots** for the Marketplace page (recommended 1280×800, no tiny text): e.g. the tool window with jobs and
   builds, the "why it failed" tooltip, the call graph, the settings page.

### Upload

On `main` (after step 3):

```bash
./gradlew clean check                                   # tests
./gradlew verifyPlugin                                  # Plugin Verifier (downloads several GB)
./gradlew patchChangelog                                # [Unreleased] -> [1.0.0] - <date>
./gradlew buildPlugin signPlugin verifyPluginSignature
```

1. Upload `build/distributions/JenkSpotter-1.0.0-signed.zip` on https://plugins.jetbrains.com/plugin/add
   - License: `https://github.com/ryoga86/jenkspotter/blob/main/LICENSE`
   - Tags: pick fitting ones from the list (e.g. build / tools integration)
2. Commit the changelog, tag, push:
   ```bash
   git commit -am "Release 1.0.0" && git tag 1.0.0 && git push && git push --tags
   ```
   The push to `main` makes `build.yml` create a draft release `1.0.0`. **Do not publish it**: that starts
   `release.yml`, which would upload 1.0.0 a second time and fail. Delete the draft (RepoLeap has no GitHub release
   for 1.0.0 either), then merge `main` back into `develop`.
3. After the approval by JetBrains, on the plugin page: Source code (`https://github.com/ryoga86/jenkspotter`),
   Issue tracker (`…/issues`), screenshots, Ko-fi under *Monetization* (not in the description, the Marketplace
   rules forbid donation links there).
4. With the Marketplace ID: add the version / downloads badges and the Marketplace link to the README (as in
   RepoLeap) and the ID to the status table above.

**Review by JetBrains:** every uploaded version is reviewed first. Status without login:
`curl -s "https://plugins.jetbrains.com/api/plugins/<ID>/updates?size=1"` → `approve: true` means public. Without an
answer after 3–4 working days: marketplace@jetbrains.com.

## Follow-up releases via GitHub Actions

1. On `develop`: describe the changes under `## [Unreleased]` in `CHANGELOG.md`, raise `pluginVersion` in
   `gradle.properties` (`1.1.0`; pre-releases like `1.1.0-beta.1` go to the `beta` channel automatically).
2. Open a pull request `develop` → `main`, wait for CI, merge → `build.yml` creates a **draft release** on `main`.
3. Check the draft on GitHub and publish it (only visible when logged in as `ryoga86`; do **not** create a second one
   via "Draft a new release") → `release.yml` patches the changelog, signs, uploads to the Marketplace, attaches the
   ZIPs to the release and opens a pull request that only changes `CHANGELOG.md` → merge it.
4. Bring `main` back into `develop`, so the patched changelog is there as well:
   ```bash
   git checkout develop && git pull && git merge origin/main && git push
   ```

Every new version is reviewed by JetBrains again before it is public.

### Alternatively locally

```bash
./gradlew clean check
./gradlew verifyPlugin
./gradlew patchChangelog
./gradlew publishPlugin               # token: jenkspotter.publishToken=perm:… in ~/.gradle/gradle.properties
git commit -am "Release x.y.z" && git tag x.y.z && git push && git push --tags
```

## Notes

- **Never change after the first release:** the plugin ID, the action IDs (`JenkSpotter.SearchJobs`,
  `JenkSpotter.GotoJob`, `JenkSpotter.Refresh`, `JenkSpotter.Diagnose`), `@State(name = "JenkSpotterSettings")` /
  `jenkspotter.xml`, the password safe service name in `TokenStore` and the notification group IDs – otherwise users
  lose their settings, API tokens, own shortcuts or notification settings.
- **Signing key** (`private_encrypted.pem`, `chain.crt`, `password.txt`): shared with RepoLeap, backed up in the
  password manager, never committed.
- **Artwork:** `art/pluginIcon.svg` is the plugin icon (`tools/generate_icons.py` copies it to `META-INF` and
  draws the 16px icons), `art/plugin-marketplace.svg` is the round thumbnail for the Marketplace page. The banner
  `art/JenkSpotter-banner-marketplace.png` is shown in the README and loaded live from `main` by the plugin
  description (`raw.githubusercontent.com/ryoga86/jenkspotter/main/art/…`): do not rename or delete it while a
  published version uses it, and keep the repository public.
- **Plugin Verifier**: `gradle/verifier-ignored-problems.txt` hides one known false positive on 2026.3
  (`com.intellij.dvcs.repo` not found, see the comment in `build.gradle.kts`). The Marketplace may still list it
  for 2026.3 until JetBrains updates its verifier. Remove the entry when a newer verifier no longer reports it.
