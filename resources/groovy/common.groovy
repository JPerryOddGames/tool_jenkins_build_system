// ============================================================================
// COMMON UTILITIES - Platform-agnostic functions
// ============================================================================

def platformModule = null   // Set by buildUtils after platform is loaded; used by build handlers
def knownErrors = null      // Set by buildUtils; pattern matcher for known build failures

// Set build to unstable and store the reason for Slack notifications
def setUnstable(String reason) {
    if (!env.UNSTABLE_REASONS) {
        env.UNSTABLE_REASONS = reason
    } else {
        env.UNSTABLE_REASONS = "${env.UNSTABLE_REASONS}\n${reason}"
    }
    unstable(message: reason)
}

/**
 * Mark the build UNSTABLE if the Unity build left an Addressables broken-entry report.
 * Pipeline.cs writes AddressablesBrokenEntries.txt into ARTIFACT_PATH only when catalog entries
 * still had no location after a clean rebuild (warn-only — the Unity build did NOT fail). The file
 * is archived by the pipeline's normal artifact step; here we surface a summary and flip the build to
 * UNSTABLE. No-op when the report is absent (catalog was clean or the rebuild fixed it).
 */
def checkBrokenAddressables() {
    def reportPath = "${env.ARTIFACT_PATH}/AddressablesBrokenEntries.txt"
    if (!fileExists(reportPath)) return

    def summary = 'broken catalog entries detected'
    def perGroup = []
    try {
        def lines = readFile(reportPath).readLines()
        // Line 2 of the report is the count sentence (stable contract with Pipeline.cs).
        if (lines.size() > 1 && lines[1]?.trim()) summary = lines[1].trim()
        // The "PER-GROUP SUMMARY" block lists "<count>  <group>" until the next blank line.
        def inSummary = false
        for (def line : lines) {
            if (line ==~ /(?i)\s*PER-GROUP SUMMARY\s*/) { inSummary = true; continue }
            if (inSummary) {
                if (!line?.trim()) break
                perGroup << line.trim()
            }
        }
    } catch (Exception e) {
        echo "[WARN] Could not read Addressables report: ${e.message}"
    }

    // Compose the Slack detail block: summary + per-group breakdown + a link to the archived report,
    // so the full list is one click away. Rendered by sendSlackBuildNotification() on unstable builds.
    // Archived from inside dir('artifacts'), so the report sits at the artifact root.
    def reportUrl = "${env.BUILD_URL}artifact/AddressablesBrokenEntries.txt"
    def detail = ":jigsaw: *Broken Addressables* — ${summary}\n"
    detail += "Each fails at runtime with `No Location found for Key=<guid>` (blank icons / missing content).\n"
    if (perGroup) {
        def shown = perGroup.take(12)
        detail += "*Broken entries per group:*\n```${shown.join('\n')}"
        if (perGroup.size() > shown.size()) detail += "\n… and ${perGroup.size() - shown.size()} more group(s)"
        detail += "```\n"
    }
    detail += "<${reportUrl}|:page_facing_up: Full broken-entry report>"
    env.ADDRESSABLES_REPORT_DETAIL = detail

    echo "[UNSTABLE] Addressables: ${summary} — see AddressablesBrokenEntries.txt (archived artifact) and the build log."
    setUnstable("Broken Addressables — ${summary}")
}

// ============================================================================
// BUILD WARNINGS -> UNSTABLE
//
// Any build tool can flag the build UNSTABLE (yellow, still builds and uploads) by getting a line
// into ARTIFACT_PATH/build_warnings.txt:
//   Unity   Debug.LogWarning("[BUILD-WARN] msg") anywhere, or Pipeline.Warn("msg") - a log hook in
//           PipelineWarnings.cs writes the file itself, so nothing here scans the Unity console.
//   Xcode   echo "warning: [BUILD-WARN] msg" from a Run Script phase - archiveXcodeProject() greps
//           its own log file on the agent (collectToolWarnings) into the same file.
//   Shell   echo "msg" >> "$ARTIFACT_PATH/build_warnings.txt"
// Optional UNSTABLE_WARNING_PATTERNS (job env, ';'-separated regexes) promotes matching native
// Unity/Xcode warnings too. checkBuildWarnings() (the 'Build Warnings' stage, after every build
// step) turns the file into one unstable() call, badge + sidebar + Slack. A failed build never
// reaches that stage, so finalizeBuild() adds just the badge + sidebar link (linkBuildWarnings).
// ============================================================================

/**
 * Pull marker lines (and UNSTABLE_WARNING_PATTERNS matches) out of a tool's log file into
 * build_warnings.txt. Runs grep on the agent so a multi-MB xcodebuild log never crosses to the
 * controller. Marker lines keep only the text after the marker; pattern matches keep the line.
 *
 * @param logFile  absolute path of the log on the agent
 * @param source   short tool name used as a prefix, e.g. 'Xcode'
 */
def collectToolWarnings(String logFile, String source) {
    if (!env.ARTIFACT_PATH || !logFile) return
    def marker = '[BUILD-WARN]'
    // Job-level regexes -> one ERE alternation. Users write ';'-separated patterns; '|'-join them.
    def patterns = (env.UNSTABLE_WARNING_PATTERNS ?: '').split(';').collect { it.trim() }.findAll { it }
    def alternation = patterns ? patterns.join('|') : ''
    def out = "${env.ARTIFACT_PATH}/build_warnings.txt"
    try {
        if (isUnix()) {
            sh label: "Collect ${source} build warnings", script: """#!/bin/bash
                [ -f '${logFile}' ] || exit 0
                mkdir -p '${env.ARTIFACT_PATH}'
                {
                    grep -F -- '${marker}' '${logFile}' | sed 's/.*\\[BUILD-WARN\\][[:space:]]*//'
                    if [ -n '${alternation}' ]; then
                        grep -E -i -- '${alternation}' '${logFile}' | grep -v -F -- '${marker}'
                    fi
                } | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*\$//' -e '/^\$/d' | cut -c1-300 | awk '!seen[\$0]++' \\
                  | sed 's/^/${source}: /' >> '${out}'
                exit 0
            """
        } else {
            // findstr can't do ERE alternation reliably; a PowerShell script handles both channels.
            // Same write-file-then-run pattern as runPrebuildScript() in windows.groovy.
            def ps = """
                \$log = '${logFile.replace("'", "''")}'
                if (-not (Test-Path -LiteralPath \$log)) { exit 0 }
                New-Item -ItemType Directory -Force -Path '${env.ARTIFACT_PATH.replace("'", "''")}' | Out-Null
                \$marker = '${marker}'
                \$alt = '${alternation.replace("'", "''")}'
                \$seen = @{}
                foreach (\$line in [System.IO.File]::ReadLines(\$log)) {
                    \$i = \$line.IndexOf(\$marker)
                    if (\$i -ge 0) { \$msg = \$line.Substring(\$i + \$marker.Length) }
                    elseif (\$alt -and (\$line -imatch \$alt)) { \$msg = \$line }
                    else { continue }
                    \$msg = \$msg.Trim()
                    if (\$msg.Length -gt 300) { \$msg = \$msg.Substring(0, 300) }
                    if (\$msg -and -not \$seen.ContainsKey(\$msg)) { \$seen[\$msg] = 1; Add-Content -LiteralPath '${out.replace("'", "''")}' -Value ('${source}: ' + \$msg) }
                }
                exit 0
            """.stripIndent()
            def scriptFile = "${env.WORKSPACE}\\collect_${source.toLowerCase()}_warnings.ps1"
            writeFile file: scriptFile, text: ps
            try {
                bat(script: "powershell -NoProfile -ExecutionPolicy Bypass -File \"${scriptFile}\"", returnStatus: true)
            } finally {
                bat(script: "del /f /q \"${scriptFile}\" 2>nul & exit /b 0", returnStatus: true)
            }
        }
    } catch (Exception e) {
        echo "[WARN] Could not collect ${source} warnings from ${logFile}: ${e.message}"
    }
}

/**
 * Append one line to build_warnings.txt from pipeline code (Unity and Xcode feed the file
 * themselves). Picked up by checkBuildWarnings(); no-op without ARTIFACT_PATH.
 */
def appendBuildWarning(String line) {
    if (!env.ARTIFACT_PATH || !line?.trim()) return
    def path = "${env.ARTIFACT_PATH}/build_warnings.txt"
    try {
        def existing = fileExists(path) ? readFile(path) : ''
        if (existing && !existing.endsWith('\n')) existing += '\n'
        writeFile file: path, text: existing + line.trim() + '\n'
        echo "[BUILD-WARN] ${line.trim()}"
    } catch (Exception e) {
        echo "[WARN] Could not record build warning '${line}': ${e.message}"
    }
}

/**
 * Flag the build UNSTABLE for every warning the build tools raised (see the section header).
 * No-op when build_warnings.txt is absent or empty. Otherwise: lists them in the log, links the
 * file (linkBuildWarnings), and calls setUnstable() once with the (capped) list so the Slack post
 * shows them.
 */
def checkBuildWarnings() {
    def lines = linkBuildWarnings()
    if (!lines) { echo "[OK] No build warnings raised"; return }

    echo "========== BUILD WARNINGS (${lines.size()}) =========="
    lines.each { echo "  - ${it}" }
    echo "=============================================="

    def maxShown = 10
    def shown = lines.take(maxShown).collect { "• ${it}" }
    def more = lines.size() > maxShown ? "\n… ${lines.size() - maxShown} more — see build_warnings.txt" : ''
    setUnstable("Build warnings (${lines.size()}):\n${shown.join('\n')}${more}")
}

/**
 * Return the lines of ARTIFACT_PATH/build_warnings.txt (trimmed, de-duplicated; empty when the file
 * is absent or empty) and, once per build, add a yellow 'warnings | N' badge and a sidebar link to
 * it. Does not change the build result: checkBuildWarnings() does that on a build that got that far,
 * and finalizeBuild() calls this directly so a failed build still shows the warnings raised before
 * it failed.
 */
def linkBuildWarnings() {
    if (!env.ARTIFACT_PATH) return []
    def reportPath = "${env.ARTIFACT_PATH}/build_warnings.txt"
    if (!fileExists(reportPath)) return []

    def lines = []
    try {
        lines = readFile(reportPath).readLines().collect { it.trim() }.findAll { it }.unique()
    } catch (Exception e) {
        echo "[WARN] Could not read ${reportPath}: ${e.message}"
        return []
    }
    if (!lines || env.BUILD_WARNINGS_URL) return lines

    // Archived from inside dir('artifacts'), so the file sits at the artifact root.
    def reportUrl = "${env.BUILD_URL}artifact/build_warnings.txt"
    env.BUILD_WARNINGS_COUNT = "${lines.size()}"
    env.BUILD_WARNINGS_URL = reportUrl
    try {
        addShieldsDoubleBadge('warnings', 'warnings', "${lines.size()}", '555555', resultBadgeColor('UNSTABLE'), null, reportUrl)
        addSidebarLink(reportUrl, "Build Warnings (${lines.size()})", 'symbol-warning.png')
    } catch (Exception e) {
        echo "[WARN] Could not add build-warnings badge: ${e.message}"
    }
    return lines
}

// ============================================================================
// BUILD REPORTS
//
// A build tool that writes a human-readable report into ARTIFACT_PATH/reports/ gets it archived with
// the build (the artifacts dir is archived in post), a sidebar link, and - on unstable or failed
// builds - a 'report | <file>' badge on the build list and a link in the Slack post. The pipeline
// never reads the reports themselves.
// ============================================================================

/**
 * Add a sidebar link per file in ARTIFACT_PATH/reports/ and record the links for the Slack post in
 * env.BUILD_REPORT_LINKS; the folder is listed once per build. On the first call once the build is
 * unstable or failed (unsuccessful, or currentBuild.result says so), also archive the reports
 * straight away - the Slack post goes out before the final archive - and add a 'report | <file>'
 * badge per file to the build list. No-op when the folder is absent or empty, or when there is no
 * agent to list it on.
 */
def linkBuildReports(boolean unsuccessful = false) {
    if (!env.ARTIFACT_PATH) return

    if (env.BUILD_REPORTS_LINKED != 'true') {
        env.BUILD_REPORTS_LINKED = 'true'

        def names = []
        try {
            def reportsDir = "${env.ARTIFACT_PATH}/reports"
            if (!fileExists(reportsDir)) return
            def listing = isUnix()
                ? sh(returnStdout: true, script: "ls -1p '${reportsDir}' | grep -v '/\$' || true")
                : bat(returnStdout: true, script: "@dir /b /a-d \"${reportsDir.replace('/', '\\')}\" 2>nul")
            names = listing.readLines().collect { it.trim() }.findAll { it }.sort()
        } catch (Exception e) {
            echo "[WARN] Could not list build reports: ${e.message}"
            return
        }
        if (!names) return

        env.BUILD_REPORT_NAMES = names.join('\n')
        def links = names.collect { name ->
            def url = buildReportUrl(name)
            addSidebarLink(url, "Report: ${name}", 'document.png')
            "<${url}|${name}>"
        }
        env.BUILD_REPORT_LINKS = links.join(' · ')
    }

    boolean flagged = unsuccessful || (currentBuild.result in ['UNSTABLE', 'FAILURE'])
    if (!flagged || !env.BUILD_REPORT_NAMES || env.BUILD_REPORT_BADGES == 'true') return
    env.BUILD_REPORT_BADGES = 'true'

    try {
        dir(env.ARTIFACT_PATH) {
            archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true
        }
    } catch (Exception e) {
        echo "[WARN] Could not archive build reports now (links resolve after the final archive): ${e.message}"
    }
    env.BUILD_REPORT_NAMES.split('\n').each { name ->
        try {
            addShieldsDoubleBadge("report-${name}", 'report', name, '555555', 'blue', null, buildReportUrl(name))
        } catch (Exception e) {
            echo "[WARN] Could not add report badge for ${name}: ${e.message}"
        }
    }
}

String buildReportUrl(String name) {
    // Archived from inside dir('artifacts'), so the reports sit under artifact/reports/.
    return "${env.BUILD_URL}artifact/reports/${java.net.URLEncoder.encode(name, 'UTF-8').replace('+', '%20')}"
}

/**
 * Fail the build with a known error — skips AI analysis and sets a custom badge.
 * Use for early failures where the cause is obvious (workspace conflict, missing env vars, tool not found).
 */
def failWithKnownError(String badgeLabel, String explanation, String fix = null) {
    def stage = env.CURRENT_STAGE ?: 'Validate'
    def errors = [[severity: 'ERROR', stage: stage, message: explanation]]
    if (fix) errors[0].fix = fix
    def analysis = [explanation: explanation, errors: errors, knownError: true]
    env.ERROR_ANALYSIS = new groovy.json.JsonBuilder(analysis).toString()
    env.SKIP_AI_ANALYSIS = 'true'
    error(explanation)
}

