# Jenkins Build System for Unity Games

Multi-platform Jenkins Shared Library for building, testing, and distributing Unity games to 5 storefronts. Loaded via `@Library('tool_jenkins_build_system@main') _`.

## Project Structure

```
vars/
  buildUtils.groovy          # API surface, delegates to platform modules

resources/
  groovy/
    common.groovy            # Platform-agnostic: Slack, badges, versioning, SCM
    windows.groovy           # Windows agent: prereqs, Unity builds, uploads, Steam staging
    macos.groovy             # macOS agent: prereqs, Xcode, TestFlight, keychain
  Editor/Pipeline/           # Unity C# scripts copied into projects at build time
    Pipeline.cs              # Base: env vars, build options, version, artifact paths
    PipelineGooglePlay.cs    # Android AAB: keystore, ASTC, IL2CPP, debug symbols
    PipelineAmazon.cs        # Android APK: single monolithic APK for Amazon
    PipelineApple.cs         # iOS: Xcode post-build, plist, capabilities
    PipelineSteam.cs         # Windows: IL2CPP standalone build
    PipelineSwitch.cs        # Nintendo Switch build
    PipelineXbox.cs          # Xbox GDK/GameCore build (Series X|S and Xbox One)
    PipelinePS5.cs           # PlayStation 5 build
    PipelineArtifactCopy.cs  # Copy build outputs to artifact directory
  scripts/
    play_upload.rb           # Google Play AAB upload (JWT auth, resumable, symbol upload)

jenkinsfiles/                # Declarative pipelines per platform
  steam.jenkinsfile          # StandaloneWindows64 → Steam (SteamCMD)
  googleplay.jenkinsfile     # Android → Google Play (AAB via play_upload.rb)
  amazon.jenkinsfile         # Android → Amazon Appstore (APK via catalog API)
  apple.jenkinsfile          # iOS → App Store (TestFlight via Fastlane)
  switch.jenkinsfile         # Nintendo Switch → Google Drive (manual portal upload)
  xbox.jenkinsfile           # Xbox GameCore → Google Drive (manual Partner Center upload)
  ps5.jenkinsfile            # PlayStation 5 → Google Drive (manual PS Partners upload)
  test_editor.jenkinsfile    # EditMode/PlayMode tests across platforms
```

## Architecture

`buildUtils.groovy` calls `init()` to detect agent OS via `isUnix()`, then loads `common.groovy` + platform module (`windows.groovy` or `macos.groovy`). Jenkinsfile stages call `buildUtils.*` which delegates to loaded modules.

Unity C# pipeline scripts are copied from `resources/Editor/Pipeline/` into the project's `Assets/Editor/Pipeline/` by `copyPipelineScripts()` during Startup, before Unity opens. These configure platform-specific build settings and are invoked via Unity's `-executeMethod` CLI.

### Adding New Features

1. Add functions to `resources/groovy/windows.groovy` (and `macos.groovy` if cross-platform)
2. Add delegation methods to `vars/buildUtils.groovy`
3. **Update the Jenkinsfiles** — library functions alone won't run without pipeline stages

### Library Loading

- `@Library('tool_jenkins_build_system@branch')` — branch override only affects `@script` workspace (Jenkinsfile checkout), NOT `@libs` workspace (library code)
- `@libs` always resolves from the **Jenkins Global Pipeline Libraries** Git source: GitHub `oddgames/tool_jenkins_build_system`, default version `main`
- **All library code changes MUST be pushed to GitHub `main`** to take effect in builds

## Source Control

**This library repo is hosted on GitHub** (`oddgames/tool_jenkins_build_system`). Version it with **git**: commit and push library changes to `main` (all library changes must be on `main` to take effect — see Library Loading above).

The **build pipeline still checks out the Unity game projects from Plastic SCM** (`cm switch`/`cm update --forced`). The `cm` rules below apply to *pipeline code that drives the game workspace* — NOT to this repo.

### Plastic SCM (game workspace only)

**NEVER add a `cm` command to code unless verified** via docs or `cm <command> --help`. GUI-only features do NOT necessarily have CLI equivalents.