/**
 * Fail as a known error (no AI analysis) listing the errors build tools reported in
 * ARTIFACT_PATH/build_errors.txt — Unity writes that file from any log line carrying [BUILD-ERROR]
 * (PipelineWarnings.cs). The file gets the same treatment as unity_errors.log (linkFailureLog): a red
 * 'build errors | N' badge, a summary box listing the errors, a sidebar link, a description entry
 * and env.BUILD_ERRORS_URL for the Slack post. No-op when the file is absent or empty, so the
 * caller's own failure handling still runs. Call it only once the build step has already failed.
 */
def failWithReportedBuildErrors() {
    if (!env.ARTIFACT_PATH) return
    def reportPath = "${env.ARTIFACT_PATH}/build_errors.txt"

    def lines = []
    try {
        if (!fileExists(reportPath)) return
        lines = readFile(reportPath).readLines().collect { it.trim() }.findAll { it }.unique()
    } catch (Exception e) {
        echo "[WARN] Could not read ${reportPath}: ${e.message}"
        return
    }
    if (!lines) return

    echo "========== BUILD ERRORS (${lines.size()}) =========="
    lines.each { echo "  - ${it}" }
    echo "=============================================="

    def stage = env.CURRENT_STAGE ?: 'Unity Build'
    def maxShown = 10
    def shown = lines.take(maxShown)
    def more = lines.size() > maxShown ? "\n… ${lines.size() - maxShown} more — see build_errors.txt" : ''
    def explanation = "Build errors (${lines.size()}):\n${shown.collect { "• ${it}" }.join('\n')}${more}"

    def url = linkFailureLog('build_errors.txt', 'Build Errors', env.BUILD_ERRORS_LINKED == 'true', [badgeId: 'build-errors', logo: null, count: lines.size(), details: shown])
    if (url) {
        env.BUILD_ERRORS_URL = url
        env.BUILD_ERRORS_LINKED = 'true'
    }

    def errors = shown.collect { [severity: 'ERROR', stage: stage, message: it] }
    def analysis = [explanation: explanation, errors: errors, knownError: true]
    env.ERROR_ANALYSIS = new groovy.json.JsonBuilder(analysis).toString()
    env.SKIP_AI_ANALYSIS = 'true'
    error(explanation)
}

/**
 * Validate required environment variables — fails with known error if any are missing.
 * Use in Validate stage instead of inline checks in each jenkinsfile.
 */
def validateRequiredEnvVars(List<String> required) {
    def missing = required.findAll { !env[it]?.trim() }
    if (missing) {
        failWithKnownError(
            'Missing Config',
            "Missing required environment variables: ${missing.join(', ')}",
            "Set in: Job > Configure > Environment Variables"
        )
    }
}


def printBuildInfo(String buildType, String branch) {
    env.BUILD_NODE = env.NODE_NAME  // Capture while inside node{} - NODE_NAME may be lost in post blocks
    env.BUILD_TYPE = buildType
    echo "Build Type: ${buildType} | Branch: ${branch} | Node: ${env.NODE_NAME} | Build #${env.BUILD_NUMBER}"

    // Notify dashboard that build has started
    notifyDashboard('STARTED', [buildType: buildType, branch: branch])
}

def setupBuildPaths(Map config = [:]) {
    def projectFolder = config.projectFolder ?: 'UnityProject'

    env.UNITY_PROJECT = config.unityProject ?: "${env.WORKSPACE}/plastic/${projectFolder}"
    env.BUILD_PATH = config.buildPath ?: "${env.WORKSPACE}/build"
    env.ARTIFACT_PATH = config.artifactPath ?: "${env.WORKSPACE}/artifacts"

    if (config.platform == 'iOS') {
        env.XCODE_BASE_PATH = config.xcodePath ?: "${env.WORKSPACE}/xcode"
    }

    def paths = "Unity Project: ${env.UNITY_PROJECT} | Build: ${env.BUILD_PATH} | Artifacts: ${env.ARTIFACT_PATH}"
    if (env.XCODE_BASE_PATH) paths += " | Xcode: ${env.XCODE_BASE_PATH}"
    echo paths
}

/**
 * Extracts the Unity Accelerator cache server host from JENKINS_URL.
 * The Accelerator runs on the Jenkins master on port 10080.
 * Stores the result in env.CACHE_SERVER_ENDPOINT for use by runUnityCommand().
 */
def resolveCacheServer() {
    if (env.CACHE_SERVER_ENDPOINT) return // Already resolved

    env.CACHE_SERVER_ENDPOINT = 'odd-jenkins:10080'
    echo "[INFO] Cache server: ${env.CACHE_SERVER_ENDPOINT}"
}

/**
 * UnityDataTool release tag the agents install. Pinned, not "latest": from v2.3.0 the tool
 * calls GetDllVersion() and requires a Unity 6.7+ UnityFileSystemApi, so with the 6000.0
 * editor DLL that runUnityDataTool() stages it dies with EntryPointNotFoundException.
 * Job env UNITY_DATA_TOOL_VERSION overrides (e.g. v2.4.0 for a 6.7+ project).
 */
def unityDataToolVersion() {
    return env.UNITY_DATA_TOOL_VERSION ?: 'v2.2.1'
}

// ============================================================================
// BADGE FUNCTIONS
// ============================================================================

// Pre-encoded base64 icon files in resources/icons/ for shields.io logo= parameter
// Generated from colorful 48x48 PNGs (icons8/dashboard-icons) via: base64 -i icon.png -o icon.b64
def getIconDataUri(String platform) {
    def iconFiles = [
        Android:             'icons/googleplay.b64',
        iOS:                 'icons/apple.b64',
        Amazon:              'icons/amazon.b64',
        StandaloneWindows64: 'icons/windows.b64',
        StandaloneLinux64:   'icons/linux.b64',
        StandaloneOSX:       'icons/apple.b64',
        Switch:              'icons/switch.b64',
    ]
    def iconFile = iconFiles[platform]
    if (!iconFile) return null
    try {
        def b64 = libraryResource(iconFile).trim()
        return "data:image/png;base64,${b64}"
    } catch (Exception e) {
        echo "[DEBUG] Could not load icon ${iconFile}: ${e.message}"
        return null
    }
}

def addShieldsBadge(String id, message, color, link = null, String logo = null) {
    def encodedMessage = message.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def badgeUrl = "https://img.shields.io/badge/${encodedMessage}-${color}?style=plastic"
    if (logo) badgeUrl += "&logo=${java.net.URLEncoder.encode(logo, 'UTF-8')}"
    def badgeHtml = link ? "<a href='${link}'><img src='${badgeUrl}'/></a>" : "<img src='${badgeUrl}'/>"
    removeBadges(id: id)
    addBadge(text: badgeHtml, id: id)
}

def addShieldsDoubleBadge(String id, label, message, labelColor = null, messageColor, labelLink = null, messageLink = null, String logo = null) {
    def encodedLabel = label.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def encodedMessage = message.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def badgeUrl = "https://img.shields.io/badge/${encodedLabel}-${encodedMessage}-${messageColor}?style=plastic"
    if (labelColor) badgeUrl += "&labelColor=${labelColor}"
    if (logo) badgeUrl += "&logo=${java.net.URLEncoder.encode(logo, 'UTF-8')}"
    def link = messageLink ?: labelLink
    def badgeHtml = link ? "<a href='${link}'><img src='${badgeUrl}'/></a>" : "<img src='${badgeUrl}'/>"
    removeBadges(id: id)
    addBadge(text: badgeHtml, id: id)
}

/**
 * Badge colour per build state. Besides the Jenkins results there are two in-flight states:
 *   RUNNING   blue    - a stage is executing
 *   UPLOADING orange  - the artifact is built and on the local share, uploads still running.
 *                       Distinct from blue/green so the build list shows "you can grab the file
 *                       now" while a slow Google Drive / store upload is still going.
 */
private String resultBadgeColor(String result = null) {
    def status = result ?: currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'
    return [SUCCESS: 'brightgreen', UNSTABLE: 'yellow', FAILURE: 'red', ABORTED: 'lightgrey',
            UPLOADING: 'orange', RUNNING: 'blue'][status] ?: 'blue'
}

def addPlatformBadge(String color = null) {
    def platform = env.PLATFORM
    def logo = getIconDataUri(platform)
    def label = [
        Android: 'Google Play', iOS: 'App Store', Amazon: 'Amazon',
        StandaloneWindows64: 'Windows', StandaloneLinux64: 'Linux',
        StandaloneOSX: 'macOS', Switch: 'Switch',
        GameCoreXboxSeries: 'Xbox Series', GameCoreXboxOne: 'Xbox One', PS5: 'PS5'
    ][platform] ?: platform ?: 'Build'
    addShieldsBadge('platform', label, color ?: 'blue', null, logo)
}

def addBuildTypeBadge(String buildType, String color = null) {
    addShieldsBadge('env', buildType, color ?: 'blue')
}

def addBranchBadge(String color = null) {
    addShieldsDoubleBadge('branch', "${env.PLASTICSCM_BRANCH}", "cs${env.PLASTICSCM_CHANGESET_ID}", '555555', color ?: 'blue')
}

def addBuildBadges(String buildType) {
    addPlatformBadge()
    addBuildTypeBadge(buildType)
    addBranchBadge()
}

def addFailureBadge(String stageName) {
    addShieldsDoubleBadge('failure', 'failed', stageName, 'red', 'red')
}

/**
 * Re-color all badges to reflect the final build result.
 * Called from finalizeBuild() so the build list shows result at a glance.
 */
def updateBadgesForResult(String result = null) {
    def status = result ?: currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'
    def color = resultBadgeColor(status)
    if (env.BUILD_TYPE) addBuildTypeBadge(env.BUILD_TYPE, color)
    if (env.PLASTICSCM_CHANGESET_ID) addBranchBadge(color)
    if (status == 'FAILURE' && env.FAILED_STAGE) addFailureBadge(env.FAILED_STAGE)
    // The build is over - the result colours say what happened, the live status badge would only mislead.
    clearStatusBadge()
}

// ----------------------------------------------------------------------------
// Live status badge - what the build is doing right now.
//
// The build-history sidebar only renders badges and the description, so this is the one place
// a running build can say "Xcode Archive" or "uploading GDrive" at a glance. One badge, id
// 'status', replaced on every update and removed by updateBadgesForResult() when the build ends.
// Skipped for warm-up builds (nothing to report, and their post block doesn't finalize).
// ----------------------------------------------------------------------------

def setStatusBadge(String label, String message, String state = 'RUNNING') {
    if (env.WARMUP == 'true') return
    try {
        addShieldsDoubleBadge('status', label, message, '555555', resultBadgeColor(state))
    } catch (Exception e) {
        echo "[WARN] Could not update status badge: ${e.message}"
    }
}

def clearStatusBadge() {
    try {
        removeBadges(id: 'status')
    } catch (Exception e) {
        echo "[WARN] Could not remove status badge: ${e.message}"
    }
}

/** True once sendUploadNotification() has registered the upload targets for this build. */
private boolean inUploadPhase() {
    return (env.UPLOAD_GDRIVE_STATUS || env.UPLOAD_LOCAL_STATUS || env.UPLOAD_STORE_STATUS) as boolean
}

/**
 * Record the running stage (failure reporting reads env.CURRENT_STAGE) and show it on the
 * status badge. Replaces the bare `env.CURRENT_STAGE = '...'` at the top of every stage.
 * Once uploads have started the badge tracks them instead (refreshUploadStatusBadge), so the
 * post-build stages that run alongside the uploads don't overwrite "uploading ...".
 */
def setCurrentStage(String name) {
    env.CURRENT_STAGE = name
    if (!inUploadPhase()) setStatusBadge('stage', name)
}

/**
 * Status badge for the upload phase, refreshed whenever an upload target changes state:
 *   uploading | GDrive, TestFlight   orange  - still running
 *   upload failed | GDrive           red     - nothing pending, at least one failed
 *   uploads | done                   green   - everything landed
 * When everything has landed and Google Drive didn't replace the local artifact badge (jobs with
 * no Drive upload, e.g. Steam), the orange local badge is re-coloured green - orange means
 * "not uploaded yet", and by then it is.
 */
def refreshUploadStatusBadge() {
    def names = [gdrive: 'GDrive', local: 'Local', store: env.UPLOAD_STORE_NAME ?: 'Store']
    def states = [gdrive: env.UPLOAD_GDRIVE_STATUS, local: env.UPLOAD_LOCAL_STATUS, store: env.UPLOAD_STORE_STATUS]
    def pending = []
    def failed = []
    for (String key : ['local', 'gdrive', 'store']) {
        if (states[key] == 'pending') pending << names[key]
        else if (states[key] == 'failed') failed << names[key]
    }
    if (pending) {
        setStatusBadge('uploading', pending.join(', '), 'UPLOADING')
    } else if (failed) {
        setStatusBadge('upload failed', failed.join(', '), 'FAILURE')
    } else {
        setStatusBadge('uploads', 'done', 'SUCCESS')
        if (env.LOCAL_ARTIFACT_BADGE_ID && env.GDRIVE_ARTIFACT_BADGE != 'true') {
            addShieldsBadge(env.LOCAL_ARTIFACT_BADGE_ID, env.LOCAL_ARTIFACT_BADGE_ID, resultBadgeColor('SUCCESS'), env.LOCAL_ARTIFACT_URL)
        }
    }
}

/**
 * Artifact badge + sidebar link for the copy on the local share. Added the moment the local
 * copy lands, so people can grab the build while the (slow) Google Drive upload is still
 * running - the same badge + "Download <TYPE>" pair the Drive upload adds, but orange and
 * pointing at the file:// path. The badge id is the file extension, exactly what
 * uploadToGoogleDrive() uses, so its brightgreen Drive badge replaces this one on completion.
 *
 * @param uncFolder  share folder holding the copy, e.g. \\odd-jenkins\builds\Job\Debug\1.2.3
 * @param fileName   the installable at the top of that folder (apk/aab/ipa/nsp/xvc/msixvc/pkg),
 *                   a .nspd folder name, or null when the build is a loose folder (Steam)
 */
def addLocalArtifactLinks(String uncFolder, String fileName) {
    if (!uncFolder) return
    def folderUrl = localShareUrl(uncFolder)
    def badgeId
    def url
    if (fileName && !fileName.toLowerCase().endsWith('.nspd')) {
        badgeId = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
        url = localShareUrl("${uncFolder}\\${fileName}")
        addSidebarLink(url, "Download ${badgeId.toUpperCase()} (Local)", 'https://img.icons8.com/fluency/48/folder-invoices--v1.png')
    } else {
        // Switch .nspd and loose-folder builds: the folder is the artifact (mirrors the Drive badges)
        badgeId = fileName ? 'nspd' : 'build'
        url = folderUrl
    }
    env.LOCAL_ARTIFACT_BADGE_ID = badgeId
    env.LOCAL_ARTIFACT_URL = url
    // Don't downgrade a Drive badge that already landed (the uploads run in parallel)
    if (env.GDRIVE_ARTIFACT_BADGE != 'true') {
        addShieldsBadge(badgeId, badgeId, resultBadgeColor('UPLOADING'), url)
    }
}

/**
 * Google Drive artifact badge (brightgreen, id = file extension / 'nspd' / 'build'). Replaces the
 * orange local-share badge of the same id and marks that it did, so refreshUploadStatusBadge()
 * leaves the badge alone afterwards.
 */
def addDriveArtifactBadge(String badgeId, String link) {
    addShieldsBadge(badgeId, badgeId, resultBadgeColor('SUCCESS'), link)
    env.GDRIVE_ARTIFACT_BADGE = 'true'
}

/**
 * URL for a path on the local build share (\\server\share\...).
 *
 * Browsers refuse to open file:// from an http page and the sidebar-link plugin rejects the scheme
 * outright ("URI scheme file is not allowed"), so a file:// link is dead everywhere it is shown.
 * Set LOCAL_SHARE_HTTP_BASE (job or global env) to the http(s) URL that serves the share root and
 * every local link - badge, sidebar, Slack - becomes a real download. Simplest setup: on the
 * controller, junction JENKINS_HOME/userContent/builds -> the share folder and set
 * LOCAL_SHARE_HTTP_BASE=http://<jenkins-host>:8080/userContent/builds. Without it the file:// form
 * is returned (badge + Slack text only; addSidebarLink skips it).
 */
def localShareUrl(String uncPath) {
    if (!uncPath) return ''
    def norm = uncPath.replace('\\', '/')
    def base = (env.LOCAL_SHARE_HTTP_BASE ?: '').trim()
    if (!base) return "file:${norm}".replace(' ', '%20')

    def shareRoot = (env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds').replace('\\', '/')
    def rel = norm.toLowerCase().startsWith(shareRoot.toLowerCase()) ? norm.substring(shareRoot.length()) : norm
    def encoded = rel.split('/').findAll { it }.collect { java.net.URLEncoder.encode(it, 'UTF-8').replace('+', '%20') }.join('/')
    return "${base.replaceFirst('/+$', '')}/${encoded}"
}

def addSidebarLink(String url, String title, String iconUrl) {
    if (!url) { echo "No URL provided for sidebar link: ${title}"; return }
    if (url.startsWith('file:')) {
        echo "[INFO] Sidebar link '${title}' skipped: the sidebar-link plugin rejects file:// URLs - set LOCAL_SHARE_HTTP_BASE to serve the share over http (see localShareUrl)"
        return
    }
    try {
        def linkActionClass = this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
        def action = linkActionClass.newInstance(url, title, iconUrl)
        currentBuild.rawBuild.getActions().add(action)
        echo "${title}: ${url}"
    } catch (Exception e) {
        echo "Failed to add sidebar link '${title}': ${e.message}"
    }
}

def addGoogleDriveLinks(String folderLink, String fileLink, String fileType, String fileIconUrl = null) {
    addSidebarLink(folderLink, 'Google Drive Folder', 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/google-drive.png')
    def icon = fileIconUrl ?: 'https://img.icons8.com/fluency/48/android-os.png'
    addSidebarLink(fileLink, "Download ${fileType}", icon)
}

// ============================================================================
// PREFLIGHT - JENKINS SANDBOX PERMISSIONS
// ============================================================================

/**
 * Verify all Jenkins sandbox permissions required by the pipeline.
 * Call this in preflight to fail fast if an admin needs to approve signatures.
 */
def preflightJenkinsPermissions() {
    // All permission testing is done in @NonCPS to avoid CPS serialization of
    // non-serializable Jenkins objects (CpsFlowExecution, Jenkins, Computer, etc.).
    // This function runs inside a parallel block — CPS checkpoints from other
    // parallel branches would try to serialize THIS branch's local variables.
    // @NonCPS makes the method atomic from CPS's perspective: no intermediate
    // serialization points, so non-serializable locals are never persisted.
    def result = _testAllPermissions(currentBuild)

    if (result.failures) {
        def msg = "[ERROR] Jenkins sandbox permissions not approved (${result.passed} passed, ${result.failures.size()} failed):\n"
        msg += result.failures.collect { "  - ${it}" }.join("\n")
        msg += "\n\nFix: Go to Manage Jenkins > In-process Script Approval and approve the pending signatures."
        error msg
    }

    echo "[OK] All ${result.passed} Jenkins sandbox permissions verified"
}

@com.cloudbees.groovy.cps.NonCPS
private def _testAllPermissions(currentBuildWrapper) {
    def failures = []
    def passed = 0

    // Helper: each permission gets its own try/catch so a sandbox rejection
    // for one method doesn't prevent subsequent methods from being tested.
    // This ensures ALL pending signatures are queued at once in Jenkins'
    // Script Approval page, instead of one-at-a-time.
    def testPermission = { String name, Closure c ->
        try {
            c()
            passed++
        } catch (Exception e) {
            // Only count actual sandbox rejections as permission failures.
            // Other exceptions (NullPointerException, ClassNotFoundException, etc.) are
            // expected cascading failures when prior permissions were rejected, or runtime
            // issues unrelated to sandbox approval — reporting them as "permissions not
            // approved" hides the real error.
            def isSandboxRejection = e.getClass().getName().contains('RejectedAccessException') ||
                                      e.message?.contains('Scripts not permitted to use')
            if (isSandboxRejection) {
                failures << "${name}: ${e.message}"
            }
        }
    }

    // --- RunWrapper.getRawBuild & WorkflowRun.getExecution ---
    def rawBuild = null
    def execution = null
    testPermission('currentBuild.rawBuild') {
        rawBuild = currentBuildWrapper.rawBuild
    }
    testPermission('rawBuild.getExecution') {
        if (rawBuild) execution = rawBuild.getExecution()
    }

    // --- DepthFirstScanner + allNodes ---
    def scanner = null
    def flowNodes = null
    testPermission('DepthFirstScanner.new') {
        scanner = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner()
    }
    testPermission('DepthFirstScanner.allNodes') {
        if (scanner && execution) flowNodes = scanner.allNodes(execution)
    }

    // --- FlowNode methods ---
    def firstNode = null
    if (flowNodes) {
        for (def node : flowNodes) { firstNode = node; break }
    }
    testPermission('FlowNode.getId') {
        if (firstNode) firstNode.getId()
    }
    testPermission('FlowNode.getDisplayName') {
        if (firstNode) firstNode.getDisplayName()
    }
    testPermission('FlowNode.getParents') {
        if (firstNode) firstNode.getParents()
    }
    testPermission('FlowNode.getAction(LabelAction)') {
        if (firstNode) firstNode.getAction(org.jenkinsci.plugins.workflow.actions.LabelAction)
    }

    // --- LogAction.getLogText & AnnotatedLargeText.writeLogTo ---
    testPermission('LogAction.getLogText/writeLogTo') {
        if (execution) {
            def logNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : logNodes) {
                def logAction = node.getAction(org.jenkinsci.plugins.workflow.actions.LogAction.class)
                if (logAction) {
                    def logText = logAction.getLogText()
                    def sw = new java.io.StringWriter()
                    logText.writeLogTo(0, sw)
                    break
                }
            }
        }
    }

    // --- TagsAction.getTagValue (for detecting skipped stages) ---
    testPermission('TagsAction.getTagValue') {
        if (execution) {
            def tagNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : tagNodes) {
                def tagsAction = node.getAction(org.jenkinsci.plugins.workflow.actions.TagsAction.class)
                if (tagsAction) {
                    tagsAction.getTagValue('STAGE_STATUS')
                    break
                }
            }
        }
    }

    // --- ThreadNameAction.getThreadName ---
    testPermission('ThreadNameAction.getThreadName') {
        if (execution) {
            def threadNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : threadNodes) {
                def threadAction = node.getAction(org.jenkinsci.plugins.workflow.actions.ThreadNameAction.class)
                if (threadAction) {
                    threadAction.getThreadName()
                    break
                }
            }
        }
    }

    // --- FlowExecution methods ---
    testPermission('FlowExecution.getCurrentHeads') {
        if (execution) execution.getCurrentHeads()
    }
    testPermission('FlowExecution.getCauseOfFailure') {
        if (execution) execution.getCauseOfFailure()
    }

    // --- Build log access ---
    testPermission('rawBuild.getLog') {
        if (rawBuild) rawBuild.getLog(1)
    }

    // --- Build causes ---
    testPermission('getBuildCauses') {
        currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
    }

    // --- User lookup + email (for Slack) ---
    testPermission('User.getById') {
        hudson.model.User.getById("_preflight_check_", false)
    }
    testPermission('User.getProperty') {
        def causes = currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (causes && causes[0]?.userId) {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) user.getProperty(hudson.tasks.Mailer.UserProperty)
        }
    }
    testPermission('Mailer.UserProperty.getAddress') {
        def causes = currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (causes && causes[0]?.userId) {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) {
                def emailProp = user.getProperty(hudson.tasks.Mailer.UserProperty)
                emailProp?.getAddress()
            }
        }
    }

    // --- Sidebar link plugin (classLoader + loadClass + getActions + add) ---
    testPermission('classLoader.loadClass') {
        this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
    }
    testPermission('rawBuild.getActions') {
        if (rawBuild) rawBuild.getActions()
    }
    testPermission('LinkAction.newInstance') {
        try {
            def clazz = this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
            clazz.newInstance("http://test", "test", "")
        } catch (ClassNotFoundException e) {
            // Plugin not installed, skip
        }
    }

    // --- Jenkins.get + Label/Node/Computer/Executor API (for pickNode, isJobDisabled) ---
    def jenkins = null
    testPermission('Jenkins.get') {
        jenkins = jenkins.model.Jenkins.get()
    }
    testPermission('Jenkins.getItemByFullName') {
        if (jenkins) jenkins.getItemByFullName('_preflight_test_')
    }
    testPermission('Job.isDisabled') {
        // AbstractItem.isDisabled — used by warmup to skip archived jobs
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item) item.isDisabled()
        }
    }
    testPermission('Item.getParent') {
        // Used by warmup to check if jobs are in the same project folder
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item) item.parent
        }
    }
    testPermission('ItemGroup.getFullName') {
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item && item.parent) item.parent.fullName
        }
    }
    def labels = null
    testPermission('Jenkins.getLabels') {
        if (jenkins) labels = jenkins.getLabels()
    }
    testPermission('Jenkins.getLabel') {
        if (jenkins) jenkins.getLabel('_preflight_test_')
    }
    def testNode = null
    def testComputer = null
    testPermission('Label.getNodes') {
        if (labels) {
            for (def lbl : labels) {
                def nodes = lbl.getNodes()
                for (def n : nodes) { testNode = n; break }
                break
            }
        }
    }
    testPermission('Node.getDisplayName') {
        if (testNode) testNode.getDisplayName()
    }
    testPermission('Node.toComputer') {
        if (testNode) testComputer = testNode.toComputer()
    }
    testPermission('Computer.isOnline') {
        if (testComputer) testComputer.isOnline()
    }
    testPermission('Computer.numExecutors') {
        if (testComputer) testComputer.numExecutors
    }
    testPermission('Computer.getExecutors') {
        if (testComputer) testComputer.getExecutors()
    }
    testPermission('Executor.isBusy') {
        if (testComputer) {
            def executors = testComputer.getExecutors()
            for (def executor : executors) {
                executor.isBusy()
                break
            }
        }
    }
    testPermission('Executor.getCurrentExecutable') {
        if (testComputer) {
            def executors = testComputer.getExecutors()
            for (def executor : executors) {
                def executable = executor.getCurrentExecutable()
                if (executable) executable.getParent()?.getFullName()
                break
            }
        }
    }
    // pickNode resolves the owning job of a pipeline's PlaceholderTask via getOwnerTask() (it has no
    // getFullName()). Queue this signature so conflict detection isn't sent to the fallback path.
    testPermission('SubTask.getOwnerTask') {
        if (testComputer) {
            def executors = testComputer.getExecutors()
            for (def executor : executors) {
                def executable = executor.getCurrentExecutable()
                if (executable) {
                    def task = executable.getParent()
                    if (task) {
                        def owner = task.getOwnerTask()
                        if (owner) owner.getFullName()
                    }
                }
                break
            }
        }
    }

    // --- Queue API (for detecting queued builds in pickNode) ---
    testPermission('Jenkins.getQueue') {
        if (jenkins) jenkins.getQueue()
    }
    testPermission('Queue.getItems') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) queue.getItems()
        }
    }
    testPermission('Queue.Item.getAssignedLabel') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) {
                def items = queue.getItems()
                for (def item : items) {
                    item.getAssignedLabel()
                    break
                }
            }
        }
    }
    testPermission('Queue.Item.task') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) {
                def items = queue.getItems()
                for (def item : items) {
                    item.task?.getFullName()
                    break
                }
            }
        }
    }

    // --- FlowInterruptedException + causes property ---
    testPermission('FlowInterruptedException class') {
        this.class.classLoader.loadClass("org.jenkinsci.plugins.workflow.steps.FlowInterruptedException")
    }
    testPermission('FlowInterruptedException.getCauses') {
        def fie = new org.jenkinsci.plugins.workflow.steps.FlowInterruptedException(hudson.model.Result.ABORTED)
        fie.getCauses()
    }

    // --- Rejection class (input step cancellation) ---
    testPermission('Rejection class') {
        this.class.classLoader.loadClass("org.jenkinsci.plugins.workflow.support.steps.input.Rejection")
    }

    // --- AbortException class ---
    testPermission('hudson.AbortException') {
        new hudson.AbortException("preflight test")
    }

    // --- Throwable methods ---
    testPermission('Throwable.printStackTrace') {
        new Exception("preflight test").printStackTrace()
    }
    testPermission('Throwable.getClass/getName') {
        def ex = new Exception("preflight test")
        ex.getClass().getName()
    }
    testPermission('Throwable.getMessage') {
        def ex = new Exception("preflight test")
        ex.getMessage()
    }

    // --- Run.getPreviousBuild / getDescription (per-branch node affinity in pickNode) ---
    testPermission('rawBuild.getPreviousBuild') {
        if (rawBuild) rawBuild.getPreviousBuild()
    }
    testPermission('Run.getDescription') {
        if (rawBuild) rawBuild.getDescription()
    }

    return [passed: passed, failures: failures]
}