#### Verified `cm` Commands
- `cm status`, `cm history <file>`, `cm cat <file>`, `cm undo . -r`, `cm update --forced`, `cm switch`, `cm find changeset`, `cm ls --tree=<cs>@<repspec> -R --format={path}` (server tree, no workspace copy needed), `cm remove [--nodisk]`, `cm checkin`

#### Known Non-Existent Commands
- `cm diff` — visual/UI tool only, won't work on CLI
- `cm workspace checkcontent` — **does not exist**; "Check content (hash)" is GUI-only

#### Windows Reserved Names (`nul`, `con`, `aux`, ...)

Files named after a DOS device (checked in from macOS/Linux, e.g. `Back4App/cloud_code/nul`)
cannot be created on Windows, so every checkout logs
`Error updating '<path>': Stream does not support seeking.` and `cm switch` exits 1.
Deleting the file locally is useless — it is *controlled*, so the next update re-fetches it.

`_writeCloakedConf()` (windows.groovy) writes leaf-name rules for all reserved device names
into `<workspace>/cloaked.conf` before `cm workspace create` / `cm switch`, and cloaked items
are skipped by update/switch (`cm update --cloaked` is the opt-in to include them — verified
in `cm help update`). `cleanPlasticWorkspace()` keeps that file when it deletes private files.
- Job env `PLASTIC_CLOAK_PATHS` — `;`-separated extra rules, workspace-relative and starting
  with `/` (e.g. `/UnityProj_MTD/Back4App/cloud_code/nul`). Use it if the leaf-name rule isn't
  enough, or to skip large folders the build doesn't need.

**Detection**: `findReservedNamesInRepo()` runs `cm ls --tree=<cs>@<repspec> -R --format={path}`
and filters it with `findstr /i /e` on the agent, so it finds these items in the *repo* even
though no Windows workspace can hold them. `plasticCheckout()` calls it at the end and prints
the paths plus the removal commands. Cost on MTD: 63,783 paths in ~9s. `SCAN_RESERVED_NAMES=false`
skips it; hits are also exported as `env.PLASTIC_RESERVED_ITEMS`.

**Removal is impossible from Windows** — verified against a real workspace, don't retry it:
- `cm remove --nodisk "...\cloud_code\nul"` → `\\.\nul is not in a workspace.` .NET
  normalises any path whose leaf is a device name to the device itself, so cm never sees the
  real path.
- The `\\?\C:\...` extended-length form *is* accepted (`cm remove controlled --nodisk` exits 0)
  but is a silent no-op: the item never materialised, so it is not in the workspace tree —
  `cm ls <folder>` doesn't list it and `cm status --localdeleted` reports nothing, so there is
  no local deletion to check in either.
- Removing the *parent folder* and re-adding it does work on Windows, but breaks history for
  every real file in it (~50 in `cloud_code`) — not worth it.
- **Do it on macOS/Linux**, where the name is legal (the iOS agents have `cm`):
  `cm remove "<path>"` then `cm checkin "<path>" -c "..."`, on the branch that carries the file.

## Jenkins Constraints