// ============================================================================
// USER & SCM FUNCTIONS
// ============================================================================

def captureBuildUser() {
    def causes = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    env.BUILD_USER = causes ? causes[0].userName : 'System'
    env.BUILD_USER_EMAIL = ''
    if (causes && causes[0].userId) {
        try {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) {
                def emailProperty = user.getProperty(hudson.tasks.Mailer.UserProperty)
                env.BUILD_USER_EMAIL = emailProperty?.getAddress() ?: ''
            }
        } catch (Exception e) {
            echo "[WARN] Could not get user email: ${e.message}"
        }
    }
    echo "[INFO] Build user: ${env.BUILD_USER}, email: ${env.BUILD_USER_EMAIL ?: '(none)'}"
    return [user: env.BUILD_USER, email: env.BUILD_USER_EMAIL]
}

def updateBranchDescription() {
    if (env.PLASTICSCM_BRANCH) {
        def branchName = env.PLASTICSCM_BRANCH.replaceAll('^/', '')
        // Record the node alongside the branch so pickNode() can prefer this same (warm-cache)
        // agent the next time this branch builds. Kept human-readable on one line; any
        // explanations are appended on later lines, so pickNode only parses the first line.
        currentBuild.description = env.NODE_NAME ? "${branchName} @${env.NODE_NAME}" : branchName
    }
}

def printScmInfo() {
    echo """=== PlasticSCM ===
Changeset: ${env.PLASTICSCM_CHANGESET_ID} | Branch: ${env.PLASTICSCM_BRANCH} | Author: ${env.PLASTICSCM_AUTHOR}
Built by: ${env.BUILD_USER} (${env.BUILD_USER_EMAIL ?: 'no email configured'})
=================="""

    // Update branch badge with final confirmed values (checkout may have resolved differently)
    if (env.PLASTICSCM_CHANGESET_ID) addBranchBadge()
    updateBranchDescription()
}

/**
 * Add the game project's Plastic SCM change history to the Jenkins build page.
 * This compensates for Jenkins only showing changes from the @Library SCM checkout,
 * not the manually checked-out game project.
 */
def addChangeHistorySummary(String changeHistory) {
    if (!changeHistory?.trim()) return
    try {
        def branch = env.PLASTICSCM_BRANCH?.replaceAll('^/', '') ?: ''
        def cs = env.PLASTICSCM_CHANGESET_ID ?: ''
        def author = env.PLASTICSCM_AUTHOR?.split('@')?.getAt(0) ?: ''

        def html = "<b>Plastic SCM</b> &mdash; ${branch} @ cs${cs}"
        if (author) html += " by ${author}"
        html += "<br/><ul>"
        changeHistory.split('\n').each { line ->
            // Format: "- `csNNNN` author: comment"
            def cleaned = line.replaceFirst(/^-\s*/, '')
                .replaceAll('`', '')
            html += "<li>${cleaned}</li>"
        }
        html += "</ul>"
        manager.createSummary("notepad.png").appendText(html, false)
    } catch (Exception e) {
        echo "[WARN] Failed to add change history summary: ${e.message}"
    }
}

def calculateBuildVersion(int versionCodeBase, boolean forUpload = false) {
    def branchSuffix = "-${env.PLASTICSCM_BRANCH.replaceAll('[/\\\\]', '')}"
    env.VERSION = "${env.BASE_VERSION}.${env.PLASTICSCM_CHANGESET_ID}.${env.BUILD_NUMBER}${branchSuffix}"

    // Stores (Google Play / Amazon) permanently burn every versionCode they accept and require a
    // strictly increasing value. BUILD_NUMBER is a per-job monotonic counter, so base + BUILD_NUMBER
    // is unique across every build of this job. Increment whenever the build can reach a store —
    // /main (auto store upload) OR a forced upload from any branch — otherwise feature builds that
    // stay local keep the bare base so repeated QA sideloads share a stable versionCode.
    def willUpload = env.PLASTICSCM_BRANCH == '/main' || forUpload
    def versionCode = willUpload ? versionCodeBase + env.BUILD_NUMBER.toInteger() : versionCodeBase
    env.ANDROID_VERSION_CODE = versionCode.toString()

    def reason = env.PLASTICSCM_BRANCH == '/main' ? 'main' : (forUpload ? 'force-upload' : 'feature')
    def vcDetail = willUpload ? "${versionCodeBase} + ${env.BUILD_NUMBER} = ${env.ANDROID_VERSION_CODE} (${reason})" : "${env.ANDROID_VERSION_CODE} (${reason})"
    echo "Version: ${env.VERSION} | versionCode: ${vcDetail}"

    return [version: env.VERSION, versionCode: env.ANDROID_VERSION_CODE]
}

// ============================================================================
// SLACK FUNCTIONS
// ============================================================================

def getSlackMention(String userName, String userEmail) {
    if (!userEmail) return userName
    try {
        def slackUserId = slackUserIdFromEmail(email: userEmail, tokenCredentialId: 'slack-token', botUser: true)
        return slackUserId ? "<@${slackUserId}>" : userName
    } catch (Exception e) {
        echo "[WARN] Could not find Slack user for ${userEmail}: ${e.message}"
        return userName
    }
}

def sendSlackMessage(Map config) {
    def channel = config.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def message = config.message

    try {
        slackSend(
            channel: channel,
            tokenCredentialId: 'slack-token',
            message: message,
            botUser: true
        )
    } catch (Exception e) {
        echo "[WARN] Failed to send Slack message: ${e.message}"
    }
}

def notifyLocalCopyReady(String fileUrl) {
    try {
        if (!env.BUILD_USER_EMAIL) return
        def slackUserId = slackUserIdFromEmail(email: env.BUILD_USER_EMAIL, tokenCredentialId: 'slack-token', botUser: true)
        if (!slackUserId) return
        sendSlackMessage(
            channel: slackUserId,
            message: ":open_file_folder: Local copy ready for <${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>:\n<${localShareUrl(env.LOCAL_BUILD_PATH)}|${env.LOCAL_BUILD_PATH}>"
        )
    } catch (Exception e) {
        echo "[WARN] Failed to DM local copy notification: ${e.message}"
    }
}

def sendSlackBuildNotification(Map config) {
    def channel = config.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def buildType = config.buildType
    def status = config.status
    def branch = config.branch
    def changesetId = config.changesetId
    def version = config.version
    def errorAnalysis = config.errorAnalysis ?: ""
    def buildUser = config.buildUser
    def buildUserEmail = config.buildUserEmail
    def appIcon = config.appIcon
    def platform = config.platform ?: env.PLATFORM
    def changeHistory = config.changeHistory ?: env.CHANGE_HISTORY
    def uploadStatus = config.uploadStatus ?: []
    def timestamp = config.timestamp              // If set, update existing message instead of sending new one

    def colorMap = [success: 'good', failure: 'danger', aborted: 'warning', unstable: 'warning']
    def emojiMap = [success: ':white_check_mark:', failure: ':x:', aborted: ':no_entry:', unstable: ':warning:']
    def statusTextMap = [success: 'succeeded', failure: 'failed', aborted: 'aborted', unstable: 'unstable']
    def storeIcons = [
        Android: 'https://img.icons8.com/fluency/48/android-os.png',
        iOS: 'https://img.icons8.com/ios-filled/50/FFFFFF/mac-os.png',
        Amazon: 'https://img.icons8.com/color/48/amazon.png',
        StandaloneWindows64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/steam.png',
        StandaloneLinux64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/steam.png',
        Switch: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/nintendo-switch.png',
        GameCoreXboxSeries: 'https://img.icons8.com/color/48/xbox.png',
        GameCoreXboxOne: 'https://img.icons8.com/color/48/xbox.png',
        PS5: 'https://img.icons8.com/color/48/play-station.png'
    ]
    // OS icons for platforms where store icon differs from OS (e.g. Steam builds)
    def osIcons = [
        StandaloneWindows64: 'https://img.icons8.com/fluency/48/windows-11.png',
        StandaloneLinux64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/linux.png'
    ]
    def storeIcon = storeIcons[platform] ?: storeIcons['Android']
    def osIcon = osIcons[platform]
    def buildTypeIcon = [Debug: ':wrench:', Alpha: ':test_tube:', Release: ':rocket:', EditorTest: ':test_tube:', PlayTest: ':test_tube:'][buildType] ?: ''
    def color = colorMap[status] ?: 'warning'
    def emoji = emojiMap[status] ?: ''
    def statusText = statusTextMap[status] ?: ''

    def message = ""
    if (status == 'failure' && env.FAILED_STAGE) {
        if (env.FAILED_NODE_ID) {
            message += "Failed Stage: <${env.BUILD_URL}pipeline-overview/?selected-node=${env.FAILED_NODE_ID}|*${env.FAILED_STAGE}*>\n"
        } else {
            message += "Failed Stage: *${env.FAILED_STAGE}*\n"
        }
    }
    message += "Branch: `${branch}` | Changeset: `${changesetId}`"
    if (env.PLASTICSCM_AUTHOR) message += " by ${env.PLASTICSCM_AUTHOR.split('@')[0]}"
    message += "\n"
    if (buildUser) message += "Built by: ${getSlackMention(buildUser, buildUserEmail)}"
    if (env.BUILD_NODE) message += " on `${env.BUILD_NODE}`"
    if (env.BUILDSCRIPT_CHANGESET) message += " | BuildScript: `${env.BUILDSCRIPT_CHANGESET}`"
    if (buildUser || env.BUILD_NODE) message += "\n"
    if (version && (status == 'success' || status == 'unstable')) message += "Version: `${version}`"
    if (status == 'unstable' && env.UNSTABLE_REASONS) {
        message += "\n:warning: ${env.UNSTABLE_REASONS}"
    }

    def links = "<${env.BUILD_URL}|Build> | <${env.BUILD_URL}pipeline/|Pipeline> | <${env.BUILD_URL}console|Console>"
    if (env.GDRIVE_FILE_LINK) {
        def fileType = buildType == 'Debug' ? 'APK' : 'AAB'
        if (platform == 'iOS') fileType = 'IPA'
        else if (platform == 'Switch') fileType = 'NSP'
        else if (platform == 'Amazon') fileType = 'APK'
        else if (platform in ['GameCoreXboxSeries', 'GameCoreXboxOne']) fileType = 'Package'
        else if (platform == 'PS5') fileType = 'PKG'
        def sizeLabel = env.ARTIFACT_SIZE ? " (${env.ARTIFACT_SIZE})" : ''
        links += " | <${env.GDRIVE_FILE_LINK}|Download ${fileType}${sizeLabel}>"
    } else if (env.GDRIVE_FOLDER_LINK) {
        links += " | <${env.GDRIVE_FOLDER_LINK}|:file_folder: Google Drive>"
    }
    if (platform == 'Amazon' && env.AMAZON_APP_ID) {
        def latUrl = "https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/live-app-testing"
        links += " | <${latUrl}|:fire: Live App Testing>"
    }
    if (env.LOCAL_BUILD_PATH) {
        links += " | <${localShareUrl(env.LOCAL_BUILD_PATH)}|:open_file_folder: Local>"
    }
    if (status == 'failure' || status == 'unstable') {
        if (env.BUILD_ERRORS_URL) {
            links += " | <${env.BUILD_ERRORS_URL}|:no_entry: Build Errors>"
        }
        if (env.UNITY_ERRORS_URL) {
            links += " | <${env.UNITY_ERRORS_URL}|:rotating_light: Unity Errors>"
        }
        def dashUrl = env.DASHBOARD_URL ?: null
        if (dashUrl) {
            links += " | <${dashUrl}/#analyzer?job=${env.JOB_NAME}&build=${env.BUILD_NUMBER}|:mag: Analyze>"
        }
    }

    // Build upload status line from env vars (set by sendUploadNotification / updateUploadStatus)
    def uploadStatusLine = buildUploadStatusLine()

    // Override header emoji if uploads are in progress
    if (env.UPLOAD_SLACK_TS && status == 'success') {
        def hasAnyPending = [env.UPLOAD_GDRIVE_STATUS, env.UPLOAD_LOCAL_STATUS, env.UPLOAD_STORE_STATUS].any { it == 'pending' }
        def hasAnyFailed = [env.UPLOAD_GDRIVE_STATUS, env.UPLOAD_LOCAL_STATUS, env.UPLOAD_STORE_STATUS].any { it == 'failed' }
        if (hasAnyPending) {
            emoji = ':hourglass_flowing_sand:'
        } else if (hasAnyFailed) {
            emoji = ':warning:'
        }
    }

    def headerLine = "${buildTypeIcon}${emoji}  <${env.BUILD_URL}|${env.JOB_NAME}> <${env.BUILD_URL}|#${env.BUILD_NUMBER}> / ${buildType}"

    def headerElements = []
    if (appIcon) {
        headerElements << [type: 'image', image_url: appIcon, alt_text: 'App Icon']
    }
    if (osIcon) {
        headerElements << [type: 'image', image_url: osIcon, alt_text: platform]
    }
    headerElements << [type: 'image', image_url: storeIcon, alt_text: platform]
    headerElements << [type: 'mrkdwn', text: headerLine]

    def blocks = [
        [type: 'context', elements: headerElements],
        [type: 'section', text: [type: 'mrkdwn', text: message]]
    ]

    // Broken-Addressables detail (per-group breakdown + link to the archived report), populated by
    // checkBrokenAddressables() when the catalog shipped entries with no location.
    if (env.ADDRESSABLES_REPORT_DETAIL && (status == 'unstable' || status == 'failure')) {
        blocks << [type: 'divider']
        blocks << [type: 'section', text: [type: 'mrkdwn', text: env.ADDRESSABLES_REPORT_DETAIL.take(2900)]]
    }

    // Reports the build tools left in ARTIFACT_PATH/reports/.
    linkBuildReports(status == 'unstable' || status == 'failure')
    if (env.BUILD_REPORT_LINKS && (status == 'unstable' || status == 'failure')) {
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: ":page_facing_up: Reports: ${env.BUILD_REPORT_LINKS}".take(2900)]]]
    }

    if (changeHistory) {
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: "${changeHistory}"]]]
    }

    if (errorAnalysis) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(errorAnalysis)
            def explanation = parsed.explanation ?: ''
            def errors = parsed.errors ?: []

            // Block 1: Error explanation
            if (explanation) {
                // Slack section text limit is 3000 chars - leave room for prefix
                def isKnownError = parsed.knownError ?: false
                def truncatedExplanation = explanation.take(2970)
                def header = isKnownError ? ":warning: *Known Error*" : ":warning: *Error Analysis*"
                blocks << [type: 'section', text: [type: 'mrkdwn', text: "${header}\n${truncatedExplanation}"]]
            }

            // Block 2: Raw detected errors (separate from analysis)
            if (errors) {
                def errorLines = []
                def severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']
                def grouped = errors.groupBy { it.severity ?: 'UNKNOWN' }
                severityOrder.each { sev ->
                    if (grouped[sev]) {
                        grouped[sev].each { err ->
                            def stagePrefix = err.stage ? "(${err.stage}) " : ""
                            errorLines << "[${sev}] ${stagePrefix}${err.message}"
                            if (err.fix) errorLines << "  FIX: ${err.fix}"
                        }
                    }
                }
                if (errorLines) {
                    def errorText = errorLines.join('\n').take(2900)
                    blocks << [type: 'divider']
                    blocks << [type: 'section', text: [type: 'mrkdwn', text: ":mag: *Detected Errors*\n```${errorText}```"]]
                }
            }
        } catch (Exception parseEx) {
            // Fallback for non-JSON analysis (e.g. old format or AI failure message)
            blocks << [type: 'section', text: [type: 'mrkdwn', text: ":warning: ${errorAnalysis}"]]
        }
    }

    // Build report warnings (oversized shaders, textures, duplicates)
    if (status == 'success' && env.BUILD_REPORT_WARNINGS) {
        def reportLines = env.BUILD_REPORT_WARNINGS.split('\n').collect { ":warning: ${it}" }.join('\n')
        def dbLink = "<${env.BUILD_URL}artifact/artifacts/assetBundles.db|assetBundles.db>"
        def reportText = reportLines.take(2800)
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: "${reportText}\n${dbLink}"]]]
    }

    if (uploadStatusLine) {
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: uploadStatusLine]]]
    }

    // Amazon LAT instructions - manual upload required (API doesn't support large APKs)
    if (platform == 'Amazon' && status == 'success' && env.AMAZON_APP_ID) {
        def apkLink = env.GDRIVE_FILE_LINK ? "<${env.GDRIVE_FILE_LINK}|Download APK>" : (env.GDRIVE_FOLDER_LINK ? "<${env.GDRIVE_FOLDER_LINK}|Google Drive>" : 'Google Drive (see links below)')
        def latSteps = ":fire: *Live App Testing - Manual Upload Required*\n" +
            "1. ${apkLink} from Google Drive\n" +
            "2. Open <https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/submission/app-files|App Files> in the Amazon Developer Console\n" +
            "3. Click *Replace* on the existing APK and upload the new build\n" +
            "4. Go to <https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/live-app-testing|Live App Testing> and activate the build"
        blocks << [type: 'section', text: [type: 'mrkdwn', text: latSteps]]
    }

    blocks << [type: 'context', elements: [[type: 'mrkdwn', text: links]]]

    try {
        def slackResponse
        if (timestamp) {
            slackResponse = slackSend(channel: channel, timestamp: timestamp, tokenCredentialId: 'slack-token', blocks: blocks, color: color, botUser: true)
        } else {
            slackResponse = slackSend(channel: channel, tokenCredentialId: 'slack-token', blocks: blocks, color: color, botUser: true)
        }
        return slackResponse
    } catch (Exception e) {
        echo "[ERROR] Failed to send Slack notification: ${e.message}"
        echo "[DEBUG] Channel: ${channel}, AppIcon: ${appIcon}, Platform: ${platform}"
        return null
    }
}

// ============================================================================
// BUILD WARNINGS
// ============================================================================

/**
 * Add a warning that will appear in the Slack notification.
 * Can be called from any stage to surface issues to the team.
 *
 * @param warning The warning message (e.g. "Texture: bg_4k - 16.2 MB (4096x4096 RGBA32)")
 */
def addBuildWarning(String warning) {
    def current = env.BUILD_REPORT_WARNINGS ?: ''
    env.BUILD_REPORT_WARNINGS = current ? "${current}\n${warning}" : warning
}

// ============================================================================
// STEAM UPLOAD DIAGNOSTICS
// ============================================================================

/**
 * Classify a failed SteamCMD run_app_build from its console output.
 *
 * Retrying only helps for transient failures. A *rejected commit* is not
 * transient: Steam still creates the build server-side, so every retry adds
 * another duplicate build on Steamworks and re-scans the whole content root
 * for nothing. This tells steamUpload() which case it is.
 *
 * @param output  Full SteamCMD stdout/stderr for the attempt
 * @param setLive Branch the VDF asked to set live ('' when none)
 * @return Map: [retryable: boolean, summary: String, hints: List, branchSuspect: boolean]
 */
def classifySteamFailure(String output, String setLive = '') {
    def text = output ?: ''

    // Auth - never retryable, needs a human on the agent
    if (text.contains('Account Logon Denied') || text.contains('Steam Guard') ||
        text.contains('Invalid Password') || text.contains('Login Failure')) {
        return [
            retryable: false,
            summary  : 'SteamCMD login rejected (Steam Guard or bad credentials)',
            hints    : [
                'Run SteamCMD manually on the build agent and complete the Steam Guard prompt, then re-run this job.',
                "Check the 'steam-credentials' Jenkins credential is still valid."
            ],
            branchSuspect: false
        ]
    }

    // Commit rejected: "ERROR! Failed to commit build for AppID 1270920 : Failure"
    // The trailing token is a Steam EResult name - only a few of them mean "try again".
    def commit = (text =~ /Failed to commit build for AppID\s+(\d+)\s*:\s*([A-Za-z ]+)/)
    if (commit.find()) {
        def appId = commit.group(1)
        def eresult = commit.group(2).trim()
        def retryableResults = ['Timeout', 'Busy', 'ServiceUnavailable', 'Try Again Later', 'Pending', 'No Connection', 'Connect Failed']
        def isRetryable = retryableResults.any { it.equalsIgnoreCase(eresult) }

        def hints = ["The content upload itself SUCCEEDED - Steam commits the build even when this call is rejected. Check Steamworks > SteamPipe > Your Builds for app ${appId}: the build is almost certainly there, it just wasn't set live."]
        if (setLive && !isRetryable) {
            hints << "Most likely cause: branch '${setLive}' does not exist on app ${appId}. Create it in Steamworks (SteamPipe > Your Builds > 'Create new app branch'), or point the job at an existing branch with the STEAM_BRANCH_RELEASE / STEAM_BRANCH_DEBUG env var (set it to 'none' to upload without setting live)."
            hints << "Steam does not allow setting the default branch live from SteamCMD - that has to be done on the Steamworks site."
        }
        hints << "Otherwise check that depot ${env.STEAM_DEPOT_ID ?: '(depot)'} is assigned to app ${appId} and that the Steam account has publish rights."

        return [
            retryable    : isRetryable,
            summary      : "Steam rejected the build commit for app ${appId} (EResult: ${eresult})",
            hints        : hints,
            branchSuspect: (setLive && !isRetryable ? true : false)
        ]
    }

    // Set-live rejected on its own
    if (text.contains('Failed to set build live')) {
        return [
            retryable: false,
            summary  : "Steam refused to set the build live on branch '${setLive}'",
            hints    : [
                "The build uploaded - only the set-live step failed. Set it live from Steamworks, or create branch '${setLive}'.",
                'The account also needs publish rights on the app to set builds live.'
            ],
            branchSuspect: true
        ]
    }

    // Transient network / backend
    def transientHit = ['No Connection', 'Connection to Steam', 'Timeout', 'Rate Limit Exceeded',
                        'Connection Reset', 'Failed to send'].find { text.contains(it) }
    if (transientHit) {
        return [retryable: true, summary: "Transient SteamCMD failure (${transientHit})", hints: [], branchSuspect: false]
    }

    // Local content problems - retrying uploads the same broken input
    if (text.contains('Failed to get file list') || text.contains('could not find ContentRoot') ||
        text.contains('Could not find ContentRoot')) {
        return [
            retryable: false,
            summary  : 'SteamCMD could not read the content root',
            hints    : ['Check the staging path exists on the agent and that setupSteamStaging() copied the build into it.'],
            branchSuspect: false
        ]
    }

    return [retryable: true, summary: 'SteamCMD failed (see output above)', hints: [], branchSuspect: false]
}

/**
 * Explain an rclone Google Drive failure from its log. rclone repeats Google's JSON error blob
 * once per attempt, so the raw output buries the one line that matters.
 *
 * The 403 rateLimitExceeded case is worth naming precisely: consumer project 202264815644 is
 * rclone's OWN default OAuth client (rcloneClientID in backend/drive/drive.go). A remote configured
 * without client_id shares that project's Drive API quota with every rclone user on the internet,
 * so the pipeline fails whenever that pool is busy, and nothing on our side can free it up.
 * rclone.org/drive: "This shared client_id is being retired and will stop working during 2026."
 */
def classifyRcloneFailure(String output) {
    def text = output ?: ''

    if (text.contains('rateLimitExceeded') || text.contains('RATE_LIMIT_EXCEEDED') ||
        text.contains('userRateLimitExceeded') || text.contains('Quota exceeded for quota metric')) {
        def sharedClient = text.contains('project_number:202264815644') || env.RCLONE_OWN_CLIENT == 'false'
        def hints = []
        if (sharedClient) {
            hints << "The rclone remote has no client_id, so it authenticates through rclone's shared default OAuth client (Google Cloud project 202264815644). Its Drive API quota is split between every rclone user worldwide; retrying only re-uploads the file into the same exhausted bucket."
            hints << "Fix: in the ODD Google Cloud project enable the Google Drive API, create an OAuth client (type 'Desktop app'), then add client_id / client_secret to the remote in the 'rclone' Jenkins credential (rclone config file) and re-authorise it (rclone config reconnect <remote>: - the token is bound to the client). See https://rclone.org/drive/#making-your-own-client-id"
        } else {
            hints << 'Google Drive API per-minute quota exceeded on our own client. Check other jobs uploading at the same time, or raise the quota in the Google Cloud console.'
        }
        return [retryable: !sharedClient, summary: sharedClient
            ? "Google Drive API quota exhausted on rclone's shared default client ID (project 202264815644)"
            : 'Google Drive API rate limit exceeded', hints: hints]
    }

    if (text.contains('storageQuotaExceeded') || text.contains('The user\'s Drive storage quota has been exceeded')) {
        return [retryable: false, summary: 'Google Drive storage is full',
                hints: ['Free space in the Drive the rclone remote points at (or empty its trash) and re-run.']]
    }

    if (text.contains('invalid_grant') || text.contains('Token has been expired or revoked') ||
        text.contains("couldn't fetch token") || text.contains('oauth2: cannot fetch token')) {
        return [retryable: false, summary: 'rclone Google Drive token expired or revoked',
                hints: ["Re-authorise the remote (rclone config reconnect <remote>:) and update the 'rclone' Jenkins credential with the new config file."]]
    }

    if (text.contains('directory not found') || text.contains("couldn't find root directory")) {
        return [retryable: false, summary: 'rclone could not find the destination folder on Google Drive',
                hints: ["Check RCLONE_REMOTE (${env.RCLONE_REMOTE ?: 'unset'}) still points at an existing folder the authorised account can see."]]
    }

    return [retryable: true, summary: 'rclone upload failed (see rclone log above)', hints: []]
}

/**
 * Called from preflightRclone() with the result of "does the rclone config carry its own
 * client_id or a service account?". Records it for classifyRcloneFailure() and warns up front,
 * so a config still on the shared client is flagged at Startup, not after a failed upload.
 */
def reportRcloneClient(boolean ownClient) {
    env.RCLONE_OWN_CLIENT = ownClient ? 'true' : 'false'
    if (ownClient) {
        echo '[OK] rclone remote uses its own Google OAuth client / service account'
        return
    }
    echo "[WARN] rclone config has no client_id: Google Drive uploads run on rclone's SHARED default OAuth client (Google Cloud project 202264815644)."
    echo "[WARN] That project's Drive API quota is split between every rclone user worldwide - uploads fail with '403 rateLimitExceeded' whenever the pool is busy, and rclone.org/drive says the shared client is being retired during 2026."
    echo "[WARN] Fix: enable the Google Drive API in the ODD Google Cloud project, create an OAuth client (Desktop app), add client_id / client_secret to the remote in the 'rclone' Jenkins credential and re-authorise it (rclone config reconnect <remote>:). https://rclone.org/drive/#making-your-own-client-id"
}

/**
 * Fail the upload stage from rclone's --log-file: print the lines that matter (ERROR/NOTICE/Failed,
 * each once - rclone logs the same Google JSON blob per attempt), then error() with the
 * classifier's one-line summary so the failure card says why instead of "script returned exit code 1".
 */
def failRcloneUpload(String logPath) {
    def log = fileExists(logPath) ? readFile(logPath) : ''
    def lines = log.readLines().findAll { it.contains('ERROR') || it.contains('NOTICE') || it.contains('Failed') }.unique().take(20)
    if (lines) {
        echo "--- rclone errors (${logPath}) ---\n" + lines.collect { it.length() > 400 ? it.take(400) + '...' : it }.join('\n')
    } else if (!log) {
        echo "[WARN] rclone wrote no log file at ${logPath}"
    }
    def failure = classifyRcloneFailure(log)
    echo "[ERROR] ${failure.summary}"
    failure.hints.each { echo "[HINT] ${it}" }
    error(failure.summary)
}

/**
 * Print a classified Steam failure as a readable block in the console log.
 */
def reportSteamFailure(Map failure, List branches = null) {
    echo "========================================"
    echo "Steam Upload FAILED"
    echo "========================================"
    echo "Reason: ${failure.summary}"
    if (branches != null) {
        echo "Branches that exist on this app: ${branches ? branches.join(', ') : '(none reported)'}"
        echo "  ('public' is how Steam names the default branch in app info)"
    }
    (failure.hints ?: []).each { echo "  - ${it}" }
    echo "========================================"
}

// ============================================================================
// INTERNAL HELPER FUNCTIONS
// ============================================================================

def getFailedNodeId(String stageName) {
    try {
        def execution = currentBuild.rawBuild.getExecution()
        for (def node : execution.getCurrentHeads()) {
            while (node != null) {
                if (node.getDisplayName() == stageName) {
                    return node.getId()
                }
                node = node.getParents()?.find { true }
            }
        }
        for (def node : new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)) {
            if (node.getDisplayName() == stageName) {
                return node.getId()
            }
        }
    } catch (Exception e) {
        echo "Could not get node ID: ${e.message}"
    }
    return null
}