- `isUnix()` requires node context — **DO NOT** use `System.getProperty('os.name')` (returns controller OS, not agent)
- `System.getenv('HOME')` / `System.getenv('USERPROFILE')` — **returns controller's env**, not agent's. Use `env.HOME` or shell commands
- Post actions in declarative pipelines run after node release if the build fails early
- Windows 260-char path limit — Steam uses `C:\Temp\Steam\` staging to work around this
- Script approvals are verified at build time by `preflightJenkinsPermissions()` in `common.groovy`
- **When adding sandbox-restricted API calls** (anything under `jenkins.model.*`, `hudson.*`, `org.jenkinsci.*`, `rawBuild.*`, `classLoader.*`, `.newInstance()`), **add a matching `testPermission()` block** to `preflightJenkinsPermissions()`. Each permission MUST have its own try/catch so all pending signatures are queued at once in Jenkins' Script Approval page.
- Node selection API (`pickNode()`) requires: `Jenkins.get`, `getLabel`, `getLabels`, `Node.toComputer`, `Node.getDisplayName`, `LabelAtom.getNodes`, `Computer.isOnline`, `Computer.numExecutors`, `Computer.getExecutors`, `Executor.isBusy`, `Executor.getCurrentExecutable`, `Run.getParent`, `Queue.Task.getOwnerTask` (resolves a pipeline's PlaceholderTask to its owning job — plain `getFullName()` on the executor task throws for pipelines, which was silently disabling conflict detection and causing `@2` workspaces), `Job.getFullName`, `Jenkins.getQueue`, `Queue.getItems`, `Queue.Item.getAssignedLabel`, `Queue.Item.task`

### CPS Serialization

Pipeline code is CPS-transformed: every local that is still in scope when a step runs must be
serializable. A `java.util.regex.Matcher` is not — holding one across a step (especially a
`lock`/`timeout` block) throws `NotSerializableException`, and its `getMessage()` is just
`java.util.regex.Matcher`, so it surfaces as a nonsense error message. Do the matching inside a
`@com.cloudbees.groovy.cps.NonCPS` helper that returns a String (see `_suggestedModuleId()`),
never `def m = text =~ /../` followed by a step. This silently disabled the Android JDK
auto-install: the versioned module ID parsed fine, then the install threw on serialization.

### Sandbox Restrictions

`Integer.toHexString()` and `Integer.toString(n, 16)` are **BLOCKED** — use `Math.abs()` with decimal instead. Check Jenkins script approval page if methods are blocked.

Set env vars by literal name (`env.FOO = x`). `env[name] = x` goes through `DefaultGroovyMethods.putAt`, which
the sandbox rejects — build #13455 lost its Slack post, badges and final archive to it. Don't ask for that
signature to be approved: it lets a sandboxed script set any property on any object.

Code on the failure path (anything that runs before `error()` or in `post`) must not throw: an exception
there ends the `post` block, skipping `finalizeBuild()` (the Slack post) and the final artifact archive.
Wrap best-effort extras such as badges and summary boxes in try/catch, as `linkFailureLog()` does.

### Winget Package IDs

Use **specific version IDs**, not bare prefix names:
- `Python.Python.3.13` (not `Python.Python.3` — doesn't resolve on all machines)
- `RubyInstallerTeam.RubyWithDevKit.3.2`
- When auto-installing, try multiple version IDs in descending order with `returnStatus: true` fallback

### Windows Batch Gotchas

- `forfiles` returns **exit code 1** when no files match — suppress with `2>nul || echo ...` to prevent false failures
- `for %%f in (*.apk *.aab)` can match multiple files — use `goto :eof` after first match to get single filename

### Unity Hub CLI

- Syntax: `"Unity Hub.exe" -- --headless <command>`
- Takes ~20 seconds to respond — must wait, any input cancels the operation
- Cannot test from Git Bash on Windows — bash mangles `--` arg separator. Use `cmd.exe` or Jenkins `bat` steps
- Build agents: `C:\UnityEditors\` — Dev PC: `C:\Program Files\Unity\Hub\Editor\`
- Commands: `editors`, `install-path`, `install`, `install-modules` — NO `details`, `list`, or `modules` command
- **Unity 6 module IDs are versioned** (e.g. `android-open-jdk-17.0.9+9`), but `--headless help` still shows the old names (`android-open-jdk`). There is no command to query the real versioned IDs — the only way to discover them is from the "Did you mean" error when using the old name. `verifyAndroidJdk()` handles this automatically.
- **macOS: Hub may install to `<version>-arm64`, not `<version>`.** When `/Applications/Unity/Hub/Editor/<version>` is already taken (e.g. by a copy Hub doesn't track — the case where `installUnityModules()` falls back to a full `install`), Hub puts the new editor beside it with an arch suffix, and `editors -i` reports `installed at .../<version>-arm64/Unity.app`. Never hardcode the editor path in `macos.groovy` — go through `getUnityInstallDir(version)` (cached in `env.UNITY_INSTALL_DIR`; when several copies exist it asks Hub which one it tracks, since that is where modules land). The stale untracked copy is left on disk; delete it by hand.

## Key Fixes Reference

- **Switch NSP not building**: `BuildOptions.CompressWithLz4HC` conflicts with Switch ROM creation — removed from `PipelineSwitch.cs`. Switch uses its own compression via `switchEnableRomCompression`/`switchRomCompressionType`
- **Steam Linux building Windows exe**: Missing `linux-il2cpp` module causes Unity to silently fall back to Windows — added `validateLinuxBuildSupport()` preflight to `steam-linux.jenkinsfile`
- **UnityDataTool `EntryPointNotFoundException` at `GetDllVersion`**: v2.3.0+ requires a Unity 6.7+ `UnityFileSystemApi`; we stage the project editor's DLL, so the tool is pinned in `common.unityDataToolVersion()` (`v2.2.1`, job env `UNITY_DATA_TOOL_VERSION` overrides) instead of tracking latest
- **Steam "Failed to commit build ... : Failure"**: the `SetLive` branch in the VDF must already exist on the app. The content upload succeeds and Steam still creates the build — only the commit RPC is rejected — so the build shows up in Steamworks while the pipeline reports failure. See below.

## Xcode Auto-Install (`XCODE_VERSION`)

`preflightXcode()` (macos.groovy) picks the Xcode via `DEVELOPER_DIR`; when `XCODE_VERSION` isn't
installed, `installXcodeFromShare()` installs it from a **`.xip` on the build share** — never from Apple.

- **Online `xcodes install` cannot work on an agent**: it reads the Apple ID password from the login
  keychain, locked outside a GUI session (`OSStatus error:[-25308] User interaction is not allowed`),
  then Apple's sign-in returns HTML (`not valid JSON ... Unexpected character '<'`), and 2FA would
  want a typed code anyway. Don't reintroduce it.
- **Per new version, once, by hand**: download the `.xip` from developer.apple.com/download/all and
  drop it in `<LOCAL_SHARE_PATH>/_xcode/` (`\\odd-jenkins\builds\_xcode\`) keeping Apple's name
  (`Xcode_26.xip`, `Xcode_26.0.1.xip`; Apple drops trailing `.0`). Job env `XCODE_XIP_SHARE_DIR`
  overrides the folder. `XCODE_VERSION=26` takes the newest non-beta `Xcode_26.*.xip`.
- The `.xip` is copied to the agent first — `xcodes` trashes the `.xip` after installing, which on the
  SMB mount would delete the share's copy. Install is `xcodes install <v> --path <xip> --no-superuser`
  under a per-agent `lock`, with a free-space check (~5× the `.xip`).
- **License**: a new Xcode won't build until `xcodebuild -license accept` runs as root. The pipeline
  tries `sudo -n` and only warns if sudo wants a password. Fix for every future Xcode:
  `<agent-user> ALL=(root) NOPASSWD: /Applications/Xcode*.app/Contents/Developer/usr/bin/xcodebuild`.

## Console Platforms (Xbox / PS5)

Both follow the Switch pattern: build on a Windows agent, deliver the output to Google Drive + the
local share, submit manually through the platform portal. No automated store upload.

- **`env.PLATFORM` is the Unity `-buildTarget`**: `GameCoreXboxSeries`, `GameCoreXboxOne`, `PS5`.
  The Xbox job picks its target with the `XBOX_TARGET` parameter.
- **Modules are not in Unity Hub.** Licensed console support is installed from the platform's own
  developer portal, so there is no module ID to auto-install. `validateXboxSupport()` /
  `validatePS5Support()` probe the `PlaybackEngines` folder instead and list every path they tried
  when it's missing.
- **SDK discovery**: `preflightXboxGDK()` resolves the GDK from `GameDK`/`GXDKLatest`, falling back
  to scanning `C:/Program Files (x86)/Microsoft GDK`; `preflightPS5SDK()` resolves
  `SCE_PROSPERO_SDK_DIR` from the SCE env vars or `C:/Program Files (x86)/SCE/Prospero SDKs`. Both
  fall back because an agent service started before the SDK install won't have inherited the
  machine env vars — **restart the agent service after installing an SDK**. `runUnityCommand()`
  fails fast if the matching preflight didn't run.
- **Output is a folder**, not a single installable file, so the jobs call
  `uploadFolderToGoogleDrive()` (uploads the whole tree, links the folder, plus a direct link if a
  `.xvc`/`.msixvc`/`.pkg` is present at the top level) rather than `uploadToGoogleDrive()`.
- **No packaging step.** The pipelines ship the loose layout; makepkg (Xbox) and the PS5 publishing
  tools are still manual. `preflightXboxGDK()` logs the makepkg path when it finds one.
- **No `CompressWithLz4HC`** on either console — same conflict with platform packaging that broke
  Switch ROM creation.
- **Platform-specific player settings** that need the module's own editor API (which we can't
  reference without breaking compilation on agents that lack the module) belong in a Build Profile
  at `Assets/Editor/Pipeline/BuildProfiles/{Xbox,PS5}{Debug,Release}.buildprofile`. The C# scripts
  activate it when present and fall back to scripted IL2CPP/stripping settings when not, resolving
  the `NamedBuildTarget` from the active target group at runtime.
- Optional job env vars `XBOX_AGENT_LABEL` / `PS5_AGENT_LABEL` restrict the job to SDK-equipped
  agents (default label: `Windows`).

## Steam SetLive Branches

`steamUpload()` writes `SetLive` into the VDF. Steam rejects the whole commit if that branch doesn't exist on the app, reporting only `ERROR! Failed to commit build for AppID <id> : Failure`.

- Branch defaults: Release → `beta`, otherwise → `development`. Override per job with **`STEAM_BRANCH_RELEASE`** / **`STEAM_BRANCH_DEBUG`** env vars; `none` uploads without setting live.
- `default`/`public`/`none` are dropped automatically — **Steam does not allow setting the default branch live from SteamCMD** (site only).
- Retries: `common.classifySteamFailure()` splits transient failures (connection drops, `Timeout`/`Busy` EResults — retried) from rejections (retried never). Retrying a rejection is pure waste: every attempt re-scans the content root **and creates another duplicate build on Steamworks**.
- On failure the pipeline dumps the tail of SteamCMD's own `logs/content_log.txt` (the real reason; the console only says `Failure`) and, when a branch is suspected, lists the app's actual branches via `getSteamBranches()` (app info calls the default branch `public`).

## Google Drive Uploads (rclone)

`uploadToGoogleDrive()` / `uploadFolderToGoogleDrive()` shell out to rclone with the `rclone`
file credential as `--config`.

- **The remote MUST carry its own `client_id`/`client_secret`** (or a service account). Without
  one, rclone authenticates through its built-in default OAuth client, Google Cloud project
  **202264815644**, whose Drive API quota is shared by every rclone user worldwide. When that pool is
  busy every request fails with `403 ... Quota exceeded for quota metric 'Queries' ... consumer
  'project_number:202264815644'`, and nothing on our side can free it; rclone.org/drive also says the
  shared client is being retired during 2026. `preflightRclone()` checks the config (yes/no only,
  the file is a secret), warns at Startup, and exports `env.RCLONE_OWN_CLIENT`. Changing the
  client means re-authorising the remote (`rclone config reconnect <remote>:`) — the token is bound
  to the client — then updating the Jenkins credential.
- **Retries re-send the whole file.** rclone's `operations.Copy` restarts the transfer from byte 0
  on every low-level retry, so the defaults (`--retries 3` × `--low-level-retries 10`) turned one
  ~630 MiB IPA into 19 GiB of uploads against a quota that retrying cannot fix. The uploads run
  with `--retries 3 --retries-sleep 60s --low-level-retries 3` (the sleep lets a per-minute window
  reset).
- rclone's log goes to `ARTIFACT_PATH/rclone_upload.log` (archived); `--progress` keeps the console
  live. On failure `common.failRcloneUpload()` prints the ERROR/NOTICE lines once (rclone repeats
  Google's JSON blob per attempt) and fails the stage with `classifyRcloneFailure()`'s one-line
  verdict (shared-client quota, own-client rate limit, storage full, token revoked, folder missing).

## Build Badges: Live Status + Local Artifact Links

The build-history sidebar only renders badges and the description, so badges are the only way a
running build can say what it's doing there. Colours (`resultBadgeColor()` in `common.groovy`):
blue = running, **orange = built but not yet uploaded**, green/yellow/red/grey = Jenkins result.

- **Every stage starts with `buildUtils.setCurrentStage('Name')`** — never assign `env.CURRENT_STAGE`
  directly. It records the stage for failure reporting *and* updates the `status` badge
  (`stage | Name`, blue). Before `init()` it only records; `init()` paints the badge.
- `sendUploadNotification()` flips the badge to `uploading | Local, GDrive, TestFlight` (orange);
  `updateUploadStatus()` refreshes it as targets finish (`upload failed | X` red, `uploads | done`
  green). Post-build stages running in parallel with uploads don't overwrite it.
  `updateBadgesForResult()` (from `finalizeBuild()`) removes it. Warm-up builds get no status badge.
- **Local share = first download link.** `uploadToLocalShare()` (both platforms) calls
  `common.addLocalArtifactLinks()` once the copy lands: orange `ipa`/`apk`/`aab`/`nsp`/`nspd`/`build`
  badge + `Download IPA (Local)` sidebar link to the `file://` path, minutes before Google Drive
  finishes. The Drive upload adds its green badge via `common.addDriveArtifactBadge()` with the
  **same badge id** (the extension), which replaces the orange one. Jobs with no Drive upload
  (Steam) get the local badge re-coloured green when every upload has finished.
- **`file://` links are dead everywhere**: browsers block them from an http page, Slack won't
  linkify them, and the sidebar-link plugin rejects the scheme outright (`URI scheme "file" is not
  allowed`) — the `Local Build` sidebar link had never actually been added. All local links go through
  `common.localShareUrl(uncPath)`: set **`LOCAL_SHARE_HTTP_BASE`** (global or job env) to the http(s)
  URL serving the share root and badge, sidebar and Slack links become real downloads. Simplest:
  on the controller, junction `JENKINS_HOME/userContent/builds` → the share folder and set
  `LOCAL_SHARE_HTTP_BASE=http://<jenkins-host>:8080/userContent/builds`. Unset, the `file://` form
  is used for the badge/Slack text and `addSidebarLink()` skips it with an `[INFO]` line.
- **The Slack channel message is posted when the local share copy is done**, not when uploads
  start: `sendUploadNotification()` only registers the targets (pending ⏳) when `local` is among
  them; `updateUploadStatus('local', done|failed)` posts the message with every target's current
  state, later updates edit it in place, and `handleBuildSuccess()` still posts if the local stage
  never reported. `notifyAfterLocal: false` restores the immediate post.

## Build Warnings → UNSTABLE (`[BUILD-WARN]`)

Any build tool can flag the build UNSTABLE (yellow; the build still uploads) by getting a line into
`ARTIFACT_PATH/build_warnings.txt`. The `Build Warnings` stage (every player-build Jenkinsfile,
after the last build step) reads that one file → `setUnstable()` once with the list, a yellow
`warnings │ N` badge, a sidebar link to the archived file, and the lines in the Slack post.