/**
 * Extract per-stage log text from FlowNodes.
 * Returns a string with stage headers and their log content,
 * limited to the last `linesPerStage` lines per stage.
 * Returns null if FlowNode API fails (caller should fall back to getLog).
 */
/**
 * Extract per-stage logs from the pipeline execution.
 * @param linesPerStage Max lines to keep per stage
 * @param onlyStage If set, only return logs for this specific stage (e.g. the failed stage)
 */
def getPerStageLogs(int linesPerStage = 1000, String onlyStage = null) {
    try {
        def execution = currentBuild.rawBuild.getExecution()
        if (!execution) {
            echo "[getPerStageLogs] No execution found"
            return null
        }

        def allNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)

        // Collect log text per node, keyed by the enclosing stage name.
        def stageLogMap = [:]   // LinkedHashMap preserves insertion order
        def stageOrder = []     // Track order stages appear

        // DepthFirstScanner returns newest first - reverse for chronological order
        def nodeList = allNodes.toList().reverse()

        for (def node : nodeList) {
            def logAction = node.getAction(org.jenkinsci.plugins.workflow.actions.LogAction.class)
            if (!logAction) continue

            def stageName = findEnclosingStage(node)
            if (!stageName) stageName = '(pipeline)'

            // Skip stages we don't care about when filtering
            if (onlyStage && stageName != onlyStage) continue

            if (!stageLogMap.containsKey(stageName)) {
                stageLogMap[stageName] = []
                stageOrder << stageName
            }

            try {
                def logText = logAction.getLogText()
                def sw = new StringWriter()
                logText.writeLogTo(0, sw)
                def text = sw.toString()
                if (text?.trim()) {
                    stageLogMap[stageName].addAll(text.readLines())
                }
            } catch (Exception logEx) {
                // Individual node log extraction failed, continue
            }
        }

        if (stageLogMap.isEmpty()) {
            if (onlyStage) {
                echo "[getPerStageLogs] No logs found for stage '${onlyStage}'"
            } else {
                echo "[getPerStageLogs] No stage logs found"
            }
            return null
        }

        // Identify skipped stages (only when returning all stages)
        def skippedStages = [] as Set
        if (!onlyStage) {
            for (def node : nodeList) {
                try {
                    def tagsAction = node.getAction(org.jenkinsci.plugins.workflow.actions.TagsAction.class)
                    if (tagsAction?.getTagValue('STAGE_STATUS') == 'SKIPPED_FOR_CONDITIONAL') {
                        def name = findEnclosingStage(node)
                        if (name) skippedStages << name
                    }
                } catch (Exception e) { /* ignore */ }
            }
            if (skippedStages) {
                echo "[getPerStageLogs] Skipped stages excluded: ${skippedStages.join(', ')}"
            }
        }

        def result = new StringBuilder()
        for (def stageName : stageOrder) {
            if (skippedStages.contains(stageName)) continue
            def lines = stageLogMap[stageName]
            if (lines.isEmpty()) continue

            def trimmedLines = lines
            if (lines.size() > linesPerStage) {
                def skipped = lines.size() - linesPerStage
                trimmedLines = ["... (${skipped} lines omitted)"] + lines[-linesPerStage..-1]
            }

            result.append("=== STAGE: ${stageName} ===\n")
            result.append(trimmedLines.join('\n'))
            result.append("=== END STAGE: ${stageName} ===\n\n")
        }

        echo "[getPerStageLogs] Extracted logs from ${stageLogMap.size()} stage(s)${onlyStage ? " (filtered to '${onlyStage}')" : ''}"
        return result.toString()

    } catch (Exception e) {
        echo "[getPerStageLogs] Failed: ${e.message}"
        return null
    }
}

/**
 * Extract a stage's logs from the raw console log by parsing [Pipeline] markers.
 * More data than the flow graph API (includes Jenkins step output, bat/sh headers, etc).
 * Used as primary method when a specific stage is requested, fallback otherwise.
 * @param stageName The stage name to extract (e.g. 'Unity Build')
 * @param maxLines Max lines to return
 */
def getStageLogsFromRawLog(String stageName, int maxLines = 5000) {
    try {
        // The WHOLE log: Run.getLog(n) returns the LAST n lines, and a Unity
        // build's console runs well past 50k lines, which left 'Prepare' and
        // 'Unity Build' outside the window and reported them as "not found".
        def logLines = currentBuild.rawBuild.getLog(Integer.MAX_VALUE)
        def stageStart = -1
        def stageEnd = -1
        def depth = 0
        def stagePattern = "[Pipeline] { (${stageName})"

        for (int i = 0; i < logLines.size(); i++) {
            def line = logLines[i]
            if (stageStart == -1) {
                if (line.contains(stagePattern)) {
                    stageStart = i
                    depth = 1
                }
            } else {
                // Track nesting depth of [Pipeline] { / }
                if (line.startsWith('[Pipeline] {')) {
                    depth++
                } else if (line.startsWith('[Pipeline] }')) {
                    depth--
                    if (depth <= 0) {
                        stageEnd = i
                        break
                    }
                }
            }
        }

        if (stageStart == -1) {
            // Say which it is: a name that matches no stage marker (fix the
            // caller's FAILED_STAGE) vs a marker the log genuinely lacks.
            def markers = []
            for (int i = 0; i < logLines.size(); i++) {
                def at = logLines[i].indexOf("[Pipeline] { (")
                if (at < 0) continue
                def end = logLines[i].lastIndexOf(")")
                def name = end > at ? logLines[i].substring(at + 14, end) : logLines[i]
                if (!markers.contains(name)) markers.add(name)
            }
            echo "[ERROR] getStageLogsFromRawLog: Stage '${stageName}' not found among ${logLines.size()} log lines. Stage markers present: ${markers ?: '(none)'}. FAILED_STAGE must match one of them exactly."
            return null
        }

        if (stageEnd == -1) stageEnd = logLines.size() - 1

        def stageLines = logLines.subList(stageStart, Math.min(stageEnd + 1, logLines.size()))
        if (stageLines.size() > maxLines) {
            def skipped = stageLines.size() - maxLines
            stageLines = stageLines.subList(stageLines.size() - maxLines, stageLines.size())
            stageLines.add(0, "... (${skipped} lines omitted)")
        }

        echo "[getStageLogsFromRawLog] Extracted ${stageLines.size()} lines for stage '${stageName}' (raw log lines ${stageStart + 1}-${stageEnd + 1})"
        return "=== STAGE: ${stageName} [FAILED] (lines ${stageStart + 1}-${stageEnd + 1}) ===\n${stageLines.join('\n')}\n=== END STAGE: ${stageName} ===\n"

    } catch (Exception e) {
        echo "[getStageLogsFromRawLog] Failed: ${e.message}"
        return null
    }
}

/**
 * Scan console log lines and return error/exception lines plus relevant context.
 *
 * Shared by printUnityErrors (inline, mid-stage) and collectUnityErrors (post).
 * Handles three shapes:
 *   1. Single error lines (Unity [Error], CS errors, .NET exceptions) followed by
 *      stack-trace continuation lines.
 *   2. Gradle failure blocks — captured verbatim from "FAILURE: Build failed with
 *      an exception." through the terminating "BUILD FAILED in <n>s" line. This is
 *      where the real cause lives (e.g. "Manifest merger failed : minSdkVersion 25
 *      cannot be smaller than version 26"). Plain keyword matching misses it because
 *      those reason lines start with "> " or "* ", not an error keyword.
 *   3. Unity's Gradle wrapper errors (CommandInvokationFailure, GradleInvokationException,
 *      "<path>AndroidManifest.xml Error:").
 */