- **Unity**: `Debug.LogWarning("[BUILD-WARN] msg")` from anywhere (any assembly, worker threads
  included) or `Pipeline.Warn("msg")`. `PipelineWarnings.cs` installs an
  `Application.logMessageReceivedThreaded` hook on every batch-mode editor load, so asset import,
  Prepare and Build are all covered; it writes the file itself (de-duplicated, one line each) — the
  pipeline never scans the Unity console log.
- **Xcode**: `echo "warning: [BUILD-WARN] msg"` from a Run Script phase. `archiveXcodeProject()`
  greps its own tee'd `xcodebuild_archive.log` on the agent (`collectToolWarnings()`).
- **Pipeline code**: `buildUtils.appendBuildWarning("msg")`; **shell**: append to the file.
- **`UNSTABLE_WARNING_PATTERNS`** (job env, `;`-separated case-insensitive regexes) promotes
  matching *native* Unity warnings/errors and Xcode log lines too, e.g. `Shader error in;\[Bugpunch\] NOT CONFIRMED`.
  Keep this list specific — a normal Unity build logs hundreds of warnings.
- Keep tools decoupled: a tool that wants to flag the build emits the marker (or matches a pattern);
  the pipeline never reads another product's private files or APIs.
- A failed build never reaches the Build Warnings stage. `finalizeBuild()` still adds the `warnings │ N`
  badge and sidebar link (`linkBuildWarnings()`) without changing the result, so warnings raised before
  the failure stay one click away.

### Build Errors (`[BUILD-ERROR]`)

The same hook collects any log line carrying `[BUILD-ERROR]` into `ARTIFACT_PATH/build_errors.txt`. The
marker does not fail the build — the tool still has to fail it (in Unity, `BuildFailedException` from a
build callback, or an exception from a `PRE_BUILD_METHODS` method). When the Unity step then exits
non-zero, `runUnityCommand()` calls `failWithReportedBuildErrors()`, which fails as a **known error** listing
those lines (first 10 in the explanation) — the Slack post shows them instead of the AI error analysis. No
file, or an empty one: the usual `Unity command failed` failure and analysis run unchanged.

`build_errors.txt` is surfaced like `unity_errors.log`, through the same `linkFailureLog()` (with its own badge
id and `BUILD_ERRORS_URL`): a red `build errors │ N` badge on the build list, a summary box listing the lines,
a sidebar link, a build-description link and a `Build Errors` link in Slack. The lines also match
`extractErrorLines()`, so they appear in `unity_errors.log`. Make each line say what is wrong, not just how
many things are: only the first line of a message is kept, capped at 300 characters.

`Pipeline.Build()` marks the exception that ends it (`[BUILD-ERROR] Build failed: <message>`), so an
exception inside the Build invocation (a pre-build method, an unresolved `PRE_BUILD_METHODS` name, the
up-front Addressables content build) is reported this way. A player build that fails returns a report
rather than throwing; `ReportBuildErrors()` logs its messages as `[Error]` lines for `unity_errors.log`.
A failure before `Pipeline.Build()` (a platform entry point's keystore or version-code check) or in the
Prepare stage is not marked.

## Firebase Crashlytics Symbol Upload

1. Set `UPLOAD_CRASHLYTICS_SYMBOLS=true` in Jenkins job config
2. Add Firebase Crashlytics Admin role to `google-play-json` service account
3. Ensure `google-services.json` exists in Unity project (`Assets/StreamingAssets/` or `Assets/Plugins/Android/`)
4. Runs in parallel with other post-build tasks, skips silently if not configured
5. A failed upload marks the build UNSTABLE, never FAILED. Android leaves out `libFirebaseCpp*` and `libwebrtc.so`
   (the `com.unity.webrtc` prebuilt): the symbol generator aborts the whole upload on the first library it cannot process

## Addressables Content Build + Catalog Validation

`Pipeline.Build()` (in `Pipeline.cs`) runs the player build, then **validates the shipped catalog** — all in the same Editor session. All platforms funnel through `Pipeline.Build()`, so this covers Google Play/Amazon/Apple/Steam/Switch. This exists because catalogs can ship missing entries, producing `No Location found for Key=<guid>` at runtime (e.g. blank preview icons).

**Content build** (`EnsureAddressablesContentBuilt`): only builds Addressables up-front when the player build *won't* itself (project setting off). When the setting is `PreferencesValue`/`BuildWithPlayer` (the usual case), Unity's `AddressablesPlayerBuildProcessor` already builds content during the player build, so building up-front would double-build. Env: `BUILD_ADDRESSABLES` (default `true`, `false` skips); `CLEAN_ADDRESSABLES` (default `false`, `true` forces a full rebuild).

**Catalog validation** (`ValidateCatalog`, warn-only): after the player build, loads the built catalog with the runtime API (`LoadContentCatalogAsync` → `IResourceLocator.Locate`) and checks every shipped, GUID-indexed entry (`IncludeInBuild && IncludeGUIDInCatalog`) resolves — exactly how an `AssetReference` resolves at runtime.
- **No auto-rebuild.** If entries are missing, they are reported and the build is flagged — the pipeline does **not** clean-rebuild the player (that "double build" was removed; it was slow and rarely fixed anything). To rule out a stale incremental cache, re-run with `CLEAN_ADDRESSABLES=true`, which does `CleanPlayerContent` + SBP `BuildCache.PurgeCache` *before* the build.
- Missing entries are surfaced as the **full grouped list in the build log** AND written to `AddressablesBrokenEntries.txt` under `ARTIFACT_PATH` (auto-archived). The build is **NOT failed** — the report file's existence is the marker. The report is human-readable: a count sentence (line 2), a `PER-GROUP SUMMARY` block, then per-entry **address / asset path / guid** detail.
- The Jenkins pipeline calls `buildUtils.checkBrokenAddressables()` (an `Addressables Check` stage, wired into **all** player-build Jenkinsfiles) which, if that report exists, flags the build **UNSTABLE** via `setUnstable()`. It also parses the report's per-group summary into `env.ADDRESSABLES_REPORT_DETAIL`, which `sendSlackBuildNotification()` renders as a dedicated Slack block (per-group breakdown + a link to the archived report). UNSTABLE does not stop the pipeline — the IPA/AAB still builds and uploads.
- Do **not** rely on the Editor preference "Build Addressables on Player Build" — it's per-machine/untracked; the in-pipeline steps are what guarantee correctness on CI agents.

## Build Reports (`ARTIFACT_PATH/reports/`)

A build tool that writes a human-readable report into `ARTIFACT_PATH/reports/` gets it archived with the
build (the `artifacts` dir is archived in `post { always }`), a `Report: <file>` sidebar link, and — on
unstable or failed builds — a `report │ <file>` badge on the build list and a `Reports:` line of links in the
Slack post. `linkBuildReports()` (called from `sendSlackBuildNotification()`) lists the folder on the agent
once per build; on its first call once the build is unstable or failed it archives `reports/**` straight
away (the Slack post goes out before the final archive) and adds the badges. The pipeline never reads the
reports themselves. Use it for detail too long for a `[BUILD-WARN]` line, so the log and Slack stay
short while the full list is one click away.

## GitHub Auth for Private Packages (PATs)

`configureGitAuth()` (in `windows.groovy`/`macos.groovy`, called from `startup()`) sets `git config --global url.insteadOf` so Unity's UPM can resolve private GitHub packages, then `cleanupGitAuth()` removes it in post.

- **Default**: the `github` credential rewrites **all** of `github.com/`.
- **Org-scoped PATs**: set job env `GITHUB_ORG_PATS="org=credentialId; org2=credId2"` (Secret-text credentials). `configureGitHubOrgPats()` adds a `git insteadOf` for `github.com/<org>/` using the `x-access-token:<PAT>@` form (fine-grained PATs / GitHub App tokens). Git uses the **longest-matching** insteadOf, so the org PAT wins for that org while the `github` cred covers the rest.
- Tokens are passed via the bound credential's env var (`%VAR%` / `${VAR}`), **never** Groovy-interpolated into the command — avoids leaking the secret into the script text. `cleanupGitAuth()` unsets every `url.*@github.com/` entry (piped/captured so token-bearing keys aren't echoed).