def extractErrorLines(List lines) {
    def errorPatterns = [
        ~/\[Error\]/,
        ~/\[Exception\]/,
        ~/(?i)error CS\d+/,
        ~/Error building Player/,
        ~/UnityException/,
        ~/BuildFailedException/,
        ~/\[BUILD-ERROR\]/,
        ~/InvalidOperationException/,
        ~/NullReferenceException/,
        ~/MissingReferenceException/,
        ~/ArgumentException/,
        ~/IndexOutOfRangeException/,
        ~/FileNotFoundException/,
        ~/TypeLoadException/,
        ~/ReflectionTypeLoadException/,
        ~/(?i)Exception:.*at /,
        ~/BUILD FAILED/,
        // Gradle / Android build failures — keyword anchors for the wrapper lines.
        ~/CommandInvokationFailure/,
        ~/GradleInvokationException/,
        ~/Gradle build failed/,
        ~/Manifest merger failed/,
        ~/Execution failed for task/,
        ~/^> Task .* FAILED$/,
        ~/AndroidManifest\.xml Error:/,
    ]

    def out = []
    int n = lines.size()
    for (int i = 0; i < n; i++) {
        def line = lines[i]

        // Gradle failure summary block: capture from the FAILURE banner through the
        // terminating "BUILD FAILED in <n>s" line (capped at 60 lines for safety).
        // This block holds the "* What went wrong" cause that keyword matching misses.
        if (line.contains('FAILURE: Build failed with an exception')) {
            out << line
            int j = i + 1
            int captured = 0
            while (j < n && captured < 60) {
                out << lines[j]
                boolean isEnd = (lines[j] =~ /^BUILD FAILED in /).find()
                j++
                captured++
                if (isEnd) break
            }
            i = j - 1
            continue
        }

        def isError = errorPatterns.any { pattern -> line =~ pattern }
        if (isError) {
            out << line
            // "Building X failed with output:" (Bee/IL2CPP/NSS/linker) announces a block of tool
            // output whose lines look nothing like errors, so the continuation rules below drop the
            // actual reason and the report ends at the colon. Take the whole block: up to the next
            // log entry (a "[Pipeline]" / "[Tag]" line), capped at 150 lines.
            if (line =~ /(?i)with output:\s*$/) {
                int j = i + 1
                int captured = 0
                while (j < n && captured < 150 && !(lines[j] =~ /^\[(Pipeline|[A-Za-z]+\.[A-Za-z]+|INFO|WARN|ERROR|OK)\b/)) {
                    out << lines[j]
                    j++
                    captured++
                }
                if (j < n && captured >= 150) out << "... (tool output truncated at 150 lines - see the console log)"
                i = j - 1
                continue
            }
            // Grab following continuation lines: stack traces, or Gradle detail lines
            // ("> ...", "* ...", indented "Suggestion:"/"or ..." hints).
            for (int j = i + 1; j < n && j < i + 20; j++) {
                def nextLine = lines[j]
                if (nextLine =~ /^\s+(at |--- |UnityEngine\.|UnityEditor\.)/ ||
                    nextLine =~ /^\s+\(/ ||
                    nextLine =~ /^[>*]\s/ ||
                    nextLine =~ /^\s+(Suggestion:|or |Required by)/) {
                    out << nextLine
                } else {
                    break
                }
            }
        }
    }
    return out
}

/**
 * Link unity_errors.log (see linkFailureLog) and record env.UNITY_ERRORS_URL for the Slack post.
 *
 * Idempotent: called both inline (printUnityErrors, mid-build) and in post
 * (collectUnityErrors). The badge/env/description refresh on every call; the
 * sidebar link and summary box are added once so they don't duplicate.
 */
def linkErrorLog(String artifactRelPath, String label = 'Unity Errors') {
    def url = linkFailureLog(artifactRelPath, label, env.UNITY_ERRORS_LINKED == 'true', [badgeId: 'unity-errors', logo: 'unity'])
    if (!url) return
    env.UNITY_ERRORS_URL = url
    env.UNITY_ERRORS_LINKED = 'true'
}

/**
 * Surface a clickable link to an archived error log and return its URL (null without BUILD_URL):
 *   - a red shields.io badge on the build (links to the log),
 *   - a summary box on the build status page (manager.createSummary),
 *   - a left-sidebar link on the build status page (addSidebarLink),
 *   - an entry appended to the build description (shown in the Builds history widget),
 *   - the plain URL echoed into the stage log (for the pipeline overview).
 * artifactRelPath is relative to the 'artifacts/' dir (ARTIFACT_PATH). alreadyLinked skips the
 * summary box and sidebar link, which must be added once per build. The caller records the URL
 * and its once-only flag in env vars by literal name: this runs in the script sandbox, where
 * env[name] = value is rejected.
 *
 * opts:
 *   badgeId  - badge id, distinct per log so the logs don't replace each other's badge
 *   logo     - shields.io logo, or null for none
 *   count    - when set, the badge reads '<label lower-cased> | <count>'
 *   details  - lines listed under the link in the summary box
 *
 * Never throws: it runs on the failure path (before error() and in post), where an exception
 * would skip the Slack post and the final archive.
 *
 * The file is archived HERE, not left for post{always{}}: the Slack failure post
 * goes out from post{failure{}}, which runs BEFORE always{} archives the artifacts
 * dir, so a link that waited for the final archive was a 404 at the moment anyone
 * clicked it from Slack (build #2458). Archiving the one small file immediately
 * makes the URL live before any notification; the final archive re-archives it
 * harmlessly.
 */
def linkFailureLog(String artifactRelPath, String label, boolean alreadyLinked, Map opts) {
    if (!env.BUILD_URL) { echo "[WARN] linkFailureLog: BUILD_URL not set, skipping link"; return null }

    def url = "${env.BUILD_URL}artifact/${artifactRelPath}"
    try {
        if (env.ARTIFACT_PATH) {
            dir(env.ARTIFACT_PATH) {
                archiveArtifacts artifacts: artifactRelPath, allowEmptyArchive: true
            }
        }
    } catch (Exception e) {
        echo "[WARN] linkFailureLog: could not archive ${artifactRelPath} now (link resolves after the final archive): ${e.message}"
    }
    echo "[INFO] ${label} log: ${url}"

    try {
        // Red shields.io badge, clickable through to the log (idempotent: addShieldsBadge
        // removes the prior badge with this id before re-adding).
        if (opts.count != null) {
            addShieldsDoubleBadge(opts.badgeId, label.toLowerCase(), "${opts.count}", '555555', 'red', null, url, opts.logo)
        } else {
            addShieldsBadge(opts.badgeId, label, 'red', url, opts.logo)
        }

        if (!alreadyLinked) {
            def html = "<b>${label}:</b> <a href=\"${url}\">${artifactRelPath}</a>"
            if (opts.details) {
                html += '<ul>' + opts.details.collect { "<li>${escapeHtml(it.toString())}</li>" }.join('') + '</ul>'
            }
            manager.createSummary('error.png').appendText(html, false)
            addSidebarLink(url, label, 'symbol-warning.png')
        }

        def desc = currentBuild.description ?: ''
        def linkHtml = "<a href=\"${url}\">${label}</a>"
        if (!desc.contains(linkHtml)) {
            currentBuild.description = desc ? "${desc}<br/>${linkHtml}" : linkHtml
        }
    } catch (Exception e) {
        echo "[WARN] linkFailureLog: could not link ${artifactRelPath}: ${e.message}"
    }
    return url
}

String escapeHtml(String text) {
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
}

/**
 * Walk up parent nodes to find the enclosing stage name for a FlowNode.
 * Checks LabelAction (regular stages) and ThreadNameAction (parallel branches).
 */
def findEnclosingStage(def node) {
    def current = node
    def maxDepth = 50  // safety limit
    while (current != null && maxDepth-- > 0) {
        // LabelAction is on regular stage nodes
        def labelAction = current.getAction(org.jenkinsci.plugins.workflow.actions.LabelAction.class)
        if (labelAction) {
            def name = current.getDisplayName()
            if (name && name != 'Pipeline Start') return name
        }
        // ThreadNameAction is on parallel branch nodes (e.g. "Network", "Java")
        try {
            def threadAction = current.getAction(org.jenkinsci.plugins.workflow.actions.ThreadNameAction.class)
            if (threadAction) {
                def name = threadAction.getThreadName()
                if (name) return name
            }
        } catch (Exception ignored) {}
        current = current.getParents()?.find { true }
    }
    return null
}

// ============================================================================
// SELECTOR PARSING
// ============================================================================

/**
 * Parse a unified Plastic SCM selector string
 * Formats:
 *   /main                                      -> branch only
 *   /main/feature@repo@org@server              -> branch only (extracts /main/feature)
 *   9613                                       -> changeset only (requires branch query from PlasticSCM)
 *   9613@branch@repo@server                    -> changeset only (extracts 9613)
 *
 * Note: repSpec always comes from job's PLASTIC_REPSPEC env variable
 *
 * Returns: [changeset: String|null, branch: String|null]
 */
def parseSelector(String input) {
    if (!input?.trim()) {
        return [changeset: null, branch: '/main']
    }

    input = input.trim()

    // Check if first part is a changeset (e.g. "9613" or "9613@branch@repo@server")
    def parts = input.split('@')
    if (parts[0].isNumber()) {
        return [changeset: parts[0], branch: null]
    } else {
        // Branch: /main or /main/feature@repo@server - extract just the branch part
        def branchPart = parts[0]  // First part before any @
        def branch = branchPart.startsWith('/') ? branchPart : "/${branchPart}"
        return [changeset: null, branch: branch]
    }
}

/**
 * Parse the GITHUB_ORG_PATS job env var into a list of [org, credId] maps.
 * Spec format: "org=credentialId" pairs separated by ',' or ';', e.g.
 *   "oddgames=oddgames-scripting-pat; otherorg=other-pat"
 * Each pair maps a GitHub org to a Jenkins "Secret text" credential holding a PAT.
 * Used by configureGitHubOrgPats() to add org-scoped git insteadOf rewrites so a
 * dedicated token authenticates github.com/<org>/ repos (e.g. private UPM packages).
 */
def parseOrgPats(String spec) {
    if (!spec?.trim()) return []
    return spec.split(/[;,]/).collect { it.trim() }.findAll { it }.collect { entry ->
        def kv = entry.split('=', 2)
        if (kv.size() != 2 || !kv[0].trim() || !kv[1].trim()) {
            echo "[WARN] Ignoring malformed GITHUB_ORG_PATS entry: '${entry}' (expected 'org=credentialId')"
            return null
        }
        [org: kv[0].trim(), credId: kv[1].trim()]
    }.findAll { it != null }
}

// ============================================================================
// BUILD NOTIFICATION HELPERS
// ============================================================================

/** Returns a human-readable file size string (e.g. "12.3 MB", "1.45 GB"). Sandbox-safe. */
def formatFileSize(long bytes) {
    if (bytes >= 1073741824L) return "${Math.round(bytes / 1073741824d * 100) / 100.0d} GB"
    if (bytes >= 1048576L)    return "${Math.round(bytes / 1048576d * 10) / 10.0d} MB"
    return "${Math.round(bytes / 1024d * 10) / 10.0d} KB"
}

/**
 * Write error analysis JSON to error_analysis.txt and add a sidebar link.
 * Returns the parsed analysis object (or null on parse failure) so callers can reuse it.
 */
private def saveAnalysisFile(String errorAnalysis, String headerText) {
    def analysisFile = "${env.ARTIFACT_PATH}/error_analysis.txt"
    def analysisText = headerText
    def parsed = null
    try {
        parsed = new groovy.json.JsonSlurper().parseText(errorAnalysis)
        def analysisLabel = parsed.knownError ? 'KNOWN ERROR' : 'ERROR ANALYSIS'
        analysisText += "\n${analysisLabel}:\n${parsed.explanation}\n"
        if (parsed.errors) {
            analysisText += "\n${'=' * 50}\nDETECTED ERRORS:\n${'=' * 50}\n"
            parsed.errors.each { err ->
                def stagePrefix = err.stage ? "(${err.stage}) " : ""
                analysisText += "\n[${err.severity}] ${stagePrefix}${err.message}"
                if (err.fix) analysisText += "\n  FIX: ${err.fix}"
            }
        }
    } catch (Exception ex) {
        analysisText += "\n${errorAnalysis}"
    }
    writeFile file: analysisFile, text: analysisText
    def sidebarLabel = parsed?.knownError ? 'Known Error' : 'Error Analysis'
    def sidebarIcon = parsed?.knownError ? 'warning.png' : 'symbol-warning.png'
    addSidebarLink("${env.BUILD_URL}artifact/error_analysis.txt", sidebarLabel, sidebarIcon)
    return parsed
}

// ============================================================================
// UPLOAD PROGRESS NOTIFICATIONS
// ============================================================================

/**
 * Build the upload status line from env vars for the Slack notification.
 * Returns null if no uploads are being tracked.
 */
def buildUploadStatusLine() {
    def statusEmoji = [pending: ':hourglass_flowing_sand:', done: ':white_check_mark:', failed: ':x:']
    def parts = []

    if (env.UPLOAD_GDRIVE_STATUS) {
        def icon = statusEmoji[env.UPLOAD_GDRIVE_STATUS] ?: ':hourglass_flowing_sand:'
        def driveUrl = env.GDRIVE_FOLDER_LINK ?: env.GDRIVE_FILE_LINK
        def label = driveUrl ? "<${driveUrl}|GDrive>" : 'GDrive'
        parts << "${icon} ${label}"
    }
    if (env.UPLOAD_LOCAL_STATUS) {
        def icon = statusEmoji[env.UPLOAD_LOCAL_STATUS] ?: ':hourglass_flowing_sand:'
        def label = env.LOCAL_BUILD_PATH ? "<${localShareUrl(env.LOCAL_BUILD_PATH)}|Local>" : 'Local'
        parts << "${icon} ${label}"
    }
    if (env.UPLOAD_STORE_STATUS) {
        def icon = statusEmoji[env.UPLOAD_STORE_STATUS] ?: ':hourglass_flowing_sand:'
        def storeName = env.UPLOAD_STORE_NAME ?: 'Store'
        parts << "${icon} ${storeName}"
    }

    if (!parts) return null

    // Determine artifact type label
    def artifactType = env.UPLOAD_ARTIFACT_TYPE ?: 'Build'
    return "${artifactType}  \u2014  ${parts.join('  ')}"
}

/**
 * Send initial "uploading" notification before parallel uploads begin.
 * Shows hourglasses for each pending upload destination.
 */
/**
 * Build common Slack notification params from config + env vars.
 * Used by sendUploadNotification, updateUploadStatus, and handleBuildSuccess.
 */
private Map buildSlackParams(Map config = [:]) {
    if (!env.BUILD_USER) captureBuildUser()
    return [
        channel: config.channel ?: env.UPLOAD_SLACK_CHANNEL ?: env.SLACK_CHANNEL ?: '#builds',
        buildType: config.buildType ?: env.BUILD_TYPE ?: 'Release',
        status: 'success',
        branch: config.branch ?: env.PLASTICSCM_BRANCH ?: '-',
        changesetId: config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-',
        version: config.version ?: env.VERSION,
        buildUser: env.BUILD_USER,
        buildUserEmail: env.BUILD_USER_EMAIL,
        appIcon: config.appIcon ?: env.APP_ICON,
        platform: config.platform ?: env.PLATFORM ?: 'Unknown'
    ]
}

def sendUploadNotification(Map config) {
    def params = buildSlackParams(config)
    def uploads = config.uploads ?: []

    // Determine artifact type label for display
    def platform = params.platform
    def buildType = params.buildType
    def artifactType = 'Build'
    if (platform == 'iOS') artifactType = 'IPA'
    else if (platform == 'Switch') artifactType = 'NSP'
    else if (platform in ['StandaloneWindows64', 'StandaloneLinux64', 'GameCoreXboxSeries', 'GameCoreXboxOne', 'PS5']) artifactType = 'Folder'
    else if (platform == 'Amazon') artifactType = 'APK'
    else if (buildType == 'Debug') artifactType = 'APK'
    else artifactType = 'AAB'
    env.UPLOAD_ARTIFACT_TYPE = artifactType

    // Set pending status for each tracked upload
    if (uploads.contains('gdrive')) env.UPLOAD_GDRIVE_STATUS = 'pending'
    if (uploads.contains('local')) env.UPLOAD_LOCAL_STATUS = 'pending'
    if (uploads.contains('store')) env.UPLOAD_STORE_STATUS = 'pending'

    // Set store display name
    def storeNames = [Android: 'Google Play', iOS: 'TestFlight', Amazon: 'Amazon',
                      StandaloneWindows64: 'Steam', StandaloneLinux64: 'Steam', Switch: 'GDrive',
                      GameCoreXboxSeries: 'GDrive', GameCoreXboxOne: 'GDrive', PS5: 'GDrive']
    if (uploads.contains('store')) {
        env.UPLOAD_STORE_NAME = config.storeName ?: storeNames[platform] ?: 'Store'
    }

    // Build is done, uploads start: the status badge switches from "stage" to "uploading ..."
    refreshUploadStatusBadge()

    // The local share copy lands minutes before Google Drive / the store, so the channel message
    // waits for it: the first thing people see is a build they can grab, not a row of hourglasses.
    // updateUploadStatus('local', ...) posts it (done or failed) and later updates edit it in place;
    // handleBuildSuccess() still posts if the local stage never reported. Pass notifyAfterLocal:
    // false to post immediately as before; jobs without a local upload always post immediately.
    def deferUntilLocal = uploads.contains('local') && config.notifyAfterLocal != false
    if (deferUntilLocal) {
        // Keep the explicit config (not just the env fallbacks) for the deferred post
        env.UPLOAD_NOTIFY_CHANNEL = params.channel ?: ''
        env.UPLOAD_NOTIFY_BUILD_TYPE = params.buildType ?: ''
        env.UPLOAD_NOTIFY_BRANCH = params.branch ?: ''
        env.UPLOAD_NOTIFY_CHANGESET = params.changesetId ?: ''
        env.UPLOAD_NOTIFY_VERSION = params.version ?: ''
        env.UPLOAD_NOTIFY_APP_ICON = params.appIcon ?: ''
        env.UPLOAD_NOTIFY_PLATFORM = params.platform ?: ''
        env.UPLOAD_NOTIFY_AFTER_LOCAL = 'true'
        echo "[INFO] Slack upload notification deferred until the local share copy is done"
        return
    }

    _postUploadNotification(params)
}

/** Post the channel upload message and remember its coordinates so later status changes edit it. */
private void _postUploadNotification(Map params) {
    def slackResponse = sendSlackBuildNotification(params)
    if (slackResponse) {
        env.UPLOAD_SLACK_TS = slackResponse.ts
        env.UPLOAD_SLACK_CHANNEL = slackResponse.channelId
    }
}

/** The config sendUploadNotification() was given, for a post deferred until the local copy is done. */
private Map _deferredUploadConfig() {
    def cfg = [:]
    if (env.UPLOAD_NOTIFY_CHANNEL) cfg.channel = env.UPLOAD_NOTIFY_CHANNEL
    if (env.UPLOAD_NOTIFY_BUILD_TYPE) cfg.buildType = env.UPLOAD_NOTIFY_BUILD_TYPE
    if (env.UPLOAD_NOTIFY_BRANCH) cfg.branch = env.UPLOAD_NOTIFY_BRANCH
    if (env.UPLOAD_NOTIFY_CHANGESET) cfg.changesetId = env.UPLOAD_NOTIFY_CHANGESET
    if (env.UPLOAD_NOTIFY_VERSION) cfg.version = env.UPLOAD_NOTIFY_VERSION
    if (env.UPLOAD_NOTIFY_APP_ICON) cfg.appIcon = env.UPLOAD_NOTIFY_APP_ICON
    if (env.UPLOAD_NOTIFY_PLATFORM) cfg.platform = env.UPLOAD_NOTIFY_PLATFORM
    return cfg
}

/**
 * Update a specific upload stage's status and refresh the Slack notification.
 * Called by upload functions on completion.
 *
 * @param stage One of: 'gdrive', 'local', 'store'
 * @param result One of: 'done', 'failed'
 */
def updateUploadStatus(String stage, String result) {
    if (stage == 'gdrive') env.UPLOAD_GDRIVE_STATUS = result
    else if (stage == 'local') env.UPLOAD_LOCAL_STATUS = result
    else if (stage == 'store') env.UPLOAD_STORE_STATUS = result

    try { refreshUploadStatusBadge() } catch (Exception e) { echo "[WARN] Failed to refresh upload status badge: ${e.message}" }

    // Deferred channel message: the local copy just landed (or failed) - post it now, with every
    // target's current state (a Drive upload that finished first already shows as done).
    if (env.UPLOAD_NOTIFY_AFTER_LOCAL == 'true' && stage == 'local') {
        env.UPLOAD_NOTIFY_AFTER_LOCAL = ''
        try {
            echo "[INFO] Local share ${result} - posting the Slack upload notification"
            _postUploadNotification(buildSlackParams(_deferredUploadConfig()))
        } catch (Exception e) {
            echo "[WARN] Failed to post upload notification: ${e.message}"
        }
        return
    }

    if (!env.UPLOAD_SLACK_TS) return

    try {
        sendSlackBuildNotification(buildSlackParams() + [timestamp: env.UPLOAD_SLACK_TS])
    } catch (Exception e) {
        echo "[WARN] Failed to update upload status: ${e.message}"
    }
}

// ============================================================================
// DASHBOARD WEBHOOK - Real-time build status to Release Dashboard
// ============================================================================

/**
 * Send a generic webhook notification for build status changes.
 * Called automatically from printBuildInfo() (STARTED) and build handlers.
 * Configure target URL via WEBHOOK_URL env var (default: http://localhost:3000/api/jenkins-webhook).
 * Silently fails if endpoint is unreachable — never affects the build.
 *
 * @param status  STARTED, SUCCESS, FAILURE, UNSTABLE, ABORTED
 * @param config  Map with buildType, branch, changesetId (same as handler config)
 */
def notifyDashboard(String status, Map config = [:]) {
    try {
        def webhookUrl = env.WEBHOOK_URL ?: 'http://localhost:3000/api/jenkins-webhook'

        def payload = [
            jobName: env.JOB_NAME ?: '',
            buildNumber: env.BUILD_NUMBER?.toInteger() ?: 0,
            status: status,
            platform: env.PLATFORM ?: '',
            buildType: config.buildType ?: env.BUILD_TYPE ?: '',
            branch: (config.branch ?: env.PLASTICSCM_BRANCH ?: '').replaceAll('^/', ''),
            version: env.VERSION ?: '',
            changeset: config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '',
            timestamp: System.currentTimeMillis(),
            buildUrl: env.BUILD_URL ?: '',
            node: env.BUILD_NODE ?: env.NODE_NAME ?: ''
        ]

        if (status == 'STARTED') {
            def prevDuration = currentBuild.previousSuccessfulBuild?.duration
            if (prevDuration) payload.estimatedDuration = prevDuration
        } else {
            payload.duration = currentBuild.duration
            if (env.GDRIVE_FILE_LINK) payload.downloadUrl = env.GDRIVE_FILE_LINK
            if (env.ERROR_ANALYSIS) payload.errorAnalysis = env.ERROR_ANALYSIS
            if (env.FAILED_STAGE) payload.failedStage = env.FAILED_STAGE
        }

        def json = new groovy.json.JsonBuilder(payload).toString()

        def url = new URL(webhookUrl)
        def conn = url.openConnection()
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setConnectTimeout(3000)
        conn.setReadTimeout(3000)
        conn.setDoOutput(true)
        conn.getOutputStream().write(json.getBytes('UTF-8'))

        def responseCode = conn.getResponseCode()
        echo "[Webhook] ${status} -> ${responseCode}"
        conn.disconnect()
    } catch (Exception e) {
        echo "[Webhook] Skipped: ${e.message}"
    }
}

// ============================================================================
// BUILD NOTIFICATION HANDLERS
// (platform-agnostic; platform-specific helpers called via platformModule)
// ============================================================================

def handleBuildFailure(Map config) {
    notifyDashboard('FAILURE', config)

    // Capture build user if not already set
    if (!env.BUILD_USER) captureBuildUser()

    def buildType = config.buildType
    def branch = config.branch ?: env.PLASTICSCM_BRANCH
    def changeset = config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-'

    env.FAILED_STAGE = env.CURRENT_STAGE ?: 'Unknown'
    env.FAILED_NODE_ID = getFailedNodeId(env.FAILED_STAGE)

    def summary = "<b>Failed in:</b> ${env.FAILED_STAGE}<br/>"
    summary += "<b>Branch:</b> ${branch} | <b>Changeset:</b> ${changeset}<br/>"
    manager.createSummary("error.png").appendText(summary, false)
}

/**
 * Save build metadata as a JSON artifact for API consumers (web dashboards, mobile apps).
 * Written to WORKSPACE/artifacts/build_info.json, archived automatically by the always block.
 */
def saveBuildMetadata(Map config) {
    def artifactDir = "${env.WORKSPACE}/artifacts"

    def metadata = [
        job: env.JOB_NAME ?: '',
        build: env.BUILD_NUMBER ?: '',
        result: currentBuild.result ?: currentBuild.currentResult ?: 'UNKNOWN',
        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        duration: currentBuild.duration,
        url: env.BUILD_URL ?: '',

        app: env.APP_NAME ?: '',
        icon: config?.appIcon ?: env.APP_ICON ?: '',
        bundleId: env.BUNDLE_IDENTIFIER ?: '',
        version: env.VERSION ?: '',
        buildType: config?.buildType ?: env.BUILD_TYPE ?: '',
        platform: env.PLATFORM ?: '',

        branch: env.PLASTICSCM_BRANCH ?: env.BRANCH ?: '',
        changeset: env.PLASTICSCM_CHANGESET_ID ?: env.CHANGESET_ID ?: '',
        author: env.PLASTICSCM_AUTHOR ?: '',

        downloadUrl: env.GDRIVE_FILE_LINK ?: '',
        fileSize: env.ARTIFACT_SIZE ?: '',
        driveFolderUrl: env.GDRIVE_FOLDER_LINK ?: ''
    ]

    def json = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(metadata))
    writeFile file: "${artifactDir}/build_info.json", text: json
    echo "[OK] Build metadata saved to artifacts/build_info.json"
}

/**
 * Run AI failure analysis and send Slack notification - called from 'always' block.
 * For failures: runs AI analysis, extracts error cause, sends comprehensive Slack notification.
 * For unstable: runs AI analysis and updates the existing Slack message from handleBuildUnstable.
 * Skips success and aborted builds.
 */
def finalizeBuild(Map config) {
    // Save build metadata JSON for API consumers (web dashboards, etc.)
    try { saveBuildMetadata(config) } catch (Exception e) { echo "[WARN] Failed to save build metadata: ${e.message}" }

    // Re-color all shields badges to reflect final build result
    try { updateBadgesForResult() } catch (Exception e) { echo "[WARN] Failed to update badges: ${e.message}" }

    def status = currentBuild.result?.toLowerCase() ?: 'failure'

    // Skip on success or abort - those have their own handlers
    if (status == 'success' || status == 'aborted') return

    // A failed build never reaches the 'Build Warnings' stage; still badge the warnings raised before it failed.
    if (status == 'failure') {
        try { linkBuildWarnings() } catch (Exception e) { echo "[WARN] Could not link build warnings: ${e.message}" }
    }

    // Mark any remaining pending uploads as failed
    if (env.UPLOAD_GDRIVE_STATUS == 'pending') env.UPLOAD_GDRIVE_STATUS = 'failed'
    if (env.UPLOAD_LOCAL_STATUS == 'pending') env.UPLOAD_LOCAL_STATUS = 'failed'
    if (env.UPLOAD_STORE_STATUS == 'pending') env.UPLOAD_STORE_STATUS = 'failed'

    // For unstable builds, only analyze if there were actual errors
    if (status == 'unstable' && !env.UNSTABLE_REASONS) return

    // Safe defaults - every field has a fallback so nothing NPEs
    def buildType = config?.buildType ?: env.BUILD_TYPE ?: 'Unknown'
    def branch = config?.branch ?: env.PLASTICSCM_BRANCH ?: '-'
    def changeset = config?.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-'
    def platform = config?.platform ?: env.PLATFORM ?: 'Unknown'
    def channel = config?.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def appIcon = config?.appIcon

    // Step 1: Collect and archive filtered console log (always, for dashboard analyzer)
    try {
        platformModule?.collectFilteredConsoleLog()
    } catch (Exception e) {
        echo "[WARN] Console log collection failed: ${e.message}"
    }

    // Add Build Analyzer sidebar link for failed/unstable builds
    try {
        def dashUrl = env.DASHBOARD_URL ?: null
        if (dashUrl) {
            def analyzerUrl = "${dashUrl}/#analyzer?job=${env.JOB_NAME}&build=${env.BUILD_NUMBER}"
            addSidebarLink(analyzerUrl, 'Build Analyzer', 'symbol-search-regular')
        }
    } catch (Exception e) {
        // Non-critical — ignore
    }

    // Step 2: Error analysis — known patterns first, then raw exception fallback
    def errorAnalysis = env.ERROR_ANALYSIS ?: null
    if (!errorAnalysis) {
        // Check known error patterns first (no API cost)
        try {
            def failMsg = currentBuild.rawBuild.getExecution()?.getCauseOfFailure()?.getMessage() ?: ''
            def matched = knownErrors?.match(failMsg)
            if (matched) {
                def failedStage = env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'
                def errors = [[severity: 'ERROR', stage: failedStage, message: matched.explanation]]
                if (matched.fix) errors[0].fix = matched.fix
                def analysis = [
                    explanation: matched.explanation,
                    errors: errors,
                    knownError: true
                ]
                errorAnalysis = new groovy.json.JsonBuilder(analysis).toString()
                echo "[INFO] Known error pattern detected"
            }
        } catch (Exception e) {
            // Ignore — will fall through
        }

    }

    // Step 2: Fallback - extract raw exception if AI didn't produce results
    if (!errorAnalysis && status == 'failure') {
        try {
            def execution = currentBuild.rawBuild.getExecution()
            def cause = execution?.getCauseOfFailure()
            if (cause) {
                def failedStage = env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'
                def fallback = [
                    explanation: "Build failed in ${failedStage}: ${cause.getMessage() ?: cause.getClass().getName()}",
                    errors: []
                ]
                errorAnalysis = new groovy.json.JsonBuilder(fallback).toString()
            }
        } catch (Exception e) {
            echo "[DEBUG] Could not extract failure cause: ${e.message}"
        }
    }

    // Step 3: Save analysis artifact (optional - failure here must not prevent Slack)
    try {
        if (errorAnalysis) {
            env.ERROR_ANALYSIS = errorAnalysis
            def parsedCheck = new groovy.json.JsonSlurper().parseText(errorAnalysis)
            def isKnown = parsedCheck.knownError ?: false
            def headerType = isKnown ? 'Known Error' : (status == 'unstable' ? 'Build Unstable' : 'Build Failure')
            def failedInfo = status == 'unstable'
                ? "Unstable Reasons: ${env.UNSTABLE_REASONS}"
                : "Failed Stage: ${env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'}"
            def header = "${headerType}\n${'=' * 50}\n\n${failedInfo}\nBranch: ${branch}\nChangeset: ${changeset}\n\n${'=' * 50}\n"
            def parsedAnalysis = saveAnalysisFile(errorAnalysis, header)
            if (parsedAnalysis) {
                def isKnownError = parsedAnalysis.knownError ?: false
                def summaryLabel = isKnownError ? "Known Error" : "Error Analysis"
                def aiSummary = "<br/><b>${summaryLabel}:</b> ${parsedAnalysis.explanation}<br/>"
                if (parsedAnalysis.errors) {
                    aiSummary += "<br/><b>Detected Errors:</b><pre>"
                    parsedAnalysis.errors.each { err ->
                        def stagePrefix = err.stage ? "(${err.stage}) " : ""
                        aiSummary += "\n[${err.severity}] ${stagePrefix}${err.message}"
                        if (err.fix) aiSummary += "\n  FIX: ${err.fix}"
                    }
                    aiSummary += "</pre>"
                }
                manager.createSummary("symbol-star.png").appendText(aiSummary, false)

                // Set build description so AI analysis appears in the Jenkins build list hover tooltip
                def desc = currentBuild.description ?: ''
                def explanation = parsedAnalysis.explanation?.take(200) ?: ''
                if (explanation) {
                    currentBuild.description = desc ? "${desc}\n${explanation}" : explanation
                }
            }
        }
    } catch (Exception e) {
        echo "[WARNING] Could not save analysis artifact: ${e.message}"
    }

    // Step 4: Send Slack notification - THE critical step, must always execute
    try {
        if (status == 'failure') {
            def existingTs = env.UPLOAD_SLACK_TS
            sendSlackBuildNotification(
                channel: existingTs ? (env.UPLOAD_SLACK_CHANNEL ?: channel) : channel,
                buildType: buildType,
                status: 'failure',
                branch: branch,
                changesetId: changeset,
                errorAnalysis: errorAnalysis,
                buildUser: env.BUILD_USER,
                buildUserEmail: env.BUILD_USER_EMAIL,
                appIcon: appIcon,
                platform: platform,
                timestamp: existingTs
            )
        } else if (status == 'unstable' && errorAnalysis) {
            def ts = env.FAILURE_SLACK_TS ?: env.UPLOAD_SLACK_TS
            def slackChannel = env.FAILURE_SLACK_CHANNEL ?: env.UPLOAD_SLACK_CHANNEL ?: channel
            if (ts) {
                sendSlackBuildNotification(
                    channel: slackChannel,
                    buildType: buildType,
                    status: status,
                    branch: branch,
                    changesetId: changeset,
                    errorAnalysis: errorAnalysis,
                    buildUser: env.BUILD_USER,
                    buildUserEmail: env.BUILD_USER_EMAIL,
                    appIcon: appIcon,
                    platform: platform,
                    timestamp: ts
                )
            }
        }
    } catch (Exception e) {
        echo "[ERROR] Slack notification failed: ${e.message}"
    }
}

def handleBuildSuccess(Map config) {
    echo "[INFO] handleBuildSuccess called"
    notifyDashboard('SUCCESS', config)

    // Save build info for cache integrity checks on next build
    platformModule?.saveBuildInfo()

    // Clear any remaining pending upload statuses (they completed if we got here)
    if (env.UPLOAD_GDRIVE_STATUS == 'pending') env.UPLOAD_GDRIVE_STATUS = 'done'
    if (env.UPLOAD_LOCAL_STATUS == 'pending') env.UPLOAD_LOCAL_STATUS = 'done'
    if (env.UPLOAD_STORE_STATUS == 'pending') env.UPLOAD_STORE_STATUS = 'done'

    // Update existing upload notification or send new one
    def params = buildSlackParams(config)
    def existingTs = env.UPLOAD_SLACK_TS
    echo "[INFO] Sending success notification to ${existingTs ? 'existing message' : params.channel}"
    sendSlackBuildNotification(params + [timestamp: existingTs])
}

def handleBuildUnstable(Map config) {
    echo "[INFO] handleBuildUnstable called"
    notifyDashboard('UNSTABLE', config)

    // Send Slack notification immediately (without AI analysis)
    def slackResponse = sendSlackBuildNotification(buildSlackParams(config) + [status: 'unstable'])

    // Store Slack message coordinates so finalizeBuild() can update it
    if (slackResponse) {
        env.FAILURE_SLACK_TS = slackResponse.ts
        env.FAILURE_SLACK_CHANNEL = slackResponse.channelId
    }
}

def handleBuildAborted(Map config) {
    notifyDashboard('ABORTED', config)

    def params = buildSlackParams(config)
    sendSlackBuildNotification(params + [status: 'aborted'])
}

return this