## Pre-Build Methods (`PRE_BUILD_METHODS`)

Job env `PRE_BUILD_METHODS=Namespace.Type.Method; Other.Type.Method` names static, parameterless methods in the Unity project. Set it in the job's Properties Content like any other job env, **without quotes**: quotes are kept literally and the name then fails to resolve. `Pipeline.Build()` runs them in order inside the Build invocation, after `UpdateVersionData()` and **before** `EnsureAddressablesContentBuilt` and the player build, so a project's data checks fail a build before any content is built. They run in the **Unity Build** stage; the separate "Pre-Build Script" stage is `PREBUILD_SCRIPT` (below) and shows ~0 ms when that param is empty. A nested type is written `Outer+Inner`.

- A method that throws fails the build (exit 1): the full exception chain is logged, then `[BUILD-ERROR] Build failed: Pre-build method '...' failed ...`.
- A name that does not resolve (missing type or method) also fails the build, so a typo cannot silently skip the checks. It is reported the same way: `[BUILD-ERROR] Build failed: PRE_BUILD_METHODS: '<type>' has no static parameterless method '<name>'`.
- Unset or empty: no-op. Each project opts in through its own job configuration; the pipeline never names project types.
- Not to be confused with `PREBUILD_SCRIPT` (below), which runs a shell script on the agent before Unity opens.

## Pre-Build Script (one-off agent fix-ups)

`PREBUILD_SCRIPT` text param runs on the agent after checkout, before Unity opens — for agent-only state a clean repo can't fix (e.g. an orphaned `Assets/Plugins/Android/*.androidlib`, stale `Library/Bee/Android`). PowerShell on Windows agents, bash on macOS (auto by agent OS via the platform module). Best-effort: non-zero exit warns and continues unless `PREBUILD_FAIL_ON_ERROR=true`. New params don't appear until the job has run once with the updated Jenkinsfile.
