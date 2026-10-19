// ============================================================================
// MACOS-SPECIFIC UTILITIES - Uses sh commands
// ============================================================================

// Note: Can't use @Field in evaluated scripts, the warning about memory leaks is acceptable
def common = null
def PREFLIGHT_VERSION = '0'  // Set automatically by buildUtils.init() from content hash

def init(commonUtils) {
    common = commonUtils
    return this
}

// Ensures common utilities are available, reloading if necessary (e.g., after Jenkins restart)
def ensureCommon() {
    if (common != null) return true

    echo "[WARN] common utilities not available (likely Jenkins restarted mid-build), attempting reload..."
    try {
        common = evaluate(libraryResource('groovy/common.groovy'))
        echo "[INFO] Successfully reloaded common utilities"
        return true
    } catch (Exception e) {
        echo "[ERROR] Failed to reload common utilities: ${e.message}"
        return false
    }
}

// ============================================================================
// TOOL PATHS AND CONSTANTS
// ============================================================================

// Persistent tools directory (survives workspace cleanup)
def getToolsDir() {
    if (env._BUILD_TOOLS_DIR) return env._BUILD_TOOLS_DIR
    // Use env.HOME (agent's env) not System.getenv (controller's env)
    def home = env.HOME ?: sh(script: 'echo $HOME', returnStdout: true).trim()
    def toolsDir = "${home}/.buildtools"
    sh(script: "mkdir -p \"${toolsDir}\"", returnStatus: true)
    env._BUILD_TOOLS_DIR = toolsDir
    return toolsDir
}

/**
 * Check if a periodic update should run for the given tool.
 * Returns true if the marker file is missing or older than 14 days.
 */
def shouldRunPeriodicUpdate(String toolName) {
    def toolsDir = getToolsDir()
    def markerFile = "${toolsDir}/.update_checks/${toolName}"
    sh(script: "mkdir -p '${toolsDir}/.update_checks'", returnStatus: true)
    def exists = sh(script: "[ -f '${markerFile}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists != 'found') return true
    def daysOld = sh(script: "echo \$(( ( \$(date +%s) - \$(stat -f%m '${markerFile}') ) / 86400 ))", returnStdout: true).trim()
    return !daysOld.isInteger() || daysOld.toInteger() >= 14
}

/**
 * Touch the update marker file to reset the 14-day timer.
 */
def markUpdateChecked(String toolName) {
    def toolsDir = getToolsDir()
    sh(script: "mkdir -p '${toolsDir}/.update_checks' && touch '${toolsDir}/.update_checks/${toolName}'", returnStatus: true)
}

/**
 * Check if preflight checks can be skipped (passed within last 24 hours).
 * @return true if preflights should be skipped, false if they need to run
 */
def shouldSkipPreflight() {
    def toolsDir = getToolsDir()
    def markerFile = "${toolsDir}/.preflight_ok"
    def result = sh(script: """
if [ ! -f '${markerFile}' ]; then echo NOT_FOUND; exit 0; fi
stored=\$(cat '${markerFile}')
if [ "\$stored" != "${PREFLIGHT_VERSION}" ]; then echo VERSION_MISMATCH; exit 0; fi
echo \$(( ( \$(date +%s) - \$(stat -f%m '${markerFile}') ) / 3600 ))
""", returnStdout: true).trim()
    if (result == 'NOT_FOUND' || result == 'VERSION_MISMATCH') return false
    if (!result.isInteger()) return false
    def skip = result.toInteger() < 24
    if (skip) {
        echo "[Preflight] Skipping — passed ${result}h ago (v${PREFLIGHT_VERSION}, < 24h)"
    }
    return skip
}

/**
 * Mark preflights as passed. Writes PREFLIGHT_VERSION so changes invalidate the cache.
 */
def markPreflightPassed() {
    def toolsDir = getToolsDir()
    sh(script: "echo '${PREFLIGHT_VERSION}' > '${toolsDir}/.preflight_ok'", returnStatus: true)
    echo "[Preflight] Marked as passed (v${PREFLIGHT_VERSION})"
}

/**
 * Logs the contents of a build output directory for debugging.
 * Shows all files recursively to help diagnose missing or unexpected outputs.
 *
 * @param buildPath Path to the directory to list
 */
def logBuildOutputs(String buildPath) {
    echo "[INFO] Listing build outputs: ${buildPath}"
    sh """
        if [ ! -d "${buildPath}" ]; then
            echo "[WARN] Build output directory does not exist: ${buildPath}"
            exit 0
        fi
        echo "======== Build Outputs ========"
        find "${buildPath}" -type f | sort
        echo "================================"

        # Copy .log files to artifact path for archiving
        if [ -n "${env.ARTIFACT_PATH}" ]; then
            find "${buildPath}" -name "*.log" -type f | while IFS= read -r logfile; do
                mkdir -p "${env.ARTIFACT_PATH}/build_logs"
                cp "\$logfile" "${env.ARTIFACT_PATH}/build_logs/" && \\
                    echo "[OK] Archived log: \$(basename \$logfile)"
            done
        fi
    """
}

// ============================================================================
// TEXTURE CAP
// ============================================================================

/**
 * Check for Python 3. Tries python3, then python. Caches result in env.PYTHON_EXE.
 * @param autoInstall If true, attempt to install via Homebrew if not found.
 */
def checkPython(boolean autoInstall = false) {
    if (env.PYTHON_EXE) return [available: true, message: "Python 3 (${env.PYTHON_EXE})"]

    // Single sh call: try python3 first, then python
    def output = sh(script: '''
        echo "Checking Python installation..." >&2
        VER=$(python3 --version 2>&1)
        if echo "$VER" | grep -q "Python 3"; then echo "  Found: $VER (python3)" >&2; echo "FOUND_PYTHON3"; echo "$VER"; exit 0; fi
        VER=$(python --version 2>&1)
        if echo "$VER" | grep -q "Python 3"; then echo "  Found: $VER (python)" >&2; echo "FOUND_PYTHON"; echo "$VER"; exit 0; fi
        echo "  Python 3 not found" >&2
        echo "NOT_FOUND"
    ''', returnStdout: true).trim()

    echo "  checkPython stdout: ${output}"
    def lines = output.readLines()
    def status = lines[0]
    def version = lines.size() > 1 ? lines[1] : ''

    if (status == 'FOUND_PYTHON3') {
        env.PYTHON_EXE = 'python3'
        return [available: true, message: version]
    }
    if (status == 'FOUND_PYTHON') {
        env.PYTHON_EXE = 'python'
        return [available: true, message: version]
    }

    if (autoInstall) {
        try {
            echo "[INFO] Installing Python 3 via Homebrew..."
            sh 'brew install python3'
            def installedVersion = sh(script: 'python3 --version 2>&1', returnStdout: true).trim()
            if (installedVersion.contains('Python 3')) {
                env.PYTHON_EXE = 'python3'
                echo "[OK] Python 3 installed: ${installedVersion}"
                return [available: true, message: installedVersion]
            }
        } catch (Exception e) {
            echo "[WARN] brew install python3 failed: ${e.message}"
        }
    }

    return [available: false, message: 'Python 3 not installed',
            installInstructions: 'brew install python3']
}

def preflightPython() {
    def result = checkPython(true)
    if (result.available) {
        echo "[OK] Python: ${result.message}"
    } else {
        error "[ERROR] Python 3 not found\nFix: ${result.installInstructions}"
    }
}

def preflightRuby() {
    def rubyCheck = checkRuby(true)  // auto-install if missing
    if (!rubyCheck.available) {
        error "[ERROR] Ruby not available: ${rubyCheck.message}\n${rubyCheck.installInstructions ?: ''}"
    }
    echo "[OK] Ruby available: ${rubyCheck.version ?: 'found'}"
}

/**
 * Cap maxTextureSize in Unity .meta files to MAX_TEXTURE_SIZE env var value.
 * Only runs if MAX_TEXTURE_SIZE is set to a valid integer >= 256.
 * Call after Checkout, before Prepare. Restore with restoreTextures() in post.always.
 */
def capTextures(Map config = [:]) {
    def maxSizeStr = (config.maxSize ?: env.MAX_TEXTURE_SIZE)?.toString()?.trim()
    if (!maxSizeStr) {
        echo "[INFO] MAX_TEXTURE_SIZE not set - skipping texture cap"
        return
    }
    def maxSize = maxSizeStr.isInteger() ? maxSizeStr.toInteger() : -1
    if (maxSize < 256) {
        echo "[INFO] MAX_TEXTURE_SIZE=${maxSizeStr} is not a valid integer >= 256 - skipping"
        return
    }

    def result = checkPython(true)
    if (!result.available) {
        error "[ERROR] Python 3 not found. ${result.installInstructions}"
    }

    def assetsPath = config.assetsPath ?: "${env.UNITY_PROJECT}/Assets"
    def outputFile = "${env.WORKSPACE}/texture_cap_modified.txt"
    def scriptPath = "${env.WORKSPACE}/texture_cap.py"
    def dryRunFlag = config.dryRun ? '--dry-run' : ''

    writeFile file: scriptPath, text: libraryResource('scripts/texture_cap.py')

    sh """
        ${env.PYTHON_EXE} "${scriptPath}" --assets "${assetsPath}" --max-size ${maxSize} --output "${outputFile}" ${dryRunFlag}
    """
    env.TEXTURE_CAP_MODIFIED_LIST = outputFile
    echo "[OK] Texture cap complete (max ${maxSize}px)"
}

/**
 * Restore .meta files patched by capTextures() via Plastic SCM cm undo.
 * Safe to call even if capTextures() was skipped - no-ops if no list exists.
 */
def restoreTextures(Map config = [:]) {
    def inputFile = config.inputFile ?: env.TEXTURE_CAP_MODIFIED_LIST ?: "${env.WORKSPACE}/texture_cap_modified.txt"
    def exists = sh(script: "[ -f '${inputFile}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists != 'found') {
        echo "[INFO] No texture cap list found - nothing to restore"
        return
    }

    def result = checkPython(false)
    if (!result.available) {
        echo "[WARN] Python 3 not found - cannot run texture restore script. Run `cm undo . -r` manually if needed."
        return
    }

    def scriptPath = "${env.WORKSPACE}/texture_restore.py"
    writeFile file: scriptPath, text: libraryResource('scripts/texture_restore.py')

    def status = sh(script: """${env.PYTHON_EXE} "${scriptPath}" --input "${inputFile}" """, returnStatus: true)
    if (status != 0) {
        echo "[WARN] Texture restore had errors - run `cm undo . -r` manually if needed"
    }
}

// ============================================================================
// PREREQUISITE DETECTION AND INSTALLATION
// ============================================================================

/**
 * Check all prerequisites for the given platform and attempt to install missing ones
 * @param platform Target platform: 'iOS', 'Android', 'StandaloneOSX'
 * @param autoInstall If true, attempt to install missing prerequisites
 * @return Map of check results
 */
/**
 * Get required Unity modules for a platform.
 * Only list top-level modules — the -cm (child modules) flag on install/install-modules
 * automatically pulls sub-dependencies (e.g. 'android' with -cm pulls SDK, NDK, and JDK).
 */
def getRequiredUnityModules(String platform) {
    switch (platform) {
        case 'iOS':
            return ['ios']
        case 'Android':
        case 'Amazon':
            return ['android']  // -cm pulls android-sdk-ndk-tools + android-open-jdk automatically
        case 'StandaloneOSX':
            return ['mac-il2cpp']
        default:
            return []
    }
}

/**
 * Resolve the install directory of a Unity version.
 *
 * Hub does not always use the plain /Applications/Unity/Hub/Editor/<version> folder: when that
 * folder is already taken it installs beside it with an architecture suffix
 * (<version>-arm64 / <version>-x86_64). That is exactly what happens when installUnityModules()
 * falls back to a full `install` because Hub lost track of the copy at the plain path - the new
 * copy (the one with the modules) lands at <version>-arm64 while every hardcoded path still points
 * at the old one, so the modules "stay missing" and the build fails. When several copies are on
 * disk the one Hub lists in `editors -i` wins, because that is the copy install-modules writes to.
 *
 * Cached in env.UNITY_INSTALL_DIR for the version - pass refresh=true after an install.
 * Returns the plain path when nothing is installed; callers check for the binary themselves.
 */
def getUnityInstallDir(String version, boolean refresh = false) {
    if (!refresh && env.UNITY_INSTALL_DIR && env.UNITY_INSTALL_DIR_VERSION == version) {
        return env.UNITY_INSTALL_DIR
    }
    def root = '/Applications/Unity/Hub/Editor'
    def plain = "${root}/${version}".toString()
    // Every <version> / <version>-<suffix> folder that actually holds an editor binary
    def listing = sh(script: "ls -d '${plain}'*/Unity.app/Contents/MacOS/Unity 2>/dev/null || true", returnStdout: true).trim()
    def onDisk = _editorDirsForVersion(listing, version)

    def chosen = plain
    if (onDisk.size() == 1) {
        chosen = onDisk[0]
    } else if (onDisk.size() > 1) {
        echo "[WARN] Unity ${version} is installed more than once: ${onDisk.join(', ')} - asking Hub which copy it tracks"
        def hubDir = _hubTrackedEditorDir(version)
        if (hubDir && onDisk.contains(hubDir)) {
            chosen = hubDir
        } else {
            chosen = onDisk[0]
            echo "[WARN] Hub lists none of them (reported: ${hubDir ?: 'nothing'}) - using ${chosen}"
        }
    }
    if (chosen != plain) {
        echo "[INFO] Unity ${version} resolved to ${chosen}"
    }
    env.UNITY_INSTALL_DIR = chosen
    env.UNITY_INSTALL_DIR_VERSION = version
    return chosen
}

/** Editor dirs from the `ls -d` listing of <root>/<version>{,-<suffix>}/Unity.app/Contents/MacOS/Unity - exact version or version-<suffix> only. */
@com.cloudbees.groovy.cps.NonCPS
private List _editorDirsForVersion(String listing, String version) {
    def dirs = []
    if (!listing) return dirs
    for (String line : listing.split('\n')) {
        def path = line.trim()
        def idx = path.indexOf('/Unity.app/Contents/MacOS/Unity')
        if (idx < 0) continue
        def dir = path.substring(0, idx)
        def name = dir.substring(dir.lastIndexOf('/') + 1)
        if (name == version || name.startsWith("${version}-")) dirs << dir
    }
    return dirs
}

/**
 * Directory Hub reports for a version in `editors -i`, or null when Hub doesn't list it.
 * Prefers the entry for this machine's architecture when both Intel and Apple silicon copies exist.
 */
private def _hubTrackedEditorDir(String version) {
    def hub = env.UNITY_HUB_PATH ?: '/Applications/Unity Hub.app/Contents/MacOS/Unity Hub'
    def hubExists = sh(script: "[ -f '${hub}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (hubExists != 'found') return null
    def arch = sh(script: 'uname -m', returnStdout: true).trim()
    def output = sh(script: "\"${hub}\" -- --headless editors -i 2>&1 || true", returnStdout: true).trim()
    return _parseHubEditorDir(output, version, arch)
}

/**
 * Parse `editors -i` output, e.g.
 *   6000.0.83f1 (Apple silicon) installed at /Applications/Unity/Hub/Editor/6000.0.83f1-arm64/Unity.app
 * into the editor's install dir. @NonCPS: plain string ops only, no Matcher in CPS scope.
 */
@com.cloudbees.groovy.cps.NonCPS
private String _parseHubEditorDir(String output, String version, String arch) {
    if (!output) return null
    def archLabel = arch == 'arm64' ? 'Apple silicon' : 'Intel'
    def marker = ' installed at '
    def matches = []
    for (String line : output.split('\n')) {
        def l = line.trim()
        if (!l.startsWith("${version} ")) continue
        def idx = l.indexOf(marker)
        if (idx < 0) continue
        def path = l.substring(idx + marker.length()).trim()
        if (path.endsWith('/Unity.app')) path = path.substring(0, path.length() - '/Unity.app'.length())
        matches << [line: l, dir: path]
    }
    if (!matches) return null
    def preferred = matches.find { it.line.contains(archLabel) }
    return (preferred ?: matches[0]).dir
}

/**
 * Get the PlaybackEngines base path for a Unity version.
 * Unity 6+ (6000.x) uses Editor/Data/PlaybackEngines/, older versions use PlaybackEngines/ directly.
 */
def getPlaybackEnginesPath(String version) {
    def basePath = getUnityInstallDir(version)
    // Unity 6+ layout: Editor/Data/PlaybackEngines
    def newPath = "${basePath}/Editor/Data/PlaybackEngines"
    def exists = sh(script: "[ -d '${newPath}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists == 'found') {
        echo "[INFO] PlaybackEngines path (Unity 6+ layout): ${newPath}"
        return newPath
    }
    // Standard macOS layout
    def legacyPath = "${basePath}/PlaybackEngines"
    echo "[INFO] PlaybackEngines path (legacy layout): ${legacyPath}"
    return legacyPath
}

/**
 * Check Git installation (usually comes with Xcode CLI tools)
 */
def checkGit(boolean autoInstall = false) {
    try {
        def version = sh(script: 'git --version', returnStdout: true).trim()
        if (version.contains('git version')) {
            return [available: true, message: version]
        }
    } catch (Exception e) {
        // Git not available
    }

    if (autoInstall) {
        return installGit()
    }

    return [
        available: false,
        message: 'Git not installed',
        installInstructions: 'Install Xcode Command Line Tools: xcode-select --install, or: brew install git'
    ]
}

/**
 * Install Git via Homebrew or Xcode CLI tools
 */
def installGit() {
    try {
        echo "[INFO] Installing Git..."
        sh '''
            if command -v brew &>/dev/null; then
                brew install git
            else
                # Trigger Xcode CLI tools installation (includes git)
                xcode-select --install || true
                echo "[WARN] Git installation requires Xcode Command Line Tools - may need user interaction"
            fi
        '''

        // Check if now available
        try {
            def version = sh(script: 'git --version', returnStdout: true).trim()
            if (version.contains('git version')) {
                return [available: true, installed: true, message: 'Git installed successfully']
            }
        } catch (Exception e) {
            // Not yet available
        }

        return [available: false, installed: true, message: 'Git installation initiated - may require Xcode CLI tools']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Run: xcode-select --install'
        ]
    }
}

/**
 * Check if Homebrew is installed
 */
def checkHomebrew(boolean autoInstall = false) {
    try {
        def version = sh(script: 'brew --version | head -1', returnStdout: true).trim()
        if (version.contains('Homebrew')) {
            return [available: true, message: version]
        }
    } catch (Exception e) {
        // Homebrew not installed
    }

    if (autoInstall) {
        return installHomebrew()
    }

    return [
        available: false,
        message: 'Homebrew not installed',
        installInstructions: '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
    ]
}

/**
 * Install Homebrew
 */
def installHomebrew() {
    try {
        echo "[INFO] Installing Homebrew..."
        sh '''
            /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        '''
        return [available: true, installed: true, message: 'Homebrew installed successfully']
    } catch (Exception e) {
        return [available: false, installed: false, message: "Installation failed: ${e.message}"]
    }
}

/**
 * Check if Unity Hub is installed
 */
def checkUnityHub(boolean autoInstall = false) {
    def hubPath = '/Applications/Unity Hub.app/Contents/MacOS/Unity Hub'
    def exists = sh(script: "[ -f '${hubPath}' ] && echo found || echo notfound", returnStdout: true).trim()

    if (exists == 'found') {
        env.UNITY_HUB_PATH = hubPath

        // Periodic update check (every 14 days)
        try {
            if (shouldRunPeriodicUpdate('unity_hub')) {
                echo "[INFO] Checking for Unity Hub updates..."
                sh(script: 'brew upgrade --cask unity-hub || true', returnStatus: true)
                markUpdateChecked('unity_hub')
            }
        } catch (Exception e) {
            echo "[WARN] Unity Hub update check failed: ${e.message}"
        }

        return [available: true, message: "Found at ${hubPath}"]
    }

    if (autoInstall) {
        return installUnityHub()
    }

    return [
        available: false,
        message: 'Unity Hub not found',
        installInstructions: 'Download from https://unity.com/download or: brew install --cask unity-hub'
    ]
}

/**
 * Install Unity Hub via Homebrew
 */
def installUnityHub() {
    try {
        echo "[INFO] Installing Unity Hub via Homebrew..."
        sh 'brew install --cask unity-hub'
        env.UNITY_HUB_PATH = '/Applications/Unity Hub.app/Contents/MacOS/Unity Hub'
        markUpdateChecked('unity_hub')
        return [available: true, installed: true, message: 'Unity Hub installed successfully']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Download from https://unity.com/download'
        ]
    }
}

/**
 * Check if a specific Unity version is installed
 */
/**
 * Check if a specific Unity version is installed, optionally install it.
 * When auto-installing and modules are provided, installs editor + modules in one Hub command.
 */
def checkUnity(String version, boolean autoInstall = false, List modules = []) {
    def unityPath = "${getUnityInstallDir(version, true)}/Unity.app/Contents/MacOS/Unity"
    echo "[INFO] Checking for Unity at: ${unityPath}"
    def exists = sh(script: "[ -f '${unityPath}' ] && echo found || echo notfound", returnStdout: true).trim()

    if (exists == 'found') {
        return [available: true, message: "Unity ${version} found at ${unityPath}"]
    }

    // Log installed editors for diagnostic context
    logInstalledEditors()

    if (!autoInstall) {
        return [
            available: false,
            message: "Unity ${version} not installed at ${unityPath}",
            installInstructions: "Install via Unity Hub or run: buildUtils.installUnity('${version}')"
        ]
    }

    // Attempt installation via Unity Hub (with modules if provided)
    echo "[INFO] Unity ${version} not found, attempting auto-install..."
    return installUnity(version, modules)
}

/**
 * Log currently installed Unity editors via Hub CLI (diagnostic helper).
 */
def logInstalledEditors() {
    if (!env.UNITY_HUB_PATH) return
    try {
        def output = sh(script: """
            "${env.UNITY_HUB_PATH}" -- --headless editors -i 2>&1 || true
        """, returnStdout: true).trim()
        echo "[INFO] Installed Unity editors (via Hub CLI):\n${output ?: '(none found)'}"
    } catch (Exception e) {
        echo "[WARN] Could not query installed editors: ${e.message}"
    }
}

/**
 * Install Unity version (and optionally modules) via Unity Hub CLI.
 * When modules are provided, they're included in the install command via -m flags
 * so Hub installs everything in one pass — avoids the "editor not tracked" problem
 * that occurs when install-modules is called separately.
 */
def installUnity(String version, List modules = []) {
    if (!env.UNITY_HUB_PATH) {
        def hubCheck = checkUnityHub(false)
        if (!hubCheck.available) {
            return [available: false, installed: false, message: 'Unity Hub required for installation']
        }
    }

    // Use changeset if available (needed for non-release-list versions)
    def changesetArg = ''
    if (env.UNITY_CHANGESET) {
        changesetArg = "-c ${env.UNITY_CHANGESET}"
        echo "[INFO] Using changeset: ${env.UNITY_CHANGESET}"
    }

    // Detect CPU architecture to avoid interactive prompt on Apple Silicon Macs
    // uname -m returns 'arm64' (Apple Silicon) or 'x86_64' (Intel)
    def arch = sh(script: 'uname -m', returnStdout: true).trim()
    echo "[INFO] Detected architecture: ${arch}"

    // If the install directory exists but Unity binary is missing, it's a partial/corrupted install.
    // Unity Hub will refuse to install with "Editor already installed in this location" — remove it first.
    def installDir = "/Applications/Unity/Hub/Editor/${version}"
    def unityBin = "${installDir}/Unity.app/Contents/MacOS/Unity"
    def dirExists = sh(script: "[ -d '${installDir}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (dirExists == 'found') {
        def binExists = sh(script: "[ -f '${unityBin}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (binExists != 'found') {
            echo "[WARN] Removing partial Unity installation at ${installDir} (Unity binary missing)"
            sh script: "rm -rf '${installDir}'"
        }
    }

    try {
        def unityPath = "${installDir}/Unity.app/Contents/MacOS/Unity"
        def moduleArgs = modules ? modules.collect { "-m ${it}" }.join(' ') + ' -cm' : ''
        def cmd = "\"${env.UNITY_HUB_PATH}\" -- --headless install --version ${version} ${changesetArg} -a ${arch} ${moduleArgs}"

        if (modules) {
            echo "[INFO] Installing Unity ${version} with modules: ${modules.join(', ')}"
        }

        // Retry install for up to 2 hours — Unity Hub downloads can fail on transient network issues
        def maxAttempts = 4
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            echo "[INFO] Installing Unity ${version} via Unity Hub CLI (attempt ${attempt}/${maxAttempts})..."
            echo "[INFO] Running: ${cmd}"

            // Kill any existing Unity Hub processes to avoid conflicts
            sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"

            // Run Hub install with Jenkins timeout guard.
            // Unity Hub CLI hangs at "validating installation..." after install finishes — it never exits.
            // The timeout() step will interrupt it, then we verify the binary appeared on disk.
            def exitCode = 0
            try {
                timeout(time: 30, unit: 'MINUTES') {
                    exitCode = sh(script: "${cmd} 2>&1", returnStatus: true)
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                echo "[WARN] Unity Hub install timed out — checking if Unity binary is present anyway..."
            }
            if (exitCode != 0) {
                echo "[WARN] Unity Hub install exited with code ${exitCode}"
            }

            // Kill Hub process (may still be running after timeout)
            sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"

            // Verify the binary exists on disk — this is the source of truth,
            // not the Hub's exit code or output. Re-resolve the dir: Hub may have installed
            // to <version>-arm64 rather than the plain folder.
            unityPath = "${getUnityInstallDir(version, true)}/Unity.app/Contents/MacOS/Unity"
            def exists = sh(script: "[ -f '${unityPath}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (exists == 'found') {
                echo "[OK] Unity ${version} installed at ${unityPath}"
                return [available: true, installed: true, message: "Unity ${version} installed successfully"]
            }

            echo "[ERROR] Unity not found at: ${unityPath}"
            if (attempt < maxAttempts) {
                echo "[INFO] Retrying in 60 seconds..."
                sleep(60)
            }
        }

        logInstalledEditors()
        return [available: false, installed: false, message: "Installation failed after ${maxAttempts} attempts — Unity not found at ${unityPath}"]
    } catch (Exception e) {
        echo "[ERROR] Unity install failed: ${e.message}"
        logInstalledEditors()
        return [available: false, installed: false, message: "Installation failed: ${e.message}"]
    }
}

/**
 * Check if required Unity modules are installed
 */
def checkUnityModules(String version, List modules, boolean autoInstall = false) {
    if (!modules) {
        return [available: true, message: 'No additional modules required']
    }

    def playbackEngines = getPlaybackEnginesPath(version)
    def missingModules = []
    def foundModules = []

    // Marker directories to check inside PlaybackEngines for each module.
    // Nintendo Switch is NOT included here - it's validated separately by validateNintendoSwitchSupport()
    // because the addon installs differently and can't be auto-installed.
    def moduleMarkers = [
        'ios': 'iOSSupport/Trampoline',
        'android': 'AndroidPlayer',
        'android-sdk-ndk-tools': 'AndroidPlayer/SDK',
        'android-open-jdk': 'AndroidPlayer/OpenJDK',
        'mac-il2cpp': 'MacStandaloneSupport/Variations/macosx64_player_nondevelopment_il2cpp'
    ]

    echo "[INFO] Checking Unity modules in: ${playbackEngines}"

    modules.each { module ->
        def marker = moduleMarkers[module]
        if (marker) {
            def checkPath = "${playbackEngines}/${marker}"
            def exists = sh(script: "[ -d '${checkPath}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (exists == 'found') {
                echo "[OK] Module '${module}' found at: ${checkPath}"
                foundModules << module
            } else {
                echo "[MISSING] Module '${module}' not found at: ${checkPath}"
                missingModules << module
            }
        } else {
            // No marker defined - skip (e.g. nintendo-switch is validated separately)
            echo "[INFO] Module '${module}' has no marker check - skipping"
            foundModules << module
        }
    }

    if (!missingModules) {
        return [available: true, message: "All modules installed: ${foundModules.join(', ')}"]
    }

    echo "[INFO] Missing modules: ${missingModules.join(', ')}"

    if (!autoInstall) {
        return [
            available: false,
            message: "Missing modules: ${missingModules.join(', ')}",
            installInstructions: "Run: buildUtils.installUnityModules('${version}', ${missingModules})"
        ]
    }

    return installUnityModules(version, missingModules)
}

/**
 * True when Unity Hub says it can't act on an editor it should know about. All three messages
 * mean the same thing: the editor is not in this user's Hub registry (installed manually, by
 * another user, or outside the Hub's install path).
 */
private def _hubLostEditor(String output) {
    if (!output) return false
    return output.contains('only supported for editors installed with Unity Hub') ||
           output.contains('No modules found for this editor') ||
           output.contains('No editor found for version')
}

/**
 * Install Unity modules via Unity Hub CLI.
 * First tries install-modules (for Hub-tracked editors), then falls back to
 * install --version -m (which re-registers the editor and installs modules in one pass).
 */
def installUnityModules(String version, List modules) {
    if (!env.UNITY_HUB_PATH) {
        def hubCheck = checkUnityHub(false)
        if (!hubCheck.available) {
            return [available: false, installed: false, message: 'Unity Hub required for module installation']
        }
    }

    def moduleArgs = modules.collect { "-m ${it}" }.join(' ')

    // Try install-modules first (works when Hub tracks the editor)
    def cmd = "\"${env.UNITY_HUB_PATH}\" -- --headless install-modules --version ${version} ${moduleArgs} -cm"
    echo "[INFO] Running: ${cmd}"

    def exitCode = 0
    def output = ''
    try {
        timeout(time: 15, unit: 'MINUTES') {
            // '|| true' so returnStdout still yields the output when Hub exits non-zero -
            // we decide what happened from the message, not the exit code.
            output = sh(script: "${cmd} 2>&1 || true", returnStdout: true).trim()
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        echo "[WARN] Unity Hub install-modules timed out — checking if modules are present anyway..."
    }
    if (output) { echo output }

    // Hub can lose track of an editor that is present on disk (installed by another user, or
    // its install path points elsewhere). Repair the registry and retry before reinstalling -
    // never delete the editor, that triggers a full re-download (~5GB+).
    if (_hubLostEditor(output)) {
        echo "[WARN] Hub doesn't recognise Unity ${version} — diagnosing before reinstalling"
        sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"
        logInstalledEditors()

        def installRoot = '/Applications/Unity/Hub/Editor'
        def unityBin = "${getUnityInstallDir(version)}/Unity.app/Contents/MacOS/Unity"
        def onDisk = sh(script: "[ -f '${unityBin}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (onDisk == 'found') {
            echo "[INFO] Unity ${version} IS on disk — pointing Hub at ${installRoot} and retrying install-modules"
            sh script: "\"${env.UNITY_HUB_PATH}\" -- --headless install-path -s '${installRoot}' 2>&1 || true"
            try {
                timeout(time: 15, unit: 'MINUTES') {
                    output = sh(script: "${cmd} 2>&1 || true", returnStdout: true).trim()
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                echo "[WARN] Unity Hub install-modules retry timed out"
            }
            if (output) { echo output }
            sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"
        } else {
            echo "[WARN] Unity ${version} is not at ${installRoot} either — a full install is needed"
        }
    }

    // Still not tracked: install the editor with -m flags, which registers it and adds the modules.
    // Hub won't reuse the untracked copy at the plain folder - it downloads a fresh editor next to
    // it as <version>-arm64. getUnityInstallDir() picks that copy up afterwards (Hub tracks it, so
    // it is where the modules are); the old copy is left alone and can be deleted by hand.
    if (_hubLostEditor(output)) {
        echo "[WARN] Hub still doesn't track this editor — trying install command to re-register and add modules..."
        sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"

        def changesetArg = env.UNITY_CHANGESET ? "-c ${env.UNITY_CHANGESET}" : ''
        def arch = sh(script: 'uname -m', returnStdout: true).trim()
        def installCmd = "\"${env.UNITY_HUB_PATH}\" -- --headless install --version ${version} ${changesetArg} -a ${arch} ${moduleArgs} -cm"
        echo "[INFO] Running: ${installCmd}"
        try {
            timeout(time: 30, unit: 'MINUTES') {
                exitCode = sh(script: "${installCmd} 2>&1", returnStatus: true)
            }
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            echo "[WARN] Unity Hub install timed out — checking if modules are present anyway..."
        }
        sh script: "pkill -f 'Unity Hub' 2>/dev/null || true"
    } else if (output.contains('Error') || output.contains('Failed')) {
        echo "[WARN] Unity Hub install-modules reported an error"
    }

    // Verify modules are actually present by checking marker directories.
    // This is the source of truth - Unity Hub may return errors even when modules are already installed
    // (e.g. "Validation Failed" when another build has a file lock on the module directory).
    // Re-resolve the editor dir first: the install fallback above may have created <version>-arm64.
    getUnityInstallDir(version, true)
    def playbackEngines = getPlaybackEnginesPath(version)
    def moduleMarkers = [
        'ios': 'iOSSupport/Trampoline',
        'android': 'AndroidPlayer',
        'android-sdk-ndk-tools': 'AndroidPlayer/SDK',
        'android-open-jdk': 'AndroidPlayer/OpenJDK',
        'mac-il2cpp': 'MacStandaloneSupport/Variations/macosx64_player_nondevelopment_il2cpp'
    ]

    def stillMissing = modules.findAll { module ->
        def marker = moduleMarkers[module]
        if (!marker) return false  // No marker to check
        def checkPath = "${playbackEngines}/${marker}"
        def exists = sh(script: "[ -d '${checkPath}' ] && echo found || echo notfound", returnStdout: true).trim()
        return exists != 'found'
    }

    if (!stillMissing) {
        if (exitCode != 0) {
            echo "[OK] Unity Hub reported an error but modules are present on disk - continuing"
        }
        return [available: true, installed: true, message: "Modules installed: ${modules.join(', ')}"]
    }

    echo "[ERROR] Modules still missing after install attempt: ${stillMissing.join(', ')}"
    logInstalledEditors()
    return [available: false, installed: false, message: "Module installation failed (exit code ${exitCode}): ${stillMissing.join(', ')} still missing"]
}

/**
 * Accept Android SDK licenses and ensure required platform is installed.
 * Writes license acceptance hashes directly to the SDK licenses directory,
 * which is the only reliable method in non-interactive CI environments.
 * Then installs the target SDK platform if the project specifies one.
 */
def acceptAndroidSdkLicenses() {
    def sdkPath = env.ANDROID_HOME ?: ''
    if (!sdkPath && env.UNITY_VERSION) {
        sdkPath = "${getPlaybackEnginesPath(env.UNITY_VERSION)}/AndroidPlayer/SDK"
    }
    if (!sdkPath) {
        echo "[WARN] Cannot accept Android SDK licenses: SDK path not found"
        return
    }

    echo "[INFO] Accepting Android SDK licenses at ${sdkPath}..."

    // Write license acceptance files directly - this is how sdkmanager persists
    // accepted licenses. Each file contains newline-separated hash(es) of the
    // license text. These are the standard Google license hashes.
    try {
        sh """
            mkdir -p '${sdkPath}/licenses'

            echo '[INFO] Writing android-sdk-license...'
            printf '%s\\n' \\
                '8933bad161af4178b1185d1a37fbf41ea5269c55' \\
                'd56f5187479451eabf01fb78af6dfcb131a6481e' \\
                '24333f8a63b6825ea9c5514f83c2829b004d1fee' \\
                > '${sdkPath}/licenses/android-sdk-license'

            echo '[INFO] Writing android-sdk-preview-license...'
            printf '%s\\n' \\
                '84831b9409646a918e30573bab4c9c91346d8abd' \\
                > '${sdkPath}/licenses/android-sdk-preview-license'

            echo '[OK] License files written to ${sdkPath}/licenses'
        """
        echo "[OK] Android SDK licenses accepted"
    } catch (Exception e) {
        echo "[WARN] Failed to write Android SDK license files: ${e.message}"
    }

    // Locate sdkmanager for platform installation (prefer modern cmdline-tools over legacy)
    def sdkmanager = null
    def cmdlineToolsPath = "${sdkPath}/cmdline-tools/latest/bin/sdkmanager"
    def legacyToolsPath = "${sdkPath}/tools/bin/sdkmanager"

    def cmdlineExists = sh(script: "[ -f '${cmdlineToolsPath}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (cmdlineExists == 'found') {
        sdkmanager = cmdlineToolsPath
    } else {
        def legacyExists = sh(script: "[ -f '${legacyToolsPath}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (legacyExists == 'found') {
            sdkmanager = legacyToolsPath
        }
    }

    if (!sdkmanager) {
        echo "[WARN] sdkmanager not found - cannot auto-install SDK platforms"
        return
    }

    // Always use Unity's bundled JDK for sdkmanager — system JDKs (e.g. JDK 21) are
    // incompatible with Android SDK tools (removed javax.xml.bind causes NoClassDefFoundError).
    def javaHome = ''
    if (env.UNITY_VERSION) {
        def unityJdk = "${getPlaybackEnginesPath(env.UNITY_VERSION)}/AndroidPlayer/OpenJDK"
        def jdkExists = sh(script: "[ -f '${unityJdk}/bin/java' ] && echo found || echo notfound", returnStdout: true).trim()
        if (jdkExists == 'found') {
            javaHome = unityJdk
        } else {
            echo "[WARN] Unity bundled JDK not found at ${unityJdk} — sdkmanager may fail"
        }
    }

    // Detect target SDK version from the Unity project and install if needed
    // Unity's ProjectSettings.asset uses "AndroidTargetSdkVersion:" (0 = auto/highest installed)
    try {
        def projectPath = env.UNITY_PROJECT ?: ''
        if (projectPath) {
            def projectSettingsFile = "${projectPath}/ProjectSettings/ProjectSettings.asset"
            def settingsExists = sh(script: "[ -f '${projectSettingsFile}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (settingsExists == 'found') {
                def targetSdk = sh(script: "grep 'AndroidTargetSdkVersion:' '${projectSettingsFile}' | awk '{print \$2}' || true", returnStdout: true).trim()

                if (targetSdk && targetSdk.isInteger() && targetSdk.toInteger() > 0) {
                    echo "[INFO] Unity project targets Android SDK ${targetSdk}, ensuring platform is installed..."
                    def sdkInstallResult = sh(script: """
                        ${javaHome ? "export JAVA_HOME='${javaHome}'" : ''}
                        '${sdkmanager}' 'platforms;android-${targetSdk}' --sdk_root='${sdkPath}' 2>&1
                    """, returnStatus: true)
                    if (sdkInstallResult == 0) {
                        echo "[OK] Android SDK platform ${targetSdk} install completed"
                    } else {
                        echo "[WARN] sdkmanager failed to install Android SDK platform ${targetSdk} (exit ${sdkInstallResult}) — Unity may still build if the platform is already present"
                    }
                } else if (targetSdk == '0' || !targetSdk) {
                    echo "[INFO] AndroidTargetSdkVersion is auto (0) or not set — Unity will use highest installed platform"
                } else {
                    echo "[INFO] AndroidTargetSdkVersion value '${targetSdk}' is not a valid SDK level — skipping"
                }
            }
        }
    } catch (Exception e) {
        echo "[WARN] Could not auto-install target SDK platform: ${e.message}"
    }
}

/**
 * Check Xcode installation
 */
def checkXcode(boolean autoInstall = false) {
    try {
        def version = sh(script: 'xcodebuild -version | head -1', returnStdout: true).trim()
        if (version.contains('Xcode')) {
            return [available: true, message: version]
        }
    } catch (Exception e) {
        // Xcode not available
    }

    // Check if Xcode.app exists but isn't selected
    def xcodeExists = sh(script: "[ -d '/Applications/Xcode.app' ] && echo found || echo notfound", returnStdout: true).trim()
    if (xcodeExists == 'found') {
        if (autoInstall) {
            try {
                sh 'sudo xcode-select -s /Applications/Xcode.app/Contents/Developer'
                return [available: true, installed: true, message: 'Xcode selected successfully']
            } catch (Exception e) {
                // Could not select
            }
        }
        return [
            available: false,
            message: 'Xcode installed but not selected',
            installInstructions: 'Run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer'
        ]
    }

    return [
        available: false,
        message: 'Xcode not installed',
        installInstructions: 'Install from Mac App Store or: xcode-select --install (for command line tools only)'
    ]
}

/**
 * Check Xcode Command Line Tools
 */
def checkXcodeCommandLineTools(boolean autoInstall = false) {
    try {
        def path = sh(script: 'xcode-select -p', returnStdout: true).trim()
        if (path) {
            return [available: true, message: "Developer tools at ${path}"]
        }
    } catch (Exception e) {
        // Not installed
    }

    if (autoInstall) {
        return installXcodeCommandLineTools()
    }

    return [
        available: false,
        message: 'Xcode Command Line Tools not installed',
        installInstructions: 'Run: xcode-select --install'
    ]
}

/**
 * Install Xcode Command Line Tools
 */
def installXcodeCommandLineTools() {
    try {
        echo "[INFO] Installing Xcode Command Line Tools..."
        sh '''
            xcode-select --install || true
            # Wait for installation dialog - this is interactive
            echo "[WARN] Xcode Command Line Tools installation requires user interaction"
        '''
        return [
            available: false,
            installed: false,
            message: 'Installation initiated - requires user interaction',
            installInstructions: 'Complete the Xcode Command Line Tools installation dialog'
        ]
    } catch (Exception e) {
        return [available: false, installed: false, message: "Installation failed: ${e.message}"]
    }
}

/**
 * Check Ruby installation (macOS has system Ruby)
 */
def checkRuby(boolean autoInstall = false) {
    // Single sh call: check version and path
    def result = sh(
        script: '''#!/bin/bash
            echo "Checking Ruby installation..." >&2
            RVER=$(ruby --version 2>/dev/null) || true
            if [ -n "$RVER" ] && echo "$RVER" | grep -q "ruby"; then
                RPATH=$(which ruby 2>/dev/null) || RPATH="unknown"
                echo "  Found: $RVER at $RPATH" >&2
                echo "FOUND"
                echo "$RVER"
                echo "$RPATH"
                exit 0
            fi
            echo "  Ruby not found" >&2
            echo "NOT_FOUND"''',
        returnStdout: true
    ).trim()

    echo "  checkRuby stdout: ${result}"
    def lines = result.readLines()
    if (lines[0] == 'FOUND' && lines.size() >= 3) {
        def isSystemRuby = lines[2] == '/usr/bin/ruby'
        def note = isSystemRuby ? ' (system Ruby - consider using rbenv or brew ruby)' : ''
        return [available: true, message: "${lines[1]}${note}", version: lines[1]]
    }

    if (autoInstall) {
        return installRuby()
    }

    return [
        available: false,
        message: 'Ruby not found',
        installInstructions: 'macOS includes system Ruby, or install via: brew install ruby'
    ]
}

/**
 * Install Ruby via Homebrew
 */
def installRuby() {
    try {
        echo "[INFO] Installing Ruby via Homebrew..."
        sh 'brew install ruby'
        return [available: true, installed: true, message: 'Ruby installed successfully']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Run: brew install ruby'
        ]
    }
}

/**
 * Check Fastlane installation
 */
def checkFastlane(boolean autoInstall = false) {
    try {
        def version = sh(script: 'fastlane --version | head -1', returnStdout: true).trim()
        if (version.contains('fastlane')) {
            // Periodic update check (every 14 days)
            try {
                if (shouldRunPeriodicUpdate('fastlane')) {
                    echo "[INFO] Checking for Fastlane updates..."
                    sh(script: '''
                        if command -v brew &>/dev/null && brew list fastlane &>/dev/null; then
                            brew upgrade fastlane || true
                        else
                            gem update fastlane --no-document || true
                        fi
                    ''', returnStatus: true)
                    markUpdateChecked('fastlane')
                }
            } catch (Exception e) {
                echo "[WARN] Fastlane update check failed: ${e.message}"
            }

            return [available: true, message: version]
        }
    } catch (Exception e) {
        // Fastlane not installed
    }

    if (autoInstall) {
        return installFastlane()
    }

    return [
        available: false,
        message: 'Fastlane not installed',
        installInstructions: 'Run: gem install fastlane (may need sudo) or: brew install fastlane'
    ]
}

/**
 * Install Fastlane
 */
def installFastlane() {
    try {
        echo "[INFO] Installing Fastlane..."
        // Try brew first (doesn't require sudo)
        sh '''
            if command -v brew &>/dev/null; then
                brew install fastlane
            else
                gem install fastlane --no-document
            fi
        '''
        markUpdateChecked('fastlane')
        return [available: true, installed: true, message: 'Fastlane installed successfully']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Run: sudo gem install fastlane'
        ]
    }
}

/**
 * Check PlasticSCM installation
 */
def checkPlasticSCM(boolean autoInstall = false) {
    // Single sh call: check version, auth status, and common paths
    def output = sh(script: '''
        echo "Checking PlasticSCM..." >&2
        if cm version >/dev/null 2>&1; then
            VER=$(cm version)
            WHOAMI=$(cm whoami 2>/dev/null || true)
            if [ -n "$WHOAMI" ] && ! echo "$WHOAMI" | grep -q "not logged"; then
                echo "FOUND_AUTH"
                echo "$VER"
                echo "$WHOAMI"
            elif [ -n "$WHOAMI" ]; then
                echo "FOUND_NOT_LOGGED_IN"
                echo "$VER"
            else
                echo "FOUND_UNKNOWN_AUTH"
                echo "$VER"
            fi
        else
            for cmpath in /Applications/PlasticSCM.app/Contents/MacOS/cm /usr/local/bin/cm; do
                if [ -f "$cmpath" ]; then
                    echo "FOUND_NOT_IN_PATH"
                    echo "$cmpath"
                    exit 0
                fi
            done
            echo "NOT_FOUND"
        fi
    ''', returnStdout: true).trim()

    echo "  checkPlasticSCM stdout: ${output}"
    def lines = output.readLines()
    def status = lines[0]
    def version = lines.size() > 1 ? lines[1] : ''

    // Plastic found - run periodic update check (separate call since it modifies the system)
    if (status == 'FOUND_AUTH' || status == 'FOUND_NOT_LOGGED_IN' || status == 'FOUND_UNKNOWN_AUTH') {
        try {
            if (shouldRunPeriodicUpdate('plastic_scm')) {
                echo "[INFO] Checking for Plastic SCM updates..."
                sh(script: 'brew upgrade --cask plastic-scm || true', returnStatus: true)
                markUpdateChecked('plastic_scm')
            }
        } catch (Exception e) {
            echo "[WARN] Plastic SCM update check failed: ${e.message}"
        }
    }

    if (status == 'FOUND_AUTH') {
        def whoami = lines.size() > 2 ? lines[2] : ''
        return [available: true, message: "${version} (logged in as: ${whoami})"]
    }

    if (status == 'FOUND_NOT_LOGGED_IN') {
        return [
            available: false,
            message: 'PlasticSCM installed but not authenticated',
            installInstructions: 'Run: cm login or configure through Plastic GUI'
        ]
    }

    // cm whoami failed/empty - still available, just can't verify auth
    if (status == 'FOUND_UNKNOWN_AUTH') {
        return [available: true, message: version]
    }

    if (status == 'FOUND_NOT_IN_PATH') {
        def cmPath = lines.size() > 1 ? lines[1] : ''
        return [
            available: false,
            message: "PlasticSCM found at ${cmPath} but not in PATH",
            installInstructions: "Add PlasticSCM to PATH: export PATH=\"\$PATH:${cmPath.replace('/cm', '')}\""
        ]
    }

    if (autoInstall) {
        return installPlasticSCM()
    }

    return [
        available: false,
        message: 'PlasticSCM not installed',
        installInstructions: 'Run: brew install --cask plastic-scm'
    ]
}

/**
 * Install PlasticSCM via Homebrew
 */
def installPlasticSCM() {
    try {
        echo "[INFO] Installing PlasticSCM via Homebrew..."
        sh 'brew install --cask plastic-scm'
        markUpdateChecked('plastic_scm')
        return [available: true, installed: true, message: 'PlasticSCM installed successfully - run cm login to authenticate']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Download from https://www.plasticscm.com/download'
        ]
    }
}

/**
 * Check CocoaPods installation
 */
def checkCocoaPods(boolean autoInstall = false) {
    try {
        def version = sh(script: 'pod --version', returnStdout: true).trim()
        if (version) {
            return [available: true, message: "CocoaPods ${version}"]
        }
    } catch (Exception e) {
        // CocoaPods not installed
    }

    if (autoInstall) {
        return installCocoaPods()
    }

    return [
        available: false,
        message: 'CocoaPods not installed',
        installInstructions: 'Run: gem install cocoapods (may need sudo) or: brew install cocoapods'
    ]
}

/**
 * Install CocoaPods
 */
def installCocoaPods() {
    try {
        echo "[INFO] Installing CocoaPods..."
        sh '''
            if command -v brew &>/dev/null; then
                brew install cocoapods
            else
                gem install cocoapods --no-document
            fi
            pod setup || true
        '''
        return [available: true, installed: true, message: 'CocoaPods installed successfully']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Run: sudo gem install cocoapods'
        ]
    }
}

// ============================================================================
// DOWNLOADABLE TOOLS (rclone, SteamCMD, UnityDataTool)
// ============================================================================

/**
 * Check if rclone is installed in tools directory
 */
def checkRclone(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def rclonePath = "${toolsDir}/rclone/rclone"

    // Single sh call: check existence and get version
    def output = sh(script: """
        echo "Checking rclone installation..." >&2
        if [ -f '${rclonePath}' ]; then
            echo "  rclone found at ${rclonePath}" >&2
            echo "FOUND"
            '${rclonePath}' version | head -1
        else
            echo "  rclone not found" >&2
            echo "NOT_FOUND"
        fi
    """, returnStdout: true).trim()

    echo "  checkRclone stdout: ${output}"
    def lines = output.readLines()
    def status = lines[0]

    if (status == 'FOUND') {
        env.RCLONE_PATH = rclonePath
        def version = lines.size() > 1 ? lines[1] : 'rclone found'
        return [available: true, message: version, path: rclonePath]
    }

    if (autoInstall) {
        return installRclone()
    }

    return [
        available: false,
        message: 'rclone not installed',
        installInstructions: "Run: buildUtils.installRclone() or download from https://rclone.org/downloads/"
    ]
}

/**
 * Download and install rclone (latest release) to tools directory
 */
def installRclone() {
    def toolsDir = getToolsDir()
    def rcloneDir = "${toolsDir}/rclone"

    // Detect architecture
    def arch = sh(script: 'uname -m', returnStdout: true).trim()
    def archSuffix = arch == 'arm64' ? 'osx-arm64' : 'osx-amd64'

    echo "[INFO] Installing rclone (latest, ${archSuffix})..."
    sh """
        set -e
        RCLONE_DIR="${rcloneDir}"
        ARCH_SUFFIX="${archSuffix}"
        TEMP_DIR="\$(mktemp -d)"

        mkdir -p "\$RCLONE_DIR"

        echo "Fetching latest rclone release..."
        DOWNLOAD_URL=\$(curl -fsSL "https://api.github.com/repos/rclone/rclone/releases/latest" | grep "browser_download_url.*\$ARCH_SUFFIX.zip" | head -1 | cut -d'"' -f4)
        VERSION=\$(echo "\$DOWNLOAD_URL" | grep -oE 'v[0-9]+\\.[0-9]+\\.[0-9]+')
        echo "Downloading rclone \$VERSION..."
        curl -fsSL "\$DOWNLOAD_URL" -o "\$TEMP_DIR/rclone.zip"

        echo "Extracting..."
        unzip -q "\$TEMP_DIR/rclone.zip" -d "\$TEMP_DIR"

        cp "\$TEMP_DIR"/rclone-*/rclone "\$RCLONE_DIR/rclone"
        chmod +x "\$RCLONE_DIR/rclone"

        rm -rf "\$TEMP_DIR"

        echo "[OK] rclone \$VERSION installed to \$RCLONE_DIR"
    """

    env.RCLONE_PATH = "${rcloneDir}/rclone"
    return [available: true, installed: true, message: "rclone installed", path: env.RCLONE_PATH]
}

/**
 * Check if SteamCMD is installed in tools directory
 */
def checkSteamCMD(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def steamCmdPath = "${toolsDir}/steamcmd/steamcmd.sh"

    def exists = sh(script: "[ -f '${steamCmdPath}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists == 'found') {
        env.STEAMCMD_PATH = steamCmdPath
        return [available: true, message: "SteamCMD found", path: steamCmdPath]
    }

    if (autoInstall) {
        return installSteamCMD()
    }

    return [
        available: false,
        message: 'SteamCMD not installed',
        installInstructions: "Run: buildUtils.installSteamCMD() or download from https://steamcdn-a.akamaihd.net/client/installer/steamcmd_osx.tar.gz"
    ]
}

/**
 * Download and install SteamCMD to tools directory
 */
def installSteamCMD() {
    def toolsDir = getToolsDir()
    def steamCmdDir = "${toolsDir}/steamcmd"

    try {
        echo "[INFO] Installing SteamCMD..."
        sh """
            set -e
            STEAMCMD_DIR="${steamCmdDir}"
            TEMP_DIR="\$(mktemp -d)"

            mkdir -p "\$STEAMCMD_DIR"

            echo "Downloading SteamCMD..."
            curl -fsSL "https://steamcdn-a.akamaihd.net/client/installer/steamcmd_osx.tar.gz" -o "\$TEMP_DIR/steamcmd.tar.gz"

            echo "Extracting..."
            tar -xzf "\$TEMP_DIR/steamcmd.tar.gz" -C "\$STEAMCMD_DIR"

            rm -rf "\$TEMP_DIR"

            echo "Running SteamCMD initial update..."
            "\$STEAMCMD_DIR/steamcmd.sh" +quit || true

            echo "[OK] SteamCMD installed to \$STEAMCMD_DIR"
        """

        env.STEAMCMD_PATH = "${steamCmdDir}/steamcmd.sh"
        return [available: true, installed: true, message: "SteamCMD installed", path: env.STEAMCMD_PATH]
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Download from https://steamcdn-a.akamaihd.net/client/installer/steamcmd_osx.tar.gz'
        ]
    }
}

// ============================================================================
// STEAM BUILD SUPPORT
// ============================================================================

/**
 * Preflight check for SteamCMD - auto-installs if missing, tests authentication.
 * If Steam Guard is required, sends a Slack notification and pauses for user input.
 */
def preflightSteamCMD() {
    def steamCheck = checkSteamCMD(true)  // auto-install if missing
    if (!steamCheck.available) {
        error "[ERROR] SteamCMD not available: ${steamCheck.message}"
    }

    env.STEAMCMD_PATH = steamCheck.path

    sh """
        if [ ! -f "${env.STEAMCMD_PATH}" ]; then
            echo "[ERROR] SteamCMD not found at: ${env.STEAMCMD_PATH}"
            exit 1
        fi
        echo "[OK] SteamCMD found at ${env.STEAMCMD_PATH}"
    """

    // Test Steam authentication with the build credentials.
    // SteamCMD can HANG waiting for interactive Steam Guard input - +quit only
    // runs after login succeeds, so if login blocks for a code, the process
    // never exits. We wrap the test login in a timeout to handle this.
    withCredentials([usernamePassword(credentialsId: 'steam-credentials', usernameVariable: 'STEAM_USERNAME', passwordVariable: 'STEAM_PASSWORD')]) {
        echo "[INFO] Testing Steam authentication..."
        def testResult = ''
        def needsSteamGuard = false

        try {
            timeout(time: 20, unit: 'SECONDS') {
                testResult = sh(
                    script: '"${STEAMCMD_PATH}" +login "${STEAM_USERNAME}" "${STEAM_PASSWORD}" +quit 2>&1 || true',
                    returnStdout: true
                ).trim()
            }
        } catch (Exception e) {
            echo "[INFO] SteamCMD login timed out - likely waiting for Steam Guard input"
            needsSteamGuard = true
        }

        if (!needsSteamGuard) {
            needsSteamGuard = testResult.contains("Steam Guard") || testResult.contains("Two-factor") || testResult.contains("Account Logon Denied")
        }

        if (needsSteamGuard) {
            echo ""
            echo "=========================================="
            echo "Steam Guard Authorization Required"
            echo "=========================================="
            echo "A Steam Guard code has been sent to your email/authenticator."
            echo "Enter the code in Jenkins to continue the build."
            echo "=========================================="

            try {
                def steamUser = env.STEAM_USERNAME ?: 'unknown'
                def inputUrl = "${env.BUILD_URL}input"
                def mention = common.getSlackMention(env.BUILD_USER ?: '', env.BUILD_USER_EMAIL ?: '')
                common.sendSlackMessage(
                    message: ":lock: *Steam Guard code required* for account *${steamUser}* on <${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>\n" +
                             "${mention} - check your email/authenticator and <${inputUrl}|enter the code here> to continue the build."
                )
            } catch (Exception e) {
                echo "[WARN] Could not send Slack notification: ${e.message}"
            }

            def steamGuardCode = ''
            try {
                steamGuardCode = input(
                    message: 'Enter Steam Guard code from your email or authenticator',
                    parameters: [string(
                        name: 'STEAM_GUARD_CODE',
                        description: 'Steam Guard code',
                        trim: true
                    )]
                )
            } catch (Exception e) {
                error("Steam Guard authorization was aborted - build cannot continue without Steam login")
            }

            if (!steamGuardCode) {
                error("No Steam Guard code provided - build cannot continue without Steam login")
            }

            echo "[INFO] Logging in with Steam Guard code..."
            def guardResult
            withEnv(["STEAM_GUARD_CODE=${steamGuardCode}"]) {
                guardResult = sh(
                    script: '"${STEAMCMD_PATH}" +set_steam_guard_code "${STEAM_GUARD_CODE}" +login "${STEAM_USERNAME}" "${STEAM_PASSWORD}" +quit 2>&1 || true',
                    returnStdout: true
                ).trim()
            }

            if (guardResult.contains("Logged in OK") || guardResult.contains("Waiting for user info")) {
                echo "[OK] Steam Guard authorization successful - token cached for future builds"
            } else if (guardResult.contains("Steam Guard") || guardResult.contains("Account Logon Denied")) {
                error("Steam Guard code was rejected - check the code and retry the build")
            } else {
                echo "[WARNING] Uncertain login status after Steam Guard - build will continue"
                echo "[INFO] Login output: ${guardResult}"
            }
        } else if (testResult.contains("Logged in OK") || testResult.contains("Waiting for user info")) {
            echo "[OK] Steam authentication successful"
        } else {
            echo "[WARNING] Uncertain Steam auth status - build will continue but may fail at upload"
            echo "[INFO] Login output: ${testResult}"
        }
    }
}

/**
 * Ensure Steam staging directory exists.
 * macOS uses ~/.buildtools/steam/ (no path length limits like Windows).
 */
def preflightSteamStaging() {
    def toolsDir = getToolsDir()
    sh """
        mkdir -p "${toolsDir}/steam"
        echo "[OK] Steam staging directory available: ${toolsDir}/steam"
    """
}

/**
 * Copy build output to a staging directory for SteamCMD upload.
 * @param sourcePath Path to the build output directory
 * @return The staging path
 */
def setupSteamStaging(String sourcePath) {
    def toolsDir = getToolsDir()
    def randomId = "${env.BUILD_NUMBER}_${System.currentTimeMillis()}"
    def stagingPath = "${toolsDir}/steam/${randomId}"

    echo "[INFO] Staging Steam content: ${stagingPath}"
    echo "[INFO] Source: ${sourcePath}"

    sh """
        set -e
        mkdir -p "${stagingPath}"
        echo "Copying to staging folder..."
        cp -R "${sourcePath}/"* "${stagingPath}/" || cp -R "${sourcePath}" "${stagingPath}/"
        echo "[OK] Content staged to ${stagingPath}"
    """

    logBuildOutputs(stagingPath)

    env.STEAM_STAGING_PATH = stagingPath
    return stagingPath
}

/**
 * Clean up Steam staging folder for this build and any orphaned folders older than 1 day.
 */
def cleanupSteamStaging() {
    try {
        def toolsDir = getToolsDir()
        sh """
            # Clean up this build's staging folder
            if [ -n "\${STEAM_STAGING_PATH:-}" ] && [ -d "\${STEAM_STAGING_PATH}" ]; then
                rm -rf "\${STEAM_STAGING_PATH}"
                echo "[OK] Cleaned up staging folder: \${STEAM_STAGING_PATH}"
            fi

            # Clean up any orphaned staging folders older than 1 day
            if [ -d "${toolsDir}/steam" ]; then
                echo "Checking for orphaned staging folders..."
                find "${toolsDir}/steam" -mindepth 1 -maxdepth 1 -type d -mtime +1 -exec rm -rf {} \\; || true
            fi
        """
    } catch (Exception e) {
        echo "[WARNING] Steam staging cleanup failed (no node context?): ${e.message}"
    }
}

/**
 * Get the Steam staging base path
 */
def getSteamStagingPath() {
    return env.STEAM_STAGING_PATH ?: "${getToolsDir()}/steam"
}

/**
 * Upload a build to Steam with automatic retry on transient failures.
 *
 * @param config Map with keys:
 *   appId        - Steam App ID
 *   depotId      - Steam Depot ID
 *   contentRoot  - Path to build content to upload
 *   version      - Build version string for the description
 *   setLive      - Branch to set live (e.g. 'beta', 'development', or '' for none)
 *   artifactPath - Path to write the VDF file
 *   maxRetries   - Number of retry attempts (default: 3)
 */
def steamUpload(Map config) {
    def appId = config.appId
    def depotId = config.depotId
    def contentRoot = config.contentRoot
    def version = config.version ?: env.VERSION
    def setLive = config.setLive ?: ''
    def artifactPath = config.artifactPath ?: env.ARTIFACT_PATH
    def maxRetries = config.maxRetries ?: 3

    // Steam refuses SetLive on the default branch from SteamCMD - asking for it
    // fails the whole commit, so upload without it and let a human set it live.
    if (setLive && (setLive.equalsIgnoreCase('default') || setLive.equalsIgnoreCase('public') || setLive.equalsIgnoreCase('none'))) {
        echo "[WARN] SetLive '${setLive}' cannot be set from SteamCMD - uploading without SetLive. Set the build live on the Steamworks site."
        setLive = ''
    }

    echo "========================================"
    echo "Steam Upload"
    echo "========================================"
    def platformSuffix = (env.PLATFORM ?: 'standaloneosx').toLowerCase()
    def vdfName = "steam_${platformSuffix}.vdf"

    echo "App ID: ${appId}"
    echo "Depot ID: ${depotId}"
    echo "Content: ${contentRoot}"
    echo "Set Live: ${setLive ?: '(none)'}"
    echo "Max retries: ${maxRetries}"
    echo ""

    // Create VDF build script
    def setLiveLine = setLive ? "    \"SetLive\" \"${setLive}\"" : ''
    def vdfContent = """"AppBuild"
{
    "AppID" "${appId}"
    "Desc" "Build ${version}"
    "ContentRoot" "${contentRoot}"
    "BuildOutput" "steam_logs"
    "Depots"
    {
        "${depotId}"
        {
            "FileMapping"
            {
                "LocalPath" "*"
                "DepotPath" "."
                "recursive" "1"
            }
        }
    }
${setLiveLine}
}"""

    def vdfFile = "${artifactPath}/${vdfName}"
    writeFile file: vdfFile, text: vdfContent
    echo "[OK] VDF build script created at: ${vdfFile}"
    echo vdfContent

    def lastError = ''
    def failure = null

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        echo "[INFO] Steam upload attempt ${attempt}/${maxRetries}..."

        try {
            // tee so the upload still streams to the console live, while the
            // output is captured for classification. The exit code is echoed
            // from inside the subshell so it's SteamCMD's, not tee's.
            def uploadLog = "${env.WORKSPACE}/steamcmd_upload.log"
            sh """
                set +e
                ( "${env.STEAMCMD_PATH}" +login "\${STEAM_USERNAME}" "\${STEAM_PASSWORD}" +run_app_build "${vdfFile}" +quit 2>&1; echo "STEAM_EXIT_CODE:\$?" ) | tee "${uploadLog}"
                exit 0
            """
            def output = readFile(file: uploadLog)

            if (output.contains('Successfully finished') || output.contains('STEAM_EXIT_CODE:0')) {
                echo "[OK] Steam upload succeeded on attempt ${attempt}"
                common.updateUploadStatus('store', 'done')
                return
            }

            failure = common.classifySteamFailure(output, setLive)
        } catch (hudson.AbortException e) {
            throw e
        } catch (Exception e) {
            failure = [retryable: true, summary: e.message, hints: [], branchSuspect: false]
        }

        lastError = failure.summary

        // Steam commits the build server-side even when it reports the commit as
        // failed, so retrying a rejection just piles up duplicate builds. Stop.
        if (!failure.retryable) {
            echo "[WARNING] Steam upload attempt ${attempt} failed: ${lastError}"
            echo "[INFO] Not retrying - this failure won't clear on its own."
            dumpSteamContentLog()
            common.reportSteamFailure(failure, null)
            common.updateUploadStatus('store', 'failed')
            env.STEAM_UPLOAD_ERROR = lastError
            error "[ERROR] ${lastError}"
        }

        if (attempt < maxRetries) {
            def waitTime = 30 * attempt
            echo "[WARNING] Steam upload failed (${lastError}). Retrying in ${waitTime}s..."
            sleep(waitTime)
        }
    }

    dumpSteamContentLog()
    if (failure) common.reportSteamFailure(failure, null)
    common.updateUploadStatus('store', 'failed')
    env.STEAM_UPLOAD_ERROR = lastError
    error "[ERROR] Steam upload failed after ${maxRetries} attempts: ${lastError}"
}

/**
 * Dump the tail of SteamCMD's own content log - it carries the real reason a
 * commit was rejected, which the console output only summarises as 'Failure'.
 */
def dumpSteamContentLog(int tailLines = 60) {
    if (!env.STEAMCMD_PATH) return

    sh(script: """
        LOG="\$(dirname "${env.STEAMCMD_PATH}")/logs/content_log.txt"
        if [ -f "\$LOG" ]; then
            echo "===== SteamCMD content_log.txt (last ${tailLines} lines) ====="
            tail -n ${tailLines} "\$LOG"
            echo "===== end content_log.txt ====="
        else
            echo "[INFO] No content_log.txt found at \$LOG"
        fi
        exit 0
    """)
}

/**
 * Validate that macOS Standalone build support (IL2CPP) is available.
 * Unlike Linux on Windows, macOS requires the mac-il2cpp module for Release builds.
 */
def validateMacOSBuildSupport() {
    echo "[INFO] Verifying macOS Standalone Build Support..."
    def modulesCheck = checkUnityModules(env.UNITY_VERSION, ['mac-il2cpp'], true)

    if (!modulesCheck.available) {
        error "[ERROR] macOS Standalone Build Support (IL2CPP) is not installed for Unity ${env.UNITY_VERSION}\n" +
              "Install via Unity Hub: \"Unity Hub\" -- --headless install-modules -v ${env.UNITY_VERSION} -m mac-il2cpp -cm"
    }

    echo "[OK] macOS Standalone Build Support (IL2CPP) module verified"
}

/**
 * Check that the pinned UnityDataTool release (common.unityDataToolVersion()) is installed in
 * the tools directory, (re)installing it when missing or a different version.
 */
def checkUnityDataTool(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def toolDir = "${toolsDir}/unity_data_tool"
    def toolPath = "${toolDir}/UnityDataTool"
    def versionFile = "${toolDir}/.version"
    def wanted = common.unityDataToolVersion()

    // Outputs: MISSING | INSTALLED + version line
    def status = sh(
        script: """
            if [ ! -f '${toolPath}' ]; then echo MISSING; exit 0; fi
            echo INSTALLED
            cat '${versionFile}' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()

    def lines = status.readLines().collect { it.trim() }.findAll { it }
    def installed = lines.size() > 1 ? lines[1] : ''

    if (lines[0] == 'INSTALLED' && installed == wanted) {
        env.UNITY_DATA_TOOL_PATH = toolPath
        return [available: true, message: "UnityDataTool ${installed}", path: toolPath]
    }
    if (!autoInstall) {
        return [
            available: false,
            message: lines[0] == 'MISSING' ? 'UnityDataTool not installed' : "UnityDataTool ${installed ?: 'unknown'} installed, ${wanted} required",
            installInstructions: "Run: buildUtils.installUnityDataTool() or download ${wanted} from GitHub"
        ]
    }
    if (lines[0] == 'INSTALLED') echo "[INFO] UnityDataTool ${installed ?: 'unknown'} installed, switching to pinned ${wanted}"
    return installUnityDataTool()
}

/**
 * Download and install the pinned UnityDataTool release to the tools directory.
 * Releases from v2.3.0 refuse any UnityFileSystemApi older than Unity 6.7, so the version is
 * pinned (common.unityDataToolVersion()) rather than tracking latest.
 */
def installUnityDataTool() {
    def toolsDir = getToolsDir()
    def toolDir = "${toolsDir}/unity_data_tool"
    def wanted = common.unityDataToolVersion()

    // Note: Unity only provides macOS ARM64 builds - Intel Macs not officially supported
    echo "[INFO] Installing UnityDataTool ${wanted} (macos-arm64)..."
    sh """
        set -e
        TOOL_DIR="${toolDir}"
        TEMP_DIR="\$(mktemp -d)"

        # Wipe the old install so files from another release can't linger
        rm -rf "\$TOOL_DIR"
        mkdir -p "\$TOOL_DIR"

        echo "Fetching UnityDataTool ${wanted}..."
        DOWNLOAD_URL=\$(curl -fsSL "https://api.github.com/repos/Unity-Technologies/UnityDataTools/releases/tags/${wanted}" | grep "browser_download_url.*macos-arm64.*\\.zip" | head -1 | cut -d'"' -f4)
        if [ -z "\$DOWNLOAD_URL" ]; then echo "[ERROR] No macos-arm64 zip in release ${wanted}"; exit 1; fi
        curl -fsSL "\$DOWNLOAD_URL" -o "\$TEMP_DIR/tool.zip"

        echo "Extracting..."
        unzip -oq "\$TEMP_DIR/tool.zip" -d "\$TOOL_DIR"

        chmod +x "\$TOOL_DIR/UnityDataTool"

        # Save version tag for the pinned-version check
        echo "${wanted}" > "\$TOOL_DIR/.version"

        rm -rf "\$TEMP_DIR"

        echo "[OK] UnityDataTool ${wanted} installed to \$TOOL_DIR"
    """

    env.UNITY_DATA_TOOL_PATH = "${toolDir}/UnityDataTool"
    return [available: true, installed: true, message: "UnityDataTool ${wanted} installed", path: env.UNITY_DATA_TOOL_PATH]
}

// ============================================================================
// PREFLIGHT CHECK FUNCTIONS
// ============================================================================

/**
 * Verify network connectivity to cloud services before running other preflights.
 * Retries up to 3 times with 15s delay - catches transient DNS/firewall/cloud issues early.
 */
def preflightNetwork() {
    // Single sh call with retry logic - all output to console (no returnStdout)
    sh """
        MAX_RETRIES=3
        RETRY_DELAY=15
        for attempt in \$(seq 1 \$MAX_RETRIES); do
            FAILED=""

            if nc -z -w 10 asianortheast1-00-cloud.plasticscm.com 8787 2>/dev/null; then
                if [ \$attempt -eq 1 ]; then
                    echo "[OK] Plastic SCM Cloud reachable"
                else
                    echo "[OK] Plastic SCM Cloud reachable (attempt \$attempt)"
                fi
            else
                FAILED="\$FAILED Plastic SCM Cloud"
            fi

            if curl -sf --max-time 10 -o /dev/null https://api.github.com; then
                if [ \$attempt -eq 1 ]; then
                    echo "[OK] GitHub API reachable"
                else
                    echo "[OK] GitHub API reachable (attempt \$attempt)"
                fi
            else
                FAILED="\$FAILED GitHub API"
            fi

            [ -z "\$FAILED" ] && exit 0

            if [ \$attempt -lt \$MAX_RETRIES ]; then
                echo "[WARN] Network check attempt \$attempt/\$MAX_RETRIES - unreachable:\$FAILED. Retrying in \${RETRY_DELAY}s..."
                sleep \$RETRY_DELAY
            fi
        done

        echo "[ERROR] Cloud services unreachable after \$MAX_RETRIES attempts:\$FAILED. Check agent network/DNS/firewall."
        exit 1
    """
}

/**
 * Configure git to authenticate with GitHub using credentials from the environment.
 * Two mechanisms for maximum compatibility:
 *   1. git config --global url.insteadOf — rewrites URLs to embed credentials
 *   2. GIT_ASKPASS script — git calls this to get credentials (works even if
 *      Unity uses its own git or a different HOME)
 * Must be called before Unity opens (Startup stage) so UPM can resolve private packages.
 */
def configureGitAuth() {
    def user = env.GITHUB_TOKEN_USR?.trim()
    def pass = env.GITHUB_TOKEN_PSW?.trim()
    if (user && pass) {
        echo "[INFO] Configuring git credentials for GitHub..."

        // Method 1: url.insteadOf (works for system git)
        sh(script: "git config --global url.\"https://${user}:${pass}@github.com/\".insteadOf \"https://github.com/\"", returnStatus: true)

        // Method 2: GIT_ASKPASS script (works for any git, including Unity's embedded git)
        def askpassPath = "${env.WORKSPACE}/.git-askpass.sh"
        writeFile file: askpassPath, text: """#!/bin/sh
case "\$1" in
    *assword*) echo '${pass}' ;;
    *)         echo '${user}' ;;
esac
"""
        sh "chmod +x '${askpassPath}'"
        env.GIT_ASKPASS = askpassPath
        env.GIT_TERMINAL_PROMPT = '0'

        echo "[OK] Git configured to authenticate with GitHub as ${user}"
    } else {
        echo "[WARN] GitHub credentials not available — skipping default git auth configuration"
    }

    // Optional org-scoped PATs (from GITHUB_ORG_PATS) — applied regardless of the default cred.
    configureGitHubOrgPats()
}

/**
 * Apply org-scoped GitHub PAT rewrites from the GITHUB_ORG_PATS job env var
 * ("org=credentialId" pairs). Each pair binds a Secret-text credential and adds a
 * git insteadOf rewrite scoped to github.com/<org>/ using the x-access-token form
 * (works for fine-grained PATs and GitHub App installation tokens). Git applies the
 * longest-matching insteadOf, so the org PAT wins for that org while the default
 * github credential still covers the rest of github.com.
 *
 * The token is passed via the credential's env var (${VAR}, expanded by the shell),
 * never interpolated into the command by Groovy — so it isn't exposed in the script text.
 */
def configureGitHubOrgPats() {
    def pats = common.parseOrgPats(env.GITHUB_ORG_PATS)
    if (!pats) return

    pats.each { p ->
        try {
            withCredentials([string(credentialsId: p.credId, variable: 'GH_ORG_PAT')]) {
                def rc = sh(script: "git config --global url.\"https://x-access-token:\${GH_ORG_PAT}@github.com/${p.org}/\".insteadOf \"https://github.com/${p.org}/\"", returnStatus: true)
                if (rc == 0) {
                    echo "[OK] GitHub PAT configured for github.com/${p.org}/ (credential: ${p.credId})"
                } else {
                    echo "[WARN] Failed to configure GitHub PAT for ${p.org} (git config rc=${rc})"
                }
            }
        } catch (Exception e) {
            echo "[WARN] Skipping GitHub PAT for ${p.org} — credential '${p.credId}' unavailable: ${e.message}"
        }
    }
}

/**
 * Strip malformed "-framework Foo.framework" flags from the generated Xcode project and the
 * CocoaPods xcconfigs, and report every file that carried one.
 *
 * ld appends ".framework" to whatever token follows -framework, so a flag that already has the
 * extension makes it search for Foo.framework.framework and fail with
 *   ld: framework 'Foo.framework' not found
 * The name without the extension is the only valid form, so the rewrite is safe and idempotent.
 *
 * Several tools write this project - Unity post-processors, EDM4U's pod install, EDM4U's Swift
 * Package integration, and third-party podspecs that declare s.frameworks with the extension -
 * so normalising here, after all of them have run and before xcodebuild, is the only place that
 * catches every source. The log names the offending file, which identifies the culprit.
 */
def normalizeFrameworkLinkerFlags(String xcodePath) {
    if (!xcodePath) return
    echo "[INFO] Normalising '-framework X.framework' linker flags under ${xcodePath}"
    sh """
        cd "${xcodePath}" 2>/dev/null || exit 0

        FILES=""
        [ -f Unity-iPhone.xcodeproj/project.pbxproj ] && FILES="Unity-iPhone.xcodeproj/project.pbxproj"
        [ -d Pods ] && FILES="\$FILES \$(find Pods -name '*.xcconfig' 2>/dev/null)"
        FILES="\$FILES \$(find . -maxdepth 2 -name '*.xcconfig' -not -path './Pods/*' 2>/dev/null)"

        CHANGED=0
        for f in \$FILES; do
            [ -f "\$f" ] || continue
            BEFORE=\$(cksum "\$f" | awk '{print \$1}')
            # Two forms: the pbxproj array ("-framework", "Foo.framework"), whose tokens sit on
            # separate lines, and the xcconfig one-liner (-framework "Foo.framework").
            # -weak_framework/-lazy_framework/-reexport_framework take the same argument.
            perl -0777 -pi -e 's/("-(?:weak_|lazy_|reexport_)?framework",\\s*")([A-Za-z0-9_+.-]+)\\.framework(")/\$1\$2\$3/gs; s/(-(?:weak_|lazy_|reexport_)?framework\\s+"?)([A-Za-z0-9_+.-]+)\\.framework\\b/\$1\$2/g' "\$f"
            AFTER=\$(cksum "\$f" | awk '{print \$1}')
            if [ "\$BEFORE" != "\$AFTER" ]; then
                echo "  [FIXED] \$f carried a -framework flag with a .framework suffix"
                CHANGED=\$((CHANGED+1))
            fi
        done

        if [ "\$CHANGED" -eq 0 ]; then
            echo "  No malformed framework flags found"
        else
            echo "[OK] Normalised \$CHANGED file(s) - the file named above identifies the SDK at fault"
        fi
    """
}

/**
 * Remove git auth configuration added by configureGitAuth()/configureGitHubOrgPats().
 * Called in the post block to avoid leaving credentials on disk. The get-regexp output
 * (which includes token-bearing key names) is piped straight into the unset loop, so it
 * is never echoed to the log. Covers both the default and org-scoped rewrites.
 */
def cleanupGitAuth() {
    sh(script: 'git config --global --get-regexp "url\\.https://.*@github\\.com/" | while read key _; do git config --global --unset-all "$key"; done', returnStatus: true)
    sh(script: "rm -f '${env.WORKSPACE}/.git-askpass.sh'", returnStatus: true)
}

def preflightRclone() {
    def rcloneCheck = checkRclone(true)  // auto-install if missing
    if (!rcloneCheck.available) {
        error "[ERROR] rclone not available: ${rcloneCheck.message}"
    }

    // Always use the tools folder path (ignore any custom env vars)
    env.RCLONE_PATH = rcloneCheck.path

    withCredentials([file(credentialsId: 'rclone', variable: 'RCLONE_CONFIG')]) {
        sh """
            "${env.RCLONE_PATH}" version || exit 1
            "${env.RCLONE_PATH}" --config "\$RCLONE_CONFIG" about "\$RCLONE_REMOTE" >/dev/null || exit 1
            echo "rclone authenticated"
        """
        // Own OAuth client or service account? Only a yes/no leaves the shell - the file is a secret.
        def ownClient = sh(script: 'grep -Eq "^(client_id|service_account_(file|credentials))[[:space:]]*=[[:space:]]*[^[:space:]]" "$RCLONE_CONFIG"', returnStatus: true) == 0
        common.reportRcloneClient(ownClient)
    }
}

def preflightUnityDataTool() {
    def toolCheck = checkUnityDataTool(true)  // auto-install if missing
    if (!toolCheck.available) {
        error "[ERROR] UnityDataTool not available: ${toolCheck.message}"
    }
    env.UNITY_DATA_TOOL_PATH = toolCheck.path
    echo "[OK] ${toolCheck.message}"
}

def preflightPlasticSCM() {
    // Lock Plastic auth so only one agent refreshes the SSO token at a time.
    // Concurrent SSO logins can invalidate each other's sessions.
    lock(resource: 'plastic-scm-auth', quantity: 1) {
        withCredentials([string(credentialsId: 'plastic-token', variable: 'PLASTIC_TOKEN')]) {
            sh '''
                cm version || exit 1

                # Delete and recreate profile to ensure token is fresh
                if cm profile list | grep -q "oddgames_external@cloud"; then
                    cm profile delete oddgames_external@cloud >/dev/null 2>&1 || true
                fi
                cm profile create \
                    --server=oddgames_external@cloud \
                    --username=builds@oddgames.com.au \
                    --token="$PLASTIC_TOKEN" \
                    --workingmode=SSOWorkingMode

                cm whoami || exit 1
                echo "PlasticSCM authenticated"
            '''
        }
    }
}

def preflightCocoaPods() {
    def result = checkCocoaPods(true)  // auto-install if missing
    if (!result.available) {
        error "[ERROR] CocoaPods not available: ${result.message}\n${result.installInstructions ?: ''}"
    }
    echo "CocoaPods ready: ${result.message}"

    // Install xcpretty for cleaner xcodebuild output
    sh '''
        if ! command -v xcpretty >/dev/null 2>&1; then
            echo "[INFO] Installing xcpretty..."
            gem install xcpretty --no-document || sudo gem install xcpretty --no-document || echo "[WARN] Could not install xcpretty"
        fi
        echo "[OK] xcpretty: $(xcpretty --version || echo 'not available')"
    '''
}

def preflightFastlane() {
    def rc = sh(script: 'fastlane --version', returnStatus: true)
    if (rc != 0) {
        // Homebrew doesn't reinstall dependents when Ruby upgrades —
        // e.g. Ruby 3→4 breaks fastlane because gems like 'logger' are no longer bundled
        echo "[WARN] fastlane broken, reinstalling via brew..."
        sh 'brew reinstall fastlane'
        sh 'fastlane --version || exit 1'
    }
    echo "Fastlane available"
}

def preflightXcode() {
    // If XCODE_VERSION is set (e.g. "16", "26.0"), find matching Xcode and set DEVELOPER_DIR
    // Uses DEVELOPER_DIR (per-process, no sudo) instead of xcode-select -s (global, requires sudo)
    // A missing version is installed from a .xip on the build share - see installXcodeFromShare()
    def requiredVersion = env.XCODE_VERSION?.trim()

    if (requiredVersion) {
        def xcodePath = _findInstalledXcode(requiredVersion)

        if (!xcodePath) {
            // One install per agent at a time: two jobs would unxip 10+ GB into the same /Applications path
            lock(resource: "xcode-install-${env.NODE_NAME}") {
                // Whoever held the lock may have just installed it
                xcodePath = _findInstalledXcode(requiredVersion)
                if (!xcodePath) {
                    installXcodeFromShare(requiredVersion)
                    xcodePath = _findInstalledXcode(requiredVersion)
                }
            }
            if (!xcodePath) {
                def installed = sh(script: 'ls -d /Applications/Xcode*.app 2>/dev/null || echo "(none found)"', returnStdout: true).trim()
                error """[ERROR] Xcode ${requiredVersion} still not found after installing it - the .xip on the share is probably a different version

Installed Xcodes on this agent:
${installed}"""
            }
        }

        env.DEVELOPER_DIR = "${xcodePath}/Contents/Developer"
        echo "[OK] DEVELOPER_DIR=${env.DEVELOPER_DIR}"

        // sudo -n: never wait for a password (there is no tty). The explicit xcodebuild path keeps
        // DEVELOPER_DIR's Xcode under sudo and is what the NOPASSWD sudoers rule below matches.
        sh """
            export DEVELOPER_DIR="${env.DEVELOPER_DIR}"
            XCB="\$DEVELOPER_DIR/usr/bin/xcodebuild"
            xcodebuild -version

            # A freshly installed Xcode refuses to build until its license is accepted (root only)
            if ! xcodebuild -license check >/dev/null 2>&1; then
                echo "[INFO] Accepting the Xcode license..."
                sudo -n "\$XCB" -license accept || {
                    echo "[WARN] Could not accept the Xcode license: sudo needs a password on this agent."
                    echo "[WARN] Builds will fail with 'You have not agreed to the Xcode license agreements'."
                    echo "[FIX]  Once, on the agent: sudo \$XCB -license accept"
                    echo "[FIX]  Or for every future Xcode (sudo visudo): \$(whoami) ALL=(root) NOPASSWD: /Applications/Xcode*.app/Contents/Developer/usr/bin/xcodebuild"
                }
            fi

            # Run first-launch setup if needed (installs device support, simulator runtimes)
            xcodebuild -runFirstLaunch || sudo -n "\$XCB" -runFirstLaunch || true

            # Ensure iOS platform is installed (required for generic/platform=iOS destination)
            echo "[INFO] Ensuring iOS platform is installed..."
            xcodebuild -downloadPlatform iOS || sudo -n "\$XCB" -downloadPlatform iOS || true
        """
    } else {
        sh '''
            xcodebuild -version || exit 1
            xcodebuild -runFirstLaunch || sudo xcodebuild -runFirstLaunch || true

            echo "[INFO] Ensuring iOS platform is installed..."
            xcodebuild -downloadPlatform iOS || sudo xcodebuild -downloadPlatform iOS || true
            echo "[OK] Xcode available (no XCODE_VERSION specified, using system default)"
        '''
    }
}

// Path of an installed Xcode matching `version`, or '' if there is none.
// Supports xcodes naming (Xcode-16.2.0.app), manual naming (Xcode_16.app, Xcode_16.2.app),
// and a plain Xcode.app whose version matches (App Store install).
def _findInstalledXcode(String version) {
    return sh(
        script: """
            for pattern in "/Applications/Xcode-${version}"*.app "/Applications/Xcode_${version}"*.app; do
                if [ -d "\$pattern" ]; then
                    echo "\$pattern"
                    exit 0
                fi
            done
            if [ -d "/Applications/Xcode.app" ]; then
                XCODE_APP_VER=\$(/Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild -version | awk '/^Xcode /{print \$2}')
                if echo "\$XCODE_APP_VER" | grep -q "^${version}"; then
                    echo "/Applications/Xcode.app"
                    exit 0
                fi
            fi
            echo ""
        """,
        returnStdout: true
    ).trim()
}

// Installs Xcode `version` from a .xip on the build share, with no Apple ID involved.
//
// Apple only serves Xcode to a signed-in Apple ID with 2FA, which a Jenkins agent can't do:
// `xcodes install 26.0` reads the password from the login keychain, which is locked outside a GUI
// session (OSStatus -25308 "User interaction is not allowed"), then Apple's sign-in answers with an
// HTML page ("not valid JSON ... Unexpected character '<'"), and a 2FA code could never be typed
// anyway. So the .xip is downloaded once per version, by hand, from
// https://developer.apple.com/download/all/ and dropped in
//     <LOCAL_SHARE_PATH>/_xcode/            (e.g. //odd-jenkins/builds/_xcode/Xcode_26.xip)
// keeping Apple's file name. Job env XCODE_XIP_SHARE_DIR overrides the folder. Every agent then
// installs from there: `xcodes install --path` unxips and checks Apple's signature without signing in.
def installXcodeFromShare(String version) {
    def sharePath = env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds'
    def xipDir = (env.XCODE_XIP_SHARE_DIR?.trim() ?: '_xcode').replaceAll('^[/\\\\]+|[/\\\\]+$', '').replace('\\', '/')
    def shareHostPath = sharePath.replace('\\', '/').replaceFirst('^/+', '')   // odd-jenkins/builds
    def uncDir = "${sharePath}\\${xipDir.replace('/', '\\')}"
    // Not /tmp/local_builds: uploadToLocalShare() unmounts that under a concurrent job
    def mountPoint = '/tmp/xcode_share'
    def stagingDir = "${env.WORKSPACE_TMP ?: '/tmp'}/xcode_xip"

    echo "[INFO] Xcode ${version} is not installed on ${env.NODE_NAME} - installing from ${uncDir}"

    // Pick the .xip and copy it local: xcodes moves the .xip to the Trash after installing, which on
    // the SMB mount would delete the share's copy - and unxipping over SMB is slow anyway.
    // Log lines go to stderr; the last stdout line is the local .xip path.
    def localXip = sh(
        script: """
            set -e
            VERSION="${version}"
            # Apple drops trailing .0s: 16.0 ships as Xcode_16.xip, 26.0.1 as Xcode_26.0.1.xip
            SHORT=\$(echo "\$VERSION" | sed -E 's/(\\.0)+\$//')

            MOUNTED_HERE=no
            cleanup() { if [ "\$MOUNTED_HERE" = yes ]; then umount "${mountPoint}" >/dev/null 2>&1 || true; fi; }
            trap cleanup EXIT

            # macOS won't mount the same share twice for one user ("File exists") - reuse a live mount
            EXISTING=\$(mount | grep -i "@${shareHostPath} on " | sed -E 's/.* on (.*) \\(smbfs.*/\\1/' | head -1)
            if [ -n "\$EXISTING" ] && [ -d "\$EXISTING/${xipDir}" ]; then
                MOUNT_DIR="\$EXISTING"
            else
                mkdir -p "${mountPoint}"
                if mount | grep -q " on ${mountPoint} "; then umount "${mountPoint}" || true; fi
                mount_smbfs "//BUILD:build@${shareHostPath}" "${mountPoint}" >&2 || {
                    echo "[ERROR] Could not mount //${shareHostPath} to look for the Xcode .xip" >&2
                    exit 1
                }
                MOUNT_DIR="${mountPoint}"
                MOUNTED_HERE=yes
            fi

            SRC_DIR="\$MOUNT_DIR/${xipDir}"
            XIP=""
            for name in "Xcode_\$VERSION.xip" "Xcode_\$SHORT.xip"; do
                if [ -f "\$SRC_DIR/\$name" ]; then XIP="\$SRC_DIR/\$name"; break; fi
            done
            if [ -z "\$XIP" ]; then
                # "26" -> newest Xcode_26.*.xip; betas/RCs only when asked for by exact name
                XIP=\$(ls "\$SRC_DIR"/Xcode_"\$VERSION".*.xip 2>/dev/null | grep -viE 'beta|_rc' | sort -V | tail -1 || true)
            fi
            if [ -z "\$XIP" ]; then
                echo "[ERROR] No Xcode_\$VERSION*.xip in ${uncDir}. It has:" >&2
                ls -la "\$SRC_DIR" >&2 2>/dev/null || echo "  (folder does not exist)" >&2
                echo "[FIX] Download Xcode \$VERSION from https://developer.apple.com/download/all/ (signed in," >&2
                echo "      in a browser) and put the .xip in ${uncDir} with Apple's file name." >&2
                echo "      Every Mac agent then installs it on its next build." >&2
                exit 2
            fi

            rm -rf "${stagingDir}"
            mkdir -p "${stagingDir}"
            echo "[INFO] Copying \$(basename "\$XIP") (\$(du -h "\$XIP" | cut -f1)) to the agent..." >&2
            cp "\$XIP" "${stagingDir}/"
            echo "${stagingDir}/\$(basename "\$XIP")"
        """,
        returnStdout: true
    ).trim().readLines().last()

    try {
        sh """
            set -e
            command -v xcodes >/dev/null 2>&1 || brew install xcodes

            # The .xip expands to ~3-4x its size next to itself before moving into /Applications
            XIP_GB=\$(( \$(stat -f %z "${localXip}") / 1073741824 + 1 ))
            NEED_GB=\$(( XIP_GB * 5 ))
            FREE_GB=\$(df -g "${stagingDir}" | awk 'NR==2 {print \$4}')
            if [ "\$FREE_GB" -lt "\$NEED_GB" ]; then
                echo "[ERROR] Only \${FREE_GB} GB free on the agent; installing Xcode needs ~\${NEED_GB} GB."
                echo "[FIX]  Delete Xcodes no job uses any more: ls -d /Applications/Xcode*.app"
                exit 1
            fi

            # Version from Apple's file name: Xcode_26.0.1.xip -> 26.0.1, Xcode_26.1_beta_2.xip -> 26.1 beta 2
            XVER=\$(basename "${localXip}" .xip | sed -e 's/^Xcode_//' -e 's/_/ /g')
            echo "[INFO] Installing Xcode \$XVER - unxipping takes 10-20 minutes..."
            # --no-superuser: skip the steps that want sudo (license, components) - preflightXcode()
            #   does those with sudo -n. No --select: that's xcode-select -s (sudo); DEVELOPER_DIR picks it.
            xcodes install "\$XVER" --path "${localXip}" --no-superuser --empty-trash
        """
    } catch (Exception e) {
        def installed = sh(script: 'ls -d /Applications/Xcode*.app 2>/dev/null || echo "(none found)"', returnStdout: true).trim()
        error """[ERROR] Installing Xcode ${version} from ${uncDir} failed: ${e.message}

Installed Xcodes on this agent:
${installed}

[FIX] See the output above. If /Applications isn't writable, the agent's user needs to be an admin."""
    } finally {
        sh "rm -rf '${stagingDir}'"
    }
}

def preflightKeychain(String keychainPassword) {
    sh """
        /usr/bin/security unlock-keychain -p "${keychainPassword}" "\$HOME/Library/Keychains/login.keychain-db" || {
            echo "[ERROR] Failed to unlock keychain - password is incorrect"
            echo "  FIX: Update the Jenkins credential 'apple-keychain-pass' to match the login keychain password on this agent"
            exit 1
        }

        /usr/bin/security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${keychainPassword}" "\$HOME/Library/Keychains/login.keychain-db" || echo "[WARN] set-key-partition-list skipped (headless session, no Security Server) - non-critical, relying on persisted codesign ACL"

        CERT_COUNT=\$(/usr/bin/security find-identity -v -p codesigning "\$HOME/Library/Keychains/login.keychain-db" | grep -c "valid identities found" || echo "0")
        if [ "\$CERT_COUNT" -eq 0 ]; then
            echo "[ERROR] No valid code signing identities found"
            exit 1
        fi
        echo "[OK] Keychain unlocked, partition list set, certificates available"
    """
}

def unlockKeychain(String keychainPassword) {
    sh """
        /usr/bin/security unlock-keychain -p "${keychainPassword}" "\$HOME/Library/Keychains/login.keychain-db"
        /usr/bin/security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${keychainPassword}" "\$HOME/Library/Keychains/login.keychain-db" || true
        /usr/bin/security set-keychain-settings -l -u -t 3600 "\$HOME/Library/Keychains/login.keychain-db" || echo "[WARN] set-keychain-settings skipped (headless session, no Security Server) - non-critical, relying on persisted keychain settings"
    """
}

def runCocoaPods(String xcodePath) {
    sh """
        cd "${xcodePath}"

        # Skip the CocoaPods analytics ping (a network round-trip on every install) and
        # enable parallel code-signing of pods — both are free, safe build-time savings.
        export COCOAPODS_DISABLE_STATS=1
        export COCOAPODS_PARALLEL_CODE_SIGN=true

        POD_BIN=\$(which pod)
        echo "Using pod at: \$POD_BIN"

        if \$POD_BIN repo list | grep -q "^cocoapods\\\$"; then
            echo "Removing deprecated 'cocoapods' git repo..."
            \$POD_BIN repo remove cocoapods || true
        fi

        if [ -f "Podfile" ]; then
            sed -i.bak '/github.com\\/CocoaPods\\/Specs/d' Podfile
            if ! grep -q "^source" Podfile; then
                sed -i.bak "1i\\\\
source 'https://cdn.cocoapods.org/'\\\\
" Podfile
            fi
            rm -f Podfile.bak
        fi

        # --- Pods cache (keyed by a hash of the normalised Podfile) ---
        # Unity regenerates the whole Xcode project each build, so Pods/ is always absent
        # here and every build does a full pod re-integration. Restoring a matching Pods/ +
        # Podfile.lock makes 'pod install' incremental (skips dependency resolution and the
        # download/copy of every pod) whenever the Podfile is unchanged. The cache lives in
        # \$HOME (survives the workspace wipe) and is keyed by the Podfile hash, so any
        # dependency change invalidates it automatically. The 3-attempt retry below still
        # purges and rebuilds from scratch if a restored cache is ever broken.
        JOB_KEY=\$(echo "${env.JOB_NAME}" | tr '/ ' '__')
        CACHE_DIR="\$HOME/.jenkins_pods_cache/\$JOB_KEY"
        PODFILE_HASH=""
        [ -f Podfile ] && PODFILE_HASH=\$(shasum Podfile | awk '{print \$1}')

        # rm -rf Pods can fail with only "Directory not empty": some other process is still writing
        # into it while it's deleted (a leftover pod install - e.g. EDM4U's from the Unity build, or an
        # aborted build's). Retry, name the writer, and if it still won't go, move it aside so this
        # install gets a clean Pods/ anyway - a rename works even while the writer is running.
        remove_dir() {
            [ -e "\$1" ] || return 0
            for attempt in 1 2 3; do
                rm -rf "\$1" 2>/dev/null && return 0
                sleep 3
            done
            echo "[WARN] Could not delete \$1 - another process is writing into it:"
            lsof +D "\$1" 2>/dev/null | head -20 || true
            ps -axo pid,etime,command | grep -iE '[p]od install|[C]ocoaPods|[U]nity' | head -10 || true
            STALE="\$1.stale.\$\$"
            mv "\$1" "\$STALE" || return 1
            echo "[WARN] Moved it to \$STALE; the install continues with a fresh \$1"
            rm -rf "\$STALE" 2>/dev/null || true
        }
        # Leftovers from an earlier remove_dir() that couldn't delete them either
        for stale in Pods.stale.*; do
            if [ -e "\$stale" ]; then rm -rf "\$stale" 2>/dev/null || true; fi
        done

        CACHE_HIT="no"
        save_pods_cache() {
            # Nothing to do on a cache hit (Pods are already identical) or with no Podfile
            [ "\$CACHE_HIT" = "yes" ] && return 0
            [ -z "\$PODFILE_HASH" ] && return 0
            [ -d Pods ] || return 0
            mkdir -p "\$CACHE_DIR"
            rm -rf "\$CACHE_DIR/Pods" "\$CACHE_DIR/Podfile.lock" "\$CACHE_DIR/Podfile.sha"
            cp -R Pods "\$CACHE_DIR/Pods"
            [ -f Podfile.lock ] && cp Podfile.lock "\$CACHE_DIR/Podfile.lock"
            echo "\$PODFILE_HASH" > "\$CACHE_DIR/Podfile.sha"
            echo "[CACHE] Saved Pods cache for \$JOB_KEY"
        }

        if [ -n "\$PODFILE_HASH" ] && [ -f "\$CACHE_DIR/Podfile.sha" ] && [ -d "\$CACHE_DIR/Pods" ] && [ -f "\$CACHE_DIR/Podfile.lock" ] && [ "\$(cat "\$CACHE_DIR/Podfile.sha")" = "\$PODFILE_HASH" ]; then
            echo "[CACHE] Podfile unchanged — restoring Pods/ + Podfile.lock for an incremental install"
            remove_dir Pods
            cp -R "\$CACHE_DIR/Pods" Pods
            cp "\$CACHE_DIR/Podfile.lock" Podfile.lock
            CACHE_HIT="yes"
        else
            echo "[CACHE] No usable Pods cache (miss or Podfile changed) — clean install"
            rm -f Podfile.lock
            remove_dir Pods
        fi
        rm -rf *.xcworkspace

        # Force HTTP/1.1 to avoid HTTP/2 framing layer errors with GitHub downloads
        CURLRC_BACKUP=""
        if [ -f ~/.curlrc ]; then
            CURLRC_BACKUP=\$(cat ~/.curlrc)
        fi
        echo "--http1.1" >> ~/.curlrc

        cleanup_curlrc() {
            if [ -n "\$CURLRC_BACKUP" ]; then
                echo "\$CURLRC_BACKUP" > ~/.curlrc
            else
                rm -f ~/.curlrc
            fi
        }
        trap cleanup_curlrc EXIT

        # Disable exit on error for retry logic
        set +e

        # Attempt 1: Just run
        echo "Running pod install..."
        if \$POD_BIN install; then
            echo "Pod install succeeded"
            exit 0
        fi
        echo "[FAILED] Attempt 1 failed"

        # Attempt 2: Retry with repo update
        echo "[RETRY] Retrying with --repo-update..."
        sleep 30
        if \$POD_BIN install --repo-update; then
            echo "Pod install succeeded"
            exit 0
        fi
        echo "[FAILED] Attempt 2 failed"

        # Attempt 3: Purge and retry
        echo "[PURGE] Purging cache and retrying..."
        \$POD_BIN cache clean --all || true
        rm -rf ~/Library/Caches/CocoaPods
        remove_dir Pods
        rm -f Podfile.lock
        \$POD_BIN repo update || true
        echo "[WAIT] Waiting 5 minutes before final attempt (CDN recovery)..."
        sleep 300
        if \$POD_BIN install --repo-update; then
            echo "Pod install succeeded"
            exit 0
        fi

        echo "[ERROR] Pod install failed after 3 attempts"
        exit 1
    """
}

def archiveXcodeProject(Map config) {
    def xcodePath = config.xcodePath
    def archivePath = config.archivePath
    def logPath = config.logPath ?: "${env.ARTIFACT_PATH}/xcodebuild_archive.log"
    def configuration = config.configuration ?: (env.BUILD_TYPE == 'Debug' ? 'Debug' : 'Release')

    echo "[INFO] Xcode archive configuration: ${configuration}"

    // Runs after every project-mutating tool (Unity post-processors, pod install, EDM4U SPM)
    // and before xcodebuild, so a bad -framework flag from any of them is caught in one place.
    normalizeFrameworkLinkerFlags(xcodePath)

    // Automatic signing reconciles the embedded entitlements DOWN to whatever the
    // provisioning profile it resolves grants. A stale managed profile on this agent
    // (minted before a capability was enabled on the App ID) makes Xcode silently
    // strip that entitlement from the signed binary even though the entitlements file
    // requested it — e.g. com.apple.developer.applesignin missing → 'Continue with
    // Apple' fails at runtime with ASAuthorizationError 1000. Per-job opt-in: jobs
    // that depend on an entitlement list it in config.requiredEntitlements and the
    // build fails loudly here instead of shipping broken auth. Always dump the signed
    // entitlements to the log for post-hoc inspection.
    def required = (config.requiredEntitlements ?: []) as List
    def entGate = required.collect { ent -> """
            /usr/bin/codesign -d --entitlements :- "\$APP_PATH" 2>/dev/null | grep -q '${ent}' \\
                || { echo "[ERROR] Required entitlement missing from signed binary: ${ent} (stale provisioning profile on this agent? purge ~/Library/MobileDevice/Provisioning Profiles and re-fetch)"; exit 1; }""" }.join("\n")

    sh """
        cd "${xcodePath}"
        set -o pipefail
        PRETTY="cat"
        command -v xcpretty >/dev/null 2>&1 && PRETTY="xcpretty"
        /usr/bin/xcodebuild archive \\
            -workspace "Unity-iPhone.xcworkspace" \\
            -scheme "Unity-iPhone" \\
            -configuration ${configuration} \\
            -destination "generic/platform=iOS" \\
            -archivePath "${archivePath}" \\
            -allowProvisioningUpdates \\
            DEBUG_INFORMATION_FORMAT=dwarf-with-dsym \\
            GCC_GENERATE_DEBUGGING_SYMBOLS=YES \\
            2>&1 | tee "${logPath}" | \$PRETTY

        XCODE_EXIT=\${PIPESTATUS[0]}

        # Check if archive was actually created
        if [ ! -d "${archivePath}" ]; then
            echo "[ERROR] Archive failed - no archive created at ${archivePath}"
            exit 1
        fi

        # Check log for error indicators
        if grep -q "\\*\\* ARCHIVE FAILED \\*\\*" "${logPath}"; then
            echo "[ERROR] Archive failed - see log for details"
            exit 1
        fi

        # Match xcodebuild error format ('^error:' or '<path>: error:'), not arbitrary 'error:'
        # substrings in script-phase output like curl's 'returned error: 400'.
        if grep -qE '(^|: )error:' "${logPath}" && ! grep -q "0 errors generated" "${logPath}"; then
            echo "[ERROR] Archive completed with errors - see log for details"
            grep -E '(^|: )error:' -A2 "${logPath}" | head -20
            exit 1
        fi

        echo "[OK] Archive created successfully"

        # --- entitlements visibility + optional required-entitlement gate ---
        APP_PATH=\$(find "${archivePath}/Products/Applications" -maxdepth 1 -name '*.app' | head -1)
        if [ -n "\$APP_PATH" ]; then
            echo "[INFO] Signed entitlements for \$(basename "\$APP_PATH"):"
            /usr/bin/codesign -d --entitlements :- "\$APP_PATH" 2>/dev/null || true
${entGate}
        fi
    """

    // A Run Script phase can flag the build UNSTABLE with `echo "warning: [BUILD-WARN] msg"`;
    // UNSTABLE_WARNING_PATTERNS promotes native xcodebuild warnings. Read from the tee'd log on
    // the agent - the 'Build Warnings' stage turns the collected lines into the UNSTABLE result.
    common.collectToolWarnings(logPath, 'Xcode')
}

def generateExportOptionsPlist(Map config) {
    def outputPath = config.outputPath
    def teamId = config.teamId ?: env.APPLE_TEAM_ID
    def method = config.method ?: 'development'  // 'development', 'app-store', 'ad-hoc', 'enterprise'
    // 'app-store' is deprecated in Xcode 15+; normalize to 'app-store-connect'
    if (method == 'app-store') method = 'app-store-connect'
    def uploadSymbols = config.uploadSymbols ?: (method == 'app-store-connect')

    def plistContent = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>${method}</string>
    <key>teamID</key>
    <string>${teamId}</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>iCloudContainerEnvironment</key>
    <string>Production</string>
    <key>uploadSymbols</key>
    <${uploadSymbols}/>
    <key>uploadBitcode</key>
    <false/>
    <key>manageAppVersionAndBuildNumber</key>
    <false/>
    <key>ITSAppUsesNonExemptEncryption</key>
    <false/>
</dict>
</plist>"""

    writeFile file: outputPath, text: plistContent
    echo "Generated export options plist: ${outputPath} (method: ${method}, teamId: ${teamId})"
}

def exportXcodeArchive(Map config) {
    def archivePath = config.archivePath
    def exportPath = config.exportPath
    def exportOptionsPlist = config.exportOptionsPlist
    def logPath = config.logPath ?: "${env.ARTIFACT_PATH}/xcodebuild_export.log"
    def newName = config.newName

    sh """
        mkdir -p "${exportPath}"
        set -o pipefail
        PRETTY="cat"
        command -v xcpretty >/dev/null 2>&1 && PRETTY="xcpretty"
        /usr/bin/xcodebuild -exportArchive \\
            -archivePath "${archivePath}" \\
            -exportPath "${exportPath}" \\
            -exportOptionsPlist "${exportOptionsPlist}" \\
            -allowProvisioningUpdates \\
            | tee "${logPath}" | \$PRETTY || true

        cd "${exportPath}"
        ORIGINAL_IPA=\$(find . -maxdepth 1 -name "*.ipa" | head -1)
        if [ -n "\$ORIGINAL_IPA" ] && [ -n "${newName}" ]; then
            mv "\$ORIGINAL_IPA" "${newName}"
            echo "Renamed IPA to: ${newName}"
        fi
    """
}

def runUnityDataTool(Map config = [:]) {
    // Check/install UnityDataTool if needed
    if (!config.toolPath) {
        def toolCheck = checkUnityDataTool(true)  // auto-install if missing
        if (!toolCheck.available) {
            echo "[WARNING] UnityDataTool not available: ${toolCheck.message}"
            common.setUnstable("UnityDataTool analysis skipped - tool not available")
            return
        }
    }

    def toolPath = config.toolPath ?: env.UNITY_DATA_TOOL_PATH
    def buildPath = config.buildPath ?: env.BUILD_PATH
    def dbFile = config.outputFile ?: "${env.ARTIFACT_PATH}/assetBundles.db"
    def addressablesBase = config.addressablesBase ?: "${env.UNITY_PROJECT}/Library/com.unity.addressables/aa"
    def buildReportPath = "${env.UNITY_PROJECT}/Library/LastBuild.buildreport"
    def tempExtractDir = null

    try {
        // Step 1: Discover analyzable data (sharedassets in build output, archives, or Addressables)
        def discoveryResult = sh(script: """#!/bin/bash
echo "Searching for Unity data in build output..." >&2
FOUND=\$(find "${buildPath}" -name "sharedassets0.assets" -type f 2>/dev/null | head -1 | xargs -r dirname)
if [ -n "\$FOUND" ]; then
    echo "  Found sharedassets: \$FOUND" >&2
    echo "SHAREDASSETS"
    echo "\$FOUND"
    [ -f "${buildReportPath}" ] && echo "HAS_BUILDREPORT" || echo "NO_BUILDREPORT"
    exit 0
fi
echo "  No sharedassets found, checking for archives..." >&2
ARCHIVE=\$(ls "${buildPath}"/*.ipa "${buildPath}"/*.apk "${buildPath}"/*.aab 2>/dev/null | head -1)
if [ -n "\$ARCHIVE" ]; then
    echo "  Found archive: \$ARCHIVE" >&2
    echo "ARCHIVE"
    echo "\$ARCHIVE"
    [ -f "${buildReportPath}" ] && echo "HAS_BUILDREPORT" || echo "NO_BUILDREPORT"
    exit 0
fi
echo "  No archives found, checking Addressables..." >&2
BUNDLE=\$(find "${addressablesBase}" -name "*.bundle" -type f 2>/dev/null | head -1 | xargs -r dirname)
if [ -n "\$BUNDLE" ]; then
    echo "  Found Addressables: \$BUNDLE" >&2
    echo "ADDRESSABLES"
    echo "\$BUNDLE"
    [ -f "${buildReportPath}" ] && echo "HAS_BUILDREPORT" || echo "NO_BUILDREPORT"
    exit 0
fi
echo "  No analyzable data found" >&2
echo "NONE"
""", returnStdout: true).trim()

        echo "  runUnityDataTool discovery stdout: ${discoveryResult}"
        def lines = discoveryResult.readLines().collect { it.trim() }.findAll { it }
        def discoveryType = lines[0]

        if (discoveryType == 'NONE') {
            echo "[INFO] No analyzable Unity data found - skipping (no build output, archive, or Addressables)"
            return
        }

        def analyzeDir = lines[1]

        // Step 2: If archive found, extract and find Unity data inside
        if (discoveryType == 'ARCHIVE') {
            def archiveFile = analyzeDir
            echo "[INFO] Archive detected - extracting for analysis: ${archiveFile}"
            tempExtractDir = "${env.WORKSPACE}/temp_analyze_extract"

            def extractResult = sh(script: """#!/bin/bash
echo "Extracting archive for analysis..." >&2
rm -rf "${tempExtractDir}"
mkdir -p "${tempExtractDir}"
unzip -q "${archiveFile}" -d "${tempExtractDir}"
echo "  Searching extracted contents..." >&2
FOUND=\$(find "${tempExtractDir}" -name "sharedassets0.assets" -type f 2>/dev/null | head -1 | xargs -r dirname)
if [ -n "\$FOUND" ]; then
    echo "  Found Unity data: \$FOUND" >&2
    echo "\$FOUND"
else
    echo "  No Unity data in archive" >&2
    echo "NOT_FOUND"
fi
""", returnStdout: true).trim()

            def extractedDir = extractResult.readLines().collect { it.trim() }.findAll { it }.last()
            if (extractedDir == 'NOT_FOUND') {
                echo "[INFO] No Unity data found in extracted archive - skipping"
                return
            }
            analyzeDir = extractedDir
            echo "[INFO] Found Unity data in extracted archive: ${analyzeDir}"
        }

        // Step 3: Stage tool with correct API dylib, copy BuildReport, and run analysis
        // Compute toolDir in Groovy to avoid an extra sh call
        def toolDir = toolPath.contains('/') ? toolPath.substring(0, toolPath.lastIndexOf('/')) : toolPath
        def stagedDir = "${env.WORKSPACE}/temp_unity_data_tool"
        def stagedToolPath = "${stagedDir}/UnityDataTool"
        def editorApiDylib = "${getUnityInstallDir(env.UNITY_VERSION)}/Unity.app/Contents/Tools/UnityFileSystemApi.dylib"

        echo "[INFO] Analyzing: ${analyzeDir}"
        sh """#!/bin/bash
echo "Staging UnityDataTool..." >&2
rm -rf '${stagedDir}'
cp -R '${toolDir}' '${stagedDir}'
if [ -f '${editorApiDylib}' ]; then
    mkdir -p '${stagedDir}/UnityFileSystem'
    cp '${editorApiDylib}' '${stagedDir}/UnityFileSystem/UnityFileSystemApi.dylib'
    echo "  Staged with UnityFileSystemApi.dylib from Unity ${env.UNITY_VERSION}" >&2
else
    echo "  WARNING: UnityFileSystemApi.dylib not found - using bundled version" >&2
fi
if [ -f '${buildReportPath}' ]; then
    cp '${buildReportPath}' '${analyzeDir}/LastBuild.buildreport' 2>/dev/null || true
    echo "  BuildReport included" >&2
fi
echo "Running analysis..." >&2
rm -f "${dbFile}"
chmod +x "${stagedToolPath}"
export DYLD_LIBRARY_PATH="${stagedDir}:\$DYLD_LIBRARY_PATH"
"${stagedToolPath}" analyze "${analyzeDir}" -o "${dbFile}" || { echo "[ERROR] Unity Data Tool analysis failed, exit code \$?" >&2; exit 1; }
echo "[OK] Analysis complete" >&2
"""

        analyzeBuildReport(dbFile: dbFile)
    } catch (Exception e) {
        echo "[WARNING] Unity Data Tool failed: ${e.message}"
        common.setUnstable("Unity Data Tool analysis failed")
    } finally {
        // Clean up temp directories
        def cleanupScript = "#!/bin/bash\n"
        if (tempExtractDir) {
            cleanupScript += "rm -rf '${tempExtractDir}'\n"
        }
        cleanupScript += "rm -rf '${env.WORKSPACE}/temp_unity_data_tool'"
        sh(script: cleanupScript, returnStatus: true)
    }
}

/**
 * Analyze the UnityDataTool SQLite database for oversized or problematic assets.
 * Queries shader_view, texture_view, mesh_view, and view_potential_duplicates.
 * Stores warnings in env.BUILD_REPORT_WARNINGS for Slack notification.
 */
def analyzeBuildReport(Map config = [:]) {
    def dbFile = config.dbFile ?: "${env.ARTIFACT_PATH}/assetBundles.db"
    def shaderThresholdMB = config.shaderThresholdMB ?: 2
    def textureThresholdMB = config.textureThresholdMB ?: 4
    def duplicateThresholdKB = config.duplicateThresholdKB ?: 512

    def exists = sh(script: "[ -f '${dbFile}' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists != 'found') {
        echo "[INFO] No build report database found, skipping analysis"
        return
    }

    // macOS ships with sqlite3
    def sqlite3Path = sh(script: "which sqlite3 || echo notfound", returnStdout: true).trim()
    if (sqlite3Path == 'notfound') {
        echo "[WARNING] sqlite3 not found, skipping build report analysis"
        return
    }

    echo "========================================"
    echo "BUILD REPORT ANALYSIS"
    echo "========================================"

    try {
        // Query 1: Large shaders (top 3 + total count)
        def shaderCountQuery = "SELECT count(*) FROM (SELECT name FROM shader_view GROUP BY name HAVING sum(size) > ${shaderThresholdMB * 1024 * 1024});"
        def shaderTotal = (sh(script: "sqlite3 '${dbFile}' \"${shaderCountQuery}\"", returnStdout: true).trim() ?: '0') as Integer
        def shaderQuery = "SELECT name, count(*) as instances, sum(size) as total_size FROM shader_view GROUP BY name HAVING total_size > ${shaderThresholdMB * 1024 * 1024} ORDER BY total_size DESC LIMIT 3;"
        def shaderOutput = sh(script: "sqlite3 -separator '|' '${dbFile}' \"${shaderQuery}\"", returnStdout: true).trim()
        if (shaderOutput) {
            def shaderItems = []
            shaderOutput.split('\n').each { line ->
                def parts = line.trim().split('\\|')
                if (parts.size() >= 3) {
                    def name = parts[0]
                    def instances = parts[1] as Integer
                    def totalSize = parts[2] as Long
                    def shortName = name.contains('/') ? name.split('/')[-1] : name
                    def instNote = instances > 1 ? " x${instances}" : ""
                    shaderItems << "${shortName} ${common.formatFileSize(totalSize)}${instNote}"
                }
            }
            if (shaderItems) {
                def more = shaderTotal > shaderItems.size() ? ", +${shaderTotal - shaderItems.size()} more" : ""
                common.addBuildWarning("Shaders (${shaderTotal}): ${shaderItems.join(' | ')}${more}")
            }
        }

        // Query 2: Large textures (top 3 + total count)
        def texCountQuery = "SELECT count(*) FROM texture_view WHERE size > ${textureThresholdMB * 1024 * 1024};"
        def texTotal = (sh(script: "sqlite3 '${dbFile}' \"${texCountQuery}\"", returnStdout: true).trim() ?: '0') as Integer
        def textureQuery = "SELECT name, width, height, size FROM texture_view WHERE size > ${textureThresholdMB * 1024 * 1024} ORDER BY size DESC LIMIT 3;"
        def textureOutput = sh(script: "sqlite3 -separator '|' '${dbFile}' \"${textureQuery}\"", returnStdout: true).trim()
        if (textureOutput) {
            def texItems = []
            textureOutput.split('\n').each { line ->
                def parts = line.trim().split('\\|')
                if (parts.size() >= 4) {
                    def name = parts[0]
                    def w = parts[1]
                    def h = parts[2]
                    def size = parts[3] as Long
                    def shortName = name.contains('/') ? name.split('/')[-1] : name
                    texItems << "${shortName} (${w}x${h}) ${common.formatFileSize(size)}"
                }
            }
            if (texItems) {
                def more = texTotal > texItems.size() ? ", +${texTotal - texItems.size()} more" : ""
                common.addBuildWarning("Textures (${texTotal}): ${texItems.join(' | ')}${more}")
            }
        }

        // Query 3: Duplicate assets wasting significant space
        def dupeQuery = "SELECT name, type, instances, total_size, size FROM view_potential_duplicates WHERE size > ${duplicateThresholdKB * 1024} ORDER BY total_size DESC LIMIT 10;"
        def dupeOutput = sh(script: "sqlite3 -separator '|' '${dbFile}' \"${dupeQuery}\"", returnStdout: true).trim()
        if (dupeOutput) {
            echo "[WARN] Duplicate assets (>${duplicateThresholdKB} KB each):"
            def totalWaste = 0L
            dupeOutput.split('\n').each { line ->
                def parts = line.trim().split('\\|')
                if (parts.size() >= 5) {
                    def name = parts[0]
                    def type = parts[1]
                    def instances = parts[2] as Integer
                    def totalSize = parts[3] as Long
                    def singleSize = parts[4] as Long
                    totalWaste += (totalSize - singleSize)
                    echo "  ${name} (${type}) - ${instances} copies, ${common.formatFileSize(totalSize - singleSize)} wasted"
                }
            }
            if (totalWaste > 0) {
                common.addBuildWarning("${common.formatFileSize(totalWaste)} wasted on duplicate assets")
            }
        }

        // Overall size breakdown by type
        def breakdownQuery = "SELECT type, count(*) as cnt, sum(size) as total FROM object_view GROUP BY type ORDER BY total DESC LIMIT 8;"
        def breakdownOutput = sh(script: "sqlite3 -separator '|' '${dbFile}' \"${breakdownQuery}\"", returnStdout: true).trim()
        if (breakdownOutput) {
            echo ""
            echo "Build content breakdown by type:"
            breakdownOutput.split('\n').each { line ->
                def parts = line.trim().split('\\|')
                if (parts.size() >= 3) {
                    def type = parts[0]
                    def count = parts[1]
                    def total = parts[2] as Long
                    echo "  ${type}: ${count} objects, ${common.formatFileSize(total)}"
                }
            }
        }

        if (!env.BUILD_REPORT_WARNINGS) {
            echo "[OK] No asset size warnings found"
        }

    } catch (Exception e) {
        echo "[WARNING] Build report analysis failed: ${e.message}"
    }
}

// ============================================================================
// UNITY FUNCTIONS
// ============================================================================

/**
 * Preflight check for Unity license activation (macOS).
 * Checks if a valid license file exists on the agent. If not, activates
 * using the Build Server serial key (node-locked to this machine).
 *
 * Required Jenkins credentials:
 *   unity-build-serial  - Secret text: Build Server serial (SB-XXXX-XXXX-XXXX-XXXX-XXXX)
 *   unity-credentials   - Username/password: Unity account for activation
 */
def preflightUnityLicense() {
    // macOS license file location
    def licensePath = "/Library/Application Support/Unity/Unity_lic.ulf"
    def hasLicense = sh(script: "test -f '${licensePath}' && echo found || echo missing", returnStdout: true).trim()

    if (hasLicense == 'found') {
        echo "[OK] Unity license file found (${licensePath})"
        return
    }

    echo "[INFO] No Unity license found on this agent - attempting activation..."

    if (!env.UNITY_VERSION) {
        error "[ERROR] Cannot activate Unity license: UNITY_VERSION not set. Run extractUnityVersion() first."
    }

    def unityExe = "${getUnityInstallDir(env.UNITY_VERSION)}/Unity.app/Contents/MacOS/Unity"
    def exeExists = sh(script: "test -f '${unityExe}' && echo found || echo missing", returnStdout: true).trim()
    if (exeExists != 'found') {
        error "[ERROR] Unity editor not found at ${unityExe}. Run validateUnityInstallation() first."
    }

    withCredentials([
        string(credentialsId: 'unity-build-serial', variable: 'UNITY_SERIAL'),
        usernamePassword(credentialsId: 'unity-credentials', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD')
    ]) {
        sh """
            echo "[INFO] Activating Unity Build Server license..."
            '${unityExe}' -quit -batchmode -nographics -serial "\$UNITY_SERIAL" -username "\$UNITY_USERNAME" -password "\$UNITY_PASSWORD" -logFile - 2>&1 || true
        """
    }

    // Verify the license file was created
    def verified = sh(script: "test -f '${licensePath}' && echo found || echo missing", returnStdout: true).trim()
    if (verified != 'found') {
        error "[ERROR] Unity activation command ran but license file was not created at ${licensePath}"
    }

    echo "[OK] Unity license activated and verified on this agent"
}

def extractUnityVersion(String unityProjectPath) {
    def versionMatch = readFile("${unityProjectPath}/ProjectSettings/ProjectSettings.asset") =~ /bundleVersion: (.+)/
    env.BASE_VERSION = versionMatch ? versionMatch[0][1].trim() : "0.0.0"
    versionMatch = null

    // Read ProjectVersion.txt for both editor version and changeset hash
    def projectVersionContent = readFile("${unityProjectPath}/ProjectSettings/ProjectVersion.txt")

    // Extract editor version (e.g. "2022.3.20f1")
    def editorVersionMatch = projectVersionContent =~ /m_EditorVersion:\s*(.+)/
    env.UNITY_VERSION = editorVersionMatch ? editorVersionMatch[0][1].trim() : ''

    // Extract changeset hash from revision line (e.g. "2022.3.20f1 (abc123def456)")
    // This is needed by Unity Hub CLI for versions not in the release list
    def changesetMatch = projectVersionContent =~ /m_EditorVersionWithRevision:.*\(([a-f0-9]+)\)/
    env.UNITY_CHANGESET = changesetMatch ? changesetMatch[0][1].trim() : ''

    echo "Base version: ${env.BASE_VERSION}"
    echo "Unity version: ${env.UNITY_VERSION}"
    if (env.UNITY_CHANGESET) {
        echo "Unity changeset: ${env.UNITY_CHANGESET}"
    }

    return [baseVersion: env.BASE_VERSION, unityVersion: env.UNITY_VERSION, changeset: env.UNITY_CHANGESET]
}

def validateUnityInstallation() {
    echo "========================================"
    echo "Validating Unity Installation"
    echo "========================================"

    // Determine required modules upfront so we can install editor + modules in one Hub command
    def platformModules = getRequiredUnityModules(env.PLATFORM) ?: []

    // Auto-install Unity if missing — pass modules so Hub installs everything in one pass
    def unityCheck = checkUnity(env.UNITY_VERSION, true, platformModules)

    if (!unityCheck.available) {
        error "[ERROR] Unity ${env.UNITY_VERSION} installation failed: ${unityCheck.message}"
    }

    if (unityCheck.installed) {
        echo "[OK] Unity ${env.UNITY_VERSION} was automatically installed"
    } else {
        echo "[OK] Unity ${env.UNITY_VERSION} found"
    }

    // Verify Unity is running natively (not under Rosetta on Apple Silicon)
    def machineArch = sh(script: "uname -m", returnStdout: true).trim()
    if (machineArch == 'arm64') {
        def unityBinary = "${getUnityInstallDir(env.UNITY_VERSION)}/Unity.app/Contents/MacOS/Unity"
        def binaryArchs = sh(script: "lipo -archs '${unityBinary}' || echo unknown", returnStdout: true).trim()
        if (binaryArchs.contains('arm64')) {
            echo "[OK] Unity binary is ARM64-native on Apple Silicon"
        } else {
            error "[ERROR] Unity ${env.UNITY_VERSION} is x86_64-only and would run under Rosetta on this Apple Silicon machine\n" +
                  "Binary architectures: ${binaryArchs}\n" +
                  "[FIX] Install the Apple Silicon (ARM64) version of Unity ${env.UNITY_VERSION} via Unity Hub"
        }
    }

    // Log PlaybackEngines contents for diagnostics
    def playbackEngines = getPlaybackEnginesPath(env.UNITY_VERSION)
    try {
        def peContents = sh(script: "ls -la '${playbackEngines}/' || echo '(directory not found)'", returnStdout: true).trim()
        echo "[INFO] PlaybackEngines contents:\n${peContents}"

        // Show platform-specific module contents if they exist
        def platformDirs = [
            'iOS': 'iOSSupport',
            'Android': 'AndroidPlayer',
            'Amazon': 'AndroidPlayer',
            'StandaloneOSX': 'MacStandaloneSupport',
            'Switch': 'SwitchSupport'
        ]
        def platformDir = platformDirs[env.PLATFORM]
        if (platformDir) {
            def modContents = sh(script: "ls -la '${playbackEngines}/${platformDir}/' || echo '(not installed)'", returnStdout: true).trim()
            echo "[INFO] ${platformDir} contents:\n${modContents}"
        }
    } catch (Exception e) {
        echo "[WARN] Could not list PlaybackEngines: ${e.message}"
    }

    if (!platformModules) {
        echo "[OK] No Unity modules required for ${env.PLATFORM}"
        return
    }

    echo "[INFO] Required modules for ${env.PLATFORM}: ${platformModules.join(', ')}"

    // Check modules — if editor was just installed with -m flags, these should already be present
    def modulesCheck = checkUnityModules(env.UNITY_VERSION, platformModules, true)

    if (modulesCheck.installed) {
        echo "[OK] Unity modules automatically installed"
    } else if (modulesCheck.available) {
        echo "[OK] Unity modules already installed"
    } else {
        error "[ERROR] Failed to install required Unity modules: ${modulesCheck.message}"
    }

    // Verify IL2CPP support for platforms that require it
    // iOS and Switch always use IL2CPP; other platforms use IL2CPP for Release builds
    def il2cppAlways = ['iOS', 'StandaloneOSX', 'Switch']
    def il2cppRelease = ['Android', 'Amazon']
    def needsIl2cpp = (env.PLATFORM in il2cppAlways) ||
                      (env.PLATFORM in il2cppRelease && env.BUILD_TYPE != 'Debug')

    if (needsIl2cpp) {
        verifyIl2cppSupport(playbackEngines)
    }

    // For Android IL2CPP builds, also verify NDK is present
    if (env.PLATFORM in ['Android', 'Amazon'] && needsIl2cpp) {
        verifyAndroidNdk(playbackEngines)
    }

    // Verify Android OpenJDK is present — Unity 6 renamed the child module ID
    if (env.PLATFORM in ['Android', 'Amazon']) {
        verifyAndroidJdk(playbackEngines)
    }

    // Accept Android SDK licenses after modules are installed
    if (env.PLATFORM in ['Android', 'Amazon']) {
        acceptAndroidSdkLicenses()
    }
}

/**
 * Verify IL2CPP compiler is available in the Unity installation.
 * The IL2CPP compiler lives in Editor/Data/il2cpp/ or Unity.app/Contents/il2cpp/
 * and is needed by all platforms that use IL2CPP scripting backend.
 */
def verifyIl2cppSupport(String playbackEngines) {
    echo "[INFO] Verifying IL2CPP support for ${env.PLATFORM} (BUILD_TYPE: ${env.BUILD_TYPE ?: 'unset'})..."

    def unityBase = getUnityInstallDir(env.UNITY_VERSION)
    def il2cppPaths = [
        "${unityBase}/Unity.app/Contents/il2cpp",
        "${unityBase}/Editor/Data/il2cpp"
    ]

    // Also check platform-specific IL2CPP locations
    def platformIl2cppDirs = [
        'iOS': "${playbackEngines}/iOSSupport/il2cpp",
        'StandaloneOSX': "${playbackEngines}/MacStandaloneSupport/il2cpp",
        'Android': "${playbackEngines}/AndroidPlayer/il2cpp",
        'Amazon': "${playbackEngines}/AndroidPlayer/il2cpp",
        'Switch': "${playbackEngines}/SwitchSupport/il2cpp"
    ]
    def platformIl2cpp = platformIl2cppDirs[env.PLATFORM]
    if (platformIl2cpp) {
        il2cppPaths << platformIl2cpp
    }

    def foundPath = null
    il2cppPaths.each { path ->
        if (!foundPath) {
            def exists = sh(script: "[ -d '${path}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (exists == 'found') {
                foundPath = path
            }
        }
    }

    if (foundPath) {
        echo "[OK] IL2CPP compiler found at: ${foundPath}"
    } else {
        echo "[WARN] IL2CPP compiler not found in expected locations:"
        il2cppPaths.each { echo "  - ${it}" }

        // Try to install the appropriate IL2CPP module
        def il2cppModule = (env.PLATFORM in ['StandaloneOSX']) ? 'mac-il2cpp' : null
        if (il2cppModule) {
            echo "[INFO] Attempting to install ${il2cppModule} module..."
            try {
                installUnityModules(env.UNITY_VERSION, [il2cppModule])
            } catch (Exception e) {
                echo "[WARN] ${il2cppModule} install failed: ${e.message}"
            }
        }

        // Search for il2cpp anywhere in the Unity installation
        def searchResult = sh(script: "find '${unityBase}' -name 'il2cpp' -type d -maxdepth 5 | head -5", returnStdout: true).trim()
        if (searchResult) {
            echo "[INFO] Found il2cpp directories:\n${searchResult}"
        } else {
            error "[ERROR] IL2CPP is not available for ${env.PLATFORM}. " +
                  "Reinstall Unity ${env.UNITY_VERSION} with IL2CPP support via Unity Hub:\n" +
                  "\"Unity Hub\" -- --headless install-modules -v ${env.UNITY_VERSION} -m mac-il2cpp -cm"
        }
    }
}

/**
 * Verify Android NDK is available (required for Android IL2CPP builds).
 * The NDK is bundled inside AndroidPlayer/SDK/ndk/.
 */
def verifyAndroidNdk(String playbackEngines) {
    def ndkBasePath = "${playbackEngines}/AndroidPlayer/SDK/ndk"
    def exists = sh(script: "[ -d '${ndkBasePath}' ] && echo found || echo notfound", returnStdout: true).trim()

    if (exists == 'found') {
        // List NDK versions present
        try {
            def ndkVersions = sh(script: "ls '${ndkBasePath}/' || echo '(empty)'", returnStdout: true).trim()
            echo "[OK] Android NDK found. Versions:\n${ndkVersions}"
        } catch (Exception e) {
            echo "[OK] Android NDK directory exists at: ${ndkBasePath}"
        }
    } else {
        echo "[WARN] Android NDK not found at: ${ndkBasePath}"
        echo "[INFO] NDK is required for IL2CPP Android builds. It should be installed with the android-sdk-ndk-tools module."
        echo "[INFO] Attempting to install android-sdk-ndk-tools..."
        try {
            installUnityModules(env.UNITY_VERSION, ['android-sdk-ndk-tools'])
        } catch (Exception e) {
            echo "[WARN] android-sdk-ndk-tools install failed: ${e.message}"
        }

        // Re-check
        exists = sh(script: "[ -d '${ndkBasePath}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (exists == 'found') {
            echo "[OK] Android NDK installed successfully"
        } else {
            error "[ERROR] Android NDK is required for IL2CPP builds but is not installed.\n" +
                  "Install via Unity Hub: \"Unity Hub\" -- --headless install-modules -v ${env.UNITY_VERSION} -m android-sdk-ndk-tools -cm"
        }
    }
}

/**
 * Extract the versioned module ID from Unity Hub's "Did you mean: android-open-jdk-17.0.18+8".
 *
 * @NonCPS so the java.util.regex.Matcher never lives in CPS scope. Holding one in a local
 * across a pipeline step throws NotSerializableException, whose getMessage() is the bare class
 * name - that is the "install failed: java.util.regex.Matcher" the JDK auto-install reported
 * instead of installing anything.
 */
@com.cloudbees.groovy.cps.NonCPS
private String _suggestedModuleId(String output, String prefix) {
    if (!output) return null
    def m = (output =~ /(?i)(${prefix}[\w.+\-]+)/)
    return m.find() ? m.group(1) : null
}

/**
 * Verify Android OpenJDK is present in the Unity installation.
 * The 'android' module with -cm should install it, but Unity 6 renamed the sub-module
 * (e.g. 'android-open-jdk-17.0.9+9') so it sometimes gets skipped.
 * Attempts auto-install if missing, fails the build if that doesn't work.
 */
def verifyAndroidJdk(String playbackEngines) {
    def jdkPath = "${playbackEngines}/AndroidPlayer/OpenJDK"
    def exists = sh(script: "[ -f '${jdkPath}/bin/java' ] && echo found || echo notfound", returnStdout: true).trim()

    if (exists == 'found') {
        echo "[OK] Android OpenJDK found at: ${jdkPath}"
        return
    }

    echo "[WARN] Android OpenJDK not found at: ${jdkPath}"

    // Ensure Unity Hub is available
    if (!env.UNITY_HUB_PATH) {
        def hubCheck = checkUnityHub()
        if (!hubCheck.available) {
            error "[ERROR] Android OpenJDK is missing and Unity Hub is not available to install it.\n" +
                  "Install the android-open-jdk module manually on this build agent."
        }
    }

    // Try installing with the legacy module name. If Hub doesn't recognize it,
    // it prints 'Did you mean: android-open-jdk-17.0.9+9' — we parse that and retry.
    def cmd = "'${env.UNITY_HUB_PATH}' -- --headless install-modules --version ${env.UNITY_VERSION} -m android-open-jdk -cm"
    echo "[INFO] Running: ${cmd}"
    def output = ''
    try {
        timeout(time: 15, unit: 'MINUTES') {
            output = sh(script: "${cmd} 2>&1 || true", returnStdout: true).trim()
        }
    } catch (Exception e) {
        echo "[WARN] install-modules timed out: ${e.message}"
    }
    if (output) { echo output }

    // Check if Hub suggested the real module name (Unity 6 versioned IDs)
    if (output.contains('Did you mean') || output.contains("Couldn't find module")) {
        def realModuleId = _suggestedModuleId(output, 'android-open-jdk')
        if (realModuleId) {
            echo "[INFO] Hub suggested correct module ID: ${realModuleId}"
            try {
                installUnityModules(env.UNITY_VERSION, [realModuleId])
            } catch (Exception e) {
                echo "[WARN] ${realModuleId} install failed: ${e.message}"
            }
        } else {
            echo "[WARN] Hub rejected the module name but suggested no replacement"
        }
    }

    // Re-check
    exists = sh(script: "[ -f '${jdkPath}/bin/java' ] && echo found || echo notfound", returnStdout: true).trim()
    if (exists == 'found') {
        echo "[OK] Android OpenJDK installed successfully"
    } else {
        error "[ERROR] Android OpenJDK is not installed and could not be auto-installed.\n" +
              "The Unity bundled JDK is required — system JDKs are incompatible.\n" +
              "Install manually on the build agent:\n" +
              "  \"Unity Hub\" -- --headless install-modules -v ${env.UNITY_VERSION} -m android-open-jdk -cm\n" +
              "If Hub says 'Couldn't find module', use the versioned name it suggests (e.g. android-open-jdk-17.0.9+9)."
    }
}

/**
 * Checks if the Unity Library cache is still valid for the current build configuration.
 * Compares the current Unity editor version and Plastic branch against the last successful build.
 * If either has changed, automatically wipes all cache directories (but not the full Library).
 * Stores build info in ~/.buildtools/ so it persists across workspace cleanups.
 */
def checkCacheValidity(String unityProjectPath) {
    def jobName = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    def markerFile = "${env.HOME}/.buildtools/.lastbuild_${jobName}"
    def currentVersion = env.UNITY_VERSION ?: ''
    def currentBranch = env.PLASTICSCM_BRANCH ?: env.BRANCH ?: ''

    if (!currentVersion) {
        echo "[Cache Integrity] No Unity version set, skipping check"
        return
    }

    // Read previous build info
    def previousVersion = ''
    def previousBranch = ''
    def markerExists = sh(script: "[ -f '${markerFile}' ] && echo true || echo false", returnStdout: true).trim()
    if (markerExists == 'true') {
        def markerContent = readFile(markerFile).trim()
        def lines = markerContent.split('\n')
        lines.each { line ->
            if (line.startsWith('unity_version=')) previousVersion = line.replace('unity_version=', '').trim()
            if (line.startsWith('branch=')) previousBranch = line.replace('branch=', '').trim()
        }
    } else {
        echo "[Cache Integrity] No previous build info found - clearing cache to ensure clean state"
    }

    echo "[Cache Integrity] Previous: Unity ${previousVersion ?: '(none)'} on ${previousBranch ?: '(none)'}"
    echo "[Cache Integrity] Current:  Unity ${currentVersion} on ${currentBranch}"

    def fullWipe = false
    if (!previousVersion) {
        echo "[Cache Integrity] No previous build info - first build or marker was deleted"
        fullWipe = true
    } else if (previousVersion != currentVersion) {
        echo "[Cache Integrity] Unity version changed: ${previousVersion} -> ${currentVersion}"
        fullWipe = true
    }
    // Branch changes no longer trigger a wipe — per-branch cache symlinks (setupBranchCaches,
    // called below) isolate each branch's BuildCache/Bee/IL2CPP/Addressables, so switching
    // branches just repoints the links instead of rebuilding everything from scratch.
    if (previousBranch && previousBranch != currentBranch) {
        echo "[Cache Integrity] Branch changed ${previousBranch} -> ${currentBranch} (per-branch caches re-linked, no wipe)"
    }

    if (fullWipe) {
        echo "========================================"
        echo "AUTO-CLEANING UNITY CACHE (Unity version change / first build)"
        echo "========================================"
        // On-disk cache formats can differ between editor versions, so wipe the shared Library
        // caches (preserving ArtifactDB/SourceAssetDB to avoid a full reimport) AND the entire
        // per-branch cache store for this job (all branches) since the linked caches live there.
        cleanUnityCache(unityProjectPath, 'ShaderCache,ScriptAssemblies,PackageCache,Temp', true)
        sh "rm -rf \"\${HOME}/.buildtools/unitycache/${jobName}\" || true"
        echo "[Cache Integrity] Cleared per-branch cache store for ${jobName}"
    } else {
        echo "[Cache Integrity] No version change, incremental caches valid"
    }

    // Always (re)establish the per-branch cache symlinks for the current branch
    setupBranchCaches(unityProjectPath)
}

/**
 * Saves current build info (Unity version + branch) to a persistent marker file.
 * Called on successful builds so the next build can detect configuration changes.
 */
def saveBuildInfo() {
    def jobName = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    def markerFile = "${env.HOME}/.buildtools/.lastbuild_${jobName}"
    def currentVersion = env.UNITY_VERSION ?: ''
    def currentBranch = env.PLASTICSCM_BRANCH ?: env.BRANCH ?: ''

    sh(script: """
        mkdir -p "\${HOME}/.buildtools"
        cat > '${markerFile}' << 'MARKER_EOF'
unity_version=${currentVersion}
branch=${currentBranch}
MARKER_EOF
    """, returnStatus: true)
    echo "[INFO] Saved build info: Unity ${currentVersion} on ${currentBranch}"
}

/**
 * Purge entire Jenkins workspace if "Clear Workspace" is selected.
 * Call this BEFORE checkout so the workspace is wiped clean before repopulating.
 * Returns true if workspace was purged, false otherwise.
 */
def purgeWorkspace(String cleanCache) {
    if (!cleanCache) return false
    def cacheTypes = cleanCache.split(',').collect { it.trim() }
    if (!cacheTypes.contains('Clear Workspace')) return false

    echo "========================================"
    echo "CLEAR WORKSPACE - PURGING ENTIRE WORKSPACE"
    echo "Selected: ${cleanCache}"
    echo "Build will proceed in 60 seconds - abort now to cancel"
    echo "========================================"
    try {
        timeout(time: 60, unit: 'SECONDS') {
            input message: "Cache clean requested: ${cleanCache}\nClick 'Proceed' to start immediately, or wait 60s.",
                  ok: 'Proceed'
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        def rejected = e.causes?.find { it instanceof org.jenkinsci.plugins.workflow.support.steps.input.Rejection }
        if (rejected) {
            error "[ABORTED] Cache clean cancelled by user"
        }
        echo "[INFO] Timeout expired, proceeding with cache clean..."
    }
    def jobKey = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    sh """
        rm -rf "${env.WORKSPACE}"/*
        rm -rf "${env.WORKSPACE}"/.[!.]* || true
        echo "[OK] Clear Workspace complete - workspace purged"
    """
    // Also wipe DerivedData and the per-branch cache store (lives in \$HOME, survives the
    // workspace purge) since we're going all-in
    sh "rm -rf \"\${HOME}/Library/Developer/Xcode/DerivedData\" || true"
    sh "rm -rf \"\${HOME}/.buildtools/unitycache/${jobKey}\" || true"
    return true
}

/**
 * Library cache dirs that get per-branch isolation via symlinks into a persistent store
 * under $HOME/.buildtools/unitycache/<job>/<branch>. These are the expensive content-addressed
 * build caches — keeping one copy per branch means switching branches just repoints the symlink
 * instead of thrashing (and rebuilding) the other branch's cache. The map key is the CLEAN_CACHE
 * token; the value is the actual Library subfolder name.
 */
def branchLinkedCaches() {
    return [BuildCache: 'BuildCache', Bee: 'Bee', IL2CPP: 'il2cpp_cache', Addressables: 'com.unity.addressables']
}

def branchCacheStore() {
    def jobKey = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    def branch = env.PLASTICSCM_BRANCH ?: env.BRANCH
    if (!branch) return null
    def branchKey = branch.replaceAll('[^a-zA-Z0-9_-]', '_')
    return "${env.HOME}/.buildtools/unitycache/${jobKey}/${branchKey}"
}

/**
 * Shell function injected into cache-cleanup sh blocks. Plain `rm -rf` fails with ENOTEMPTY
 * ('Directory not empty') when a nested dir is read-only (Unity's Bee build backend writes its
 * cache that way) or a file carries a BSD immutable flag / read-only perms (Xcode output).
 * force_clean strips flags+perms and retries; a symlink is unlinked WITHOUT recursing into its
 * target (so we never chmod the real cache store behind a per-branch link).
 */
def forceCleanShellFn() {
    return '''force_clean() {
    for target in "$@"; do
        [ -e "$target" ] || [ -L "$target" ] || continue
        if [ -L "$target" ]; then rm -f "$target"; continue; fi
        chflags -R nouchg "$target" 2>/dev/null || true
        chmod -R u+rwx "$target" 2>/dev/null || true
        rm -rf "$target" 2>/dev/null && continue
        sleep 1
        chmod -R u+rwx "$target" 2>/dev/null || true
        rm -rf "$target"
    done
}'''
}

/**
 * (Re)point per-branch cache symlinks for the current branch. Idempotent — safe to call every
 * build. Creates the store on first use, so a brand-new branch starts with an empty (cold) cache
 * and is fully incremental on subsequent builds of that branch.
 */
def setupBranchCaches(String unityProjectPath) {
    def store = branchCacheStore()
    if (!store) {
        echo "[Branch Cache] Branch unknown — skipping per-branch cache links"
        return
    }
    def library = "${unityProjectPath}/Library"
    def caches = branchLinkedCaches().values().join(' ')
    sh """
        set -e
        ${forceCleanShellFn()}
        mkdir -p "${library}" "${store}"
        for d in ${caches}; do
            mkdir -p "${store}/\$d"
            link="${library}/\$d"
            # Drop any existing link OR a stale real dir (e.g. a read-only Bee cache from a
            # pre-symlink build) before re-linking. force_clean handles both.
            force_clean "\$link"
            ln -s "${store}/\$d" "\$link"
            echo "[Branch Cache] linked \$d"
        done
    """
}

def cleanUnityCache(String unityProjectPath, String cleanCache, boolean skipConfirmation = false) {
    if (!cleanCache || cleanCache == 'None') return

    // Parse comma-separated list of cache types
    def cacheTypes = cleanCache.split(',').collect { it.trim() }

    // Clear Workspace already handled by purgeWorkspace() before checkout
    if (cacheTypes.contains('Clear Workspace')) return

    if (skipConfirmation) {
        echo "[INFO] Auto-cleaning cache: ${cleanCache}"
    } else {
        echo "========================================"
        echo "CACHE CLEAN: ${cleanCache}"
        echo "Build will proceed in 60 seconds - abort now to cancel"
        echo "========================================"
        try {
            timeout(time: 60, unit: 'SECONDS') {
                input message: "Cache clean requested: ${cleanCache}\nClick 'Proceed' to start immediately, or wait 60s.",
                      ok: 'Proceed'
            }
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            def rejected = e.causes?.find { it instanceof org.jenkinsci.plugins.workflow.support.steps.input.Rejection }
            if (rejected) {
                error "[ABORTED] Cache clean cancelled by user"
            }
            echo "[INFO] Timeout expired, proceeding with cache clean..."
        }
    }

    def store = branchCacheStore()
    def linked = branchLinkedCaches()

    // Clear the real data behind a per-branch cache symlink (NOT just the link, which would
    // orphan the store) and re-establish an empty link. Falls back to a plain delete if the
    // branch (and therefore the store path) isn't known.
    def clearLinked = { String token ->
        def dir = linked[token]
        if (!store) return ["force_clean \"${unityProjectPath}/Library/${dir}\""]
        def target = "${store}/${dir}"
        def link = "${unityProjectPath}/Library/${dir}"
        return [
            "force_clean \"${link}\"",
            "force_clean \"${target}\"",
            "mkdir -p \"${target}\"",
            "ln -s \"${target}\" \"${link}\""
        ]
    }

    // Clear Library - delete entire Library folder (and the whole per-branch cache store, so
    // the linked caches are cleared too rather than surviving outside the workspace)
    if (cacheTypes.contains('Clear Library')) {
        sh """
            ${forceCleanShellFn()}
            force_clean "${unityProjectPath}/Library"
            ${store ? "force_clean \"${store}\"" : "echo '[Branch Cache] store unknown, skipped'"}
            echo "[OK] Clear Library: Deleted entire Library folder and per-branch cache store"
        """
        // Re-create the per-branch cache symlinks so this build stays linked after the wipe
        setupBranchCaches(unityProjectPath)
        return
    }

    // Build shell commands for each cache type
    def commands = []
    def libraryVar = '\$LIBRARY_PATH'
    def tempVar = '\$TEMP_PATH'

    cacheTypes.each { cacheType ->
        switch (cacheType) {
            case 'All':
                commands << "rm -rf \"${libraryVar}/ShaderCache\""
                commands.addAll(clearLinked('BuildCache'))
                commands << "rm -rf \"${libraryVar}/ArtifactDB\""
                commands << "rm -rf \"${libraryVar}/SourceAssetDB\""
                commands << "rm -rf \"${libraryVar}/ScriptAssemblies\""
                commands << "rm -rf \"${libraryVar}/PackageCache\""
                commands.addAll(clearLinked('Bee'))
                commands.addAll(clearLinked('IL2CPP'))
                commands.addAll(clearLinked('Addressables'))
                commands << "rm -rf \"${tempVar}\""
                commands << "rm -f \"${unityProjectPath}/Packages/packages-lock.json\""
                commands << "rm -rf \"\${HOME}/Library/Unity/cache/packages\""
                commands << "rm -rf \"\${HOME}/Library/Unity/cache/upm\""
                commands << "force_clean \"\${HOME}/Library/Developer/Xcode/DerivedData\""
                break
            case 'ShaderCache':
                commands << "rm -rf \"${libraryVar}/ShaderCache\""
                break
            case 'BuildCache':
                commands.addAll(clearLinked('BuildCache'))
                break
            case 'ArtifactDB':
                commands << "rm -rf \"${libraryVar}/ArtifactDB\""
                commands << "rm -rf \"${libraryVar}/SourceAssetDB\""
                break
            case 'Temp':
                commands << "rm -rf \"${tempVar}\""
                break
            case 'ScriptAssemblies':
                commands << "rm -rf \"${libraryVar}/ScriptAssemblies\""
                break
            case 'PackageCache':
                commands << "rm -rf \"${libraryVar}/PackageCache\""
                commands << "rm -f \"${unityProjectPath}/Packages/packages-lock.json\""
                commands << "rm -rf \"\${HOME}/Library/Unity/cache/packages\""
                commands << "rm -rf \"\${HOME}/Library/Unity/cache/upm\""
                break
            case 'Bee':
                commands.addAll(clearLinked('Bee'))
                break
            case 'IL2CPP':
                commands.addAll(clearLinked('IL2CPP'))
                break
            case 'Addressables':
                commands.addAll(clearLinked('Addressables'))
                break
            case 'DerivedData':
                commands << "force_clean \"\${HOME}/Library/Developer/Xcode/DerivedData\""
                break
            case 'Provisioning Profiles':
                // Purge cached provisioning profiles so the next build re-fetches fresh ones —
                // fixes stale/expired managed profiles left on this agent (see archiveXcodeProject).
                commands << "force_clean \"\${HOME}/Library/MobileDevice/Provisioning Profiles\""
                commands << "mkdir -p \"\${HOME}/Library/MobileDevice/Provisioning Profiles\""
                break
            case 'Xcode Archives':
                // Remove accumulated Xcode archives (~/Library/Developer/Xcode/Archives).
                commands << "force_clean \"\${HOME}/Library/Developer/Xcode/Archives\""
                break
            case 'None':
            case '----------':
                // Skip these
                break
            default:
                echo "[WARN] Unknown cache type: ${cacheType}"
        }
    }

    if (commands) {
        sh """
            ${forceCleanShellFn()}
            LIBRARY_PATH="${unityProjectPath}/Library"
            TEMP_PATH="${unityProjectPath}/Temp"

            ${commands.join('\n            ')}
            echo "[OK] Cleaned cache types: ${cacheTypes.join(', ')}"
        """
    }

    echo "Unity cache cleanup complete"
}

/**
 * Run a one-off pre-build shell script supplied via the PREBUILD_SCRIPT job
 * parameter. Runs on the agent in the workspace after checkout but before Unity
 * opens — the place to clear stray agent-only state that a clean repo can't fix.
 *
 * Best-effort by default: a non-zero exit is logged as a warning and the build
 * continues. Set PREBUILD_FAIL_ON_ERROR=true (env) to make a failure abort.
 */
def runPrebuildScript(String script) {
    if (!script?.trim()) {
        echo "[Pre-Build] No PREBUILD_SCRIPT provided, skipping"
        return
    }

    echo "========== PRE-BUILD SCRIPT (bash) =========="
    echo script
    echo "============================================="

    def scriptFile = "${env.WORKSPACE}/prebuild_script.sh"
    writeFile file: scriptFile, text: script
    try {
        def exitCode = sh(script: "bash '${scriptFile}'", returnStatus: true)

        if (exitCode != 0) {
            if (env.PREBUILD_FAIL_ON_ERROR == 'true') {
                error "[Pre-Build] Script failed with exit code ${exitCode} (PREBUILD_FAIL_ON_ERROR=true)"
            }
            echo "[Pre-Build] [WARN] Script exited with code ${exitCode} — continuing (set PREBUILD_FAIL_ON_ERROR=true to abort on failure)"
        } else {
            echo "[Pre-Build] Script completed successfully"
        }
    } finally {
        sh(script: "rm -f '${scriptFile}'", returnStatus: true)
    }
}

/**
 * Extract and print Unity errors/exceptions from the recent console log.
 * Called inline from runUnityCommand before it throws, so the summary
 * appears directly in the failed stage's output.
 */
def printUnityErrors(int tailLines = 10000) {
    try {
        def logLines = currentBuild.rawBuild.getLog(tailLines)
        def errors = common.extractErrorLines(logLines)

        if (errors) {
            echo "========== UNITY ERRORS & EXCEPTIONS =========="
            echo errors.join('\n')
            echo "================================================"

            if (env.ARTIFACT_PATH) {
                writeFile file: "${env.ARTIFACT_PATH}/unity_errors.log", text: errors.join('\n')
                common.linkErrorLog('unity_errors.log')
            }
        }
    } catch (Exception ex) {
        echo "[DEBUG] printUnityErrors: ${ex.message}"
    }
}

def runUnityCommand(Map config) {
    def unityProjectPath = config.unityProjectPath
    def platform = config.platform
    def executeMethod = config.executeMethod
    def quit = config.quit ?: false
    def buildPath = config.buildPath
    def artifactPath = config.artifactPath

    def importWorkers = config.importWorkers ?: 4

    def quitFlag = quit ? '-quit' : ''
    def importWorkersFlag = importWorkers > 0 ? "-desiredWorkerCount ${importWorkers}" : ''
    def prepareDirs = ''
    if (buildPath && artifactPath) {
        prepareDirs = """
            rm -rf "${buildPath}"
            rm -rf "${artifactPath}"
            mkdir -p "${buildPath}"
            mkdir -p "${artifactPath}"
        """
    }

    def cacheServerFlags = env.CACHE_SERVER_ENDPOINT ? "-EnableCacheServer -cacheServerEndpoint ${env.CACHE_SERVER_ENDPOINT}" : ''

    def unityExe = "${getUnityInstallDir(env.UNITY_VERSION)}/Unity.app/Contents/MacOS/Unity"
    def unityArgs = "-projectPath '${unityProjectPath}' ${cacheServerFlags} -batchmode -username \"\$UNITY_USERNAME\" -password \"\$UNITY_PASSWORD\" -buildTarget ${platform} ${quitFlag} ${importWorkersFlag} -executeMethod ${executeMethod} -logFile - -skipMissingProjectID -skipMissingUPID -accept-apiupdate -disable-assembly-updater"

    def exitCode = sh(script: """
        ${prepareDirs}
        echo "[INFO] Running Unity: ${executeMethod}"
        ${unityExe} ${unityArgs}
    """, returnStatus: true)

    if (exitCode != 0) {
        // Check if this is a UPM (Package Manager) crash — exit code 101 or IPC failures.
        // Clearing UPM caches and retrying usually fixes it.
        def logSnippet = currentBuild.rawBuild.getLog(5000).join('\n')
        def isUpmCrash = logSnippet.contains('Server process stopped with exit code') ||
                         logSnippet.contains('Failed to resolve packages') ||
                         logSnippet.contains('IPC stream failed to read')

        if (isUpmCrash) {
            echo "========================================"
            echo "[RETRY] UPM crash detected — clearing package caches and retrying..."
            echo "========================================"
            sh """
                rm -rf '${unityProjectPath}/Library/PackageCache'
                rm -f '${unityProjectPath}/Packages/packages-lock.json'
                rm -rf "\${HOME}/Library/Unity/cache/packages"
                rm -rf "\${HOME}/Library/Unity/cache/upm"
                echo "[OK] UPM caches cleared"
            """

            sh """
                ${prepareDirs}
                echo "[INFO] Retrying Unity: ${executeMethod}"
                ${unityExe} ${unityArgs}
            """
        } else {
            printUnityErrors()
            common.failWithReportedBuildErrors()
            error "[ERROR] Unity command failed with exit code ${exitCode}"
        }
    }
}

/**
 * Scan the Jenkins console log for Unity errors and exceptions from Prepare/Build stages.
 * Writes them to unity_errors.log in the artifact path and prints a summary.
 * Call from a post { always {} } block so it runs even on failure.
 *
 * @param stageNames  List of stage names to scan (default: Unity Prepare + Unity Build)
 */
def collectUnityErrors(List stageNames = ['Unity Prepare', 'Unity Build']) {
    def allErrors = []

    stageNames.each { stageName ->
        def stageLog = common.getStageLogsFromRawLog(stageName, 50000)
        if (!stageLog) return

        def stageErrors = common.extractErrorLines(stageLog.readLines())
        if (stageErrors) {
            allErrors << "===== ${stageName} ====="
            allErrors.addAll(stageErrors)
            allErrors << ''
        }
    }

    if (allErrors) {
        def errorText = allErrors.join('\n')

        if (env.ARTIFACT_PATH) {
            writeFile file: "${env.ARTIFACT_PATH}/unity_errors.log", text: errorText
            common.linkErrorLog('unity_errors.log')
        }

        echo "========== UNITY ERRORS & EXCEPTIONS =========="
        echo errorText
        echo "================================================"
        echo "[INFO] ${allErrors.findAll { it =~ /\[Error\]|\[Exception\]|Exception:/ }.size()} error/exception lines collected"
    } else {
        echo "[OK] No Unity errors or exceptions detected"
    }
}

def runUnityTests(Map config) {
    def unityProjectPath = config.unityProjectPath
    def testPlatform = config.testPlatform ?: 'EditMode'
    def buildTarget = config.buildTarget ?: 'iOS'
    def testResults = config.testResults ?: "${env.ARTIFACT_PATH}/TestResults.xml"
    def testFilter = config.testFilter ?: ''
    def testCategory = config.testCategory ?: ''

    def filterArg = testFilter ? "-testFilter \\\"${testFilter}\\\"" : ''
    def categoryArg = testCategory ? "-testCategory \\\"${testCategory}\\\"" : ''
    def unityExe = "${getUnityInstallDir(env.UNITY_VERSION)}/Unity.app/Contents/MacOS/Unity"

    sh """
        mkdir -p "${env.ARTIFACT_PATH}"
        echo "[INFO] Running Unity ${testPlatform} tests (target: ${buildTarget})"
        ${unityExe} \\
            -projectPath "${unityProjectPath}" \\
            -batchmode \\
            -buildTarget ${buildTarget} \\
            -runTests \\
            -testPlatform ${testPlatform} \\
            -testResults "${testResults}" \\
            ${filterArg} \\
            ${categoryArg} \\
            -logFile - \\
            -skipMissingProjectID \\
            -skipMissingUPID
        echo "[OK] Unity tests completed"
    """
}

def getBuildJobWorkspace(String platformSuffix) {
    def base = env.BUILD_JOB_BASE
    if (!base) error "BUILD_JOB_BASE env var not set - configure in Jenkins job environment"
    def jobName = "${base}_${platformSuffix}"
    def wsRoot = "${env.WORKSPACE}/.."
    return "${wsRoot}/${jobName}"
}

// ============================================================================
// XCODE BUILD FUNCTIONS
// ============================================================================

def buildXcodeProject(Map config) {
    def xcodePath = config.xcodePath
    def scheme = config.scheme ?: 'Unity-iPhone'
    def configuration = config.configuration ?: 'Release'
    def archivePath = config.archivePath
    def exportPath = config.exportPath
    def exportOptionsPlist = config.exportOptionsPlist

    sh """
        echo "[INFO] Building Xcode project..."
        PRETTY="cat"
        command -v xcpretty >/dev/null 2>&1 && PRETTY="xcpretty"
        xcodebuild -project "${xcodePath}/Unity-iPhone.xcodeproj" \\
            -scheme "${scheme}" \\
            -configuration "${configuration}" \\
            -archivePath "${archivePath}" \\
            clean archive \\
            CODE_SIGN_STYLE="Manual" \\
            | \$PRETTY
    """

    sh """
        echo "[INFO] Exporting IPA..."
        PRETTY="cat"
        command -v xcpretty >/dev/null 2>&1 && PRETTY="xcpretty"
        xcodebuild -exportArchive \\
            -archivePath "${archivePath}" \\
            -exportPath "${exportPath}" \\
            -exportOptionsPlist "${exportOptionsPlist}" \\
            | \$PRETTY
    """
}

// ============================================================================
// SCM FUNCTIONS
// ============================================================================

/**
 * Clean Plastic SCM workspace by removing private (untracked) files.
 * Call this BEFORE checkout to ensure a clean workspace state.
 * The checkout itself will overwrite any modified tracked files, so we only
 * need to remove private files that the checkout won't touch.
 *
 * @param workspacePath Path to the Plastic workspace (defaults to WORKSPACE/plastic)
 */
def cleanPlasticWorkspace(String cleanCache = null, String workspacePath = null) {
    def wsPath = workspacePath ?: "${env.WORKSPACE}/plastic"
    def cacheTypes = cleanCache?.split(',')?.collect { it.trim() } ?: []
    def doVerify = cacheTypes.contains('Verify Workspace')

    // Single consolidated sh call: check status, undo changes, remove private files, optional verify
    def result = sh(script: """#!/bin/bash
if [ ! -d '${wsPath}/.plastic' ]; then
    echo "  No Plastic workspace found at ${wsPath}" >&2
    echo "NO_WORKSPACE"
    exit 0
fi
echo "Cleaning Plastic workspace: ${wsPath}" >&2
cd '${wsPath}'
# Count changed files
CHANGED=0
CHANGED_OUTPUT=\$(cm status --changed --short 2>/dev/null || true)
if [ -n "\$CHANGED_OUTPUT" ]; then
    CHANGED=\$(echo "\$CHANGED_OUTPUT" | grep -c '.' || echo 0)
fi
echo "  Changed files: \$CHANGED" >&2
# Count and list locally deleted files
DELETED=0
DELETED_OUTPUT=\$(cm status --localdeleted --short 2>/dev/null || true)
if [ -n "\$DELETED_OUTPUT" ]; then
    DELETED=\$(echo "\$DELETED_OUTPUT" | grep -c '.' || echo 0)
    echo "\$DELETED_OUTPUT" | while IFS= read -r line; do
        [ -n "\$line" ] && echo "  Locally deleted: \$line" >&2
    done
fi
[ \$DELETED -gt 0 ] && echo "  Locally deleted total: \$DELETED" >&2
# Undo changes if any
if [ \$CHANGED -gt 0 ] || [ \$DELETED -gt 0 ]; then
    echo "  Running cm undo . -r ..." >&2
    if ! cm undo . -r; then
        echo "UNDO_FAILED"
        exit 1
    fi
    echo "  Reverted \$CHANGED changed + \$DELETED locally deleted file(s)" >&2
else
    echo "  No changes to undo" >&2
fi
# Remove private files (files only, skip directories to avoid nuking tracked content)
PRIVATE=0
PRIVATE_OUTPUT=\$(cm status --private --short --cutignored 2>/dev/null || true)
if [ -n "\$PRIVATE_OUTPUT" ]; then
    echo "\$PRIVATE_OUTPUT" | while IFS= read -r line; do
        [ -z "\$line" ] && continue
        if [ -f "\$line" ]; then
            rm -f "\$line"
            echo "  Deleted: \$(basename "\$line")" >&2
        elif [ -d "\$line" ]; then
            echo "  Skipped dir: \$(basename "\$line")" >&2
        fi
    done
    PRIVATE=\$(echo "\$PRIVATE_OUTPUT" | grep -c '.' || echo 0)
fi
[ \$PRIVATE -gt 0 ] && echo "  Removed \$PRIVATE private items" >&2 || echo "  No private files" >&2
${doVerify ? """# Verify workspace file integrity
echo "  Verifying workspace file integrity (cm update --forced)..." >&2
if cm update --forced --silent 1>&2; then
    echo "  Workspace file integrity verified" >&2
else
    echo "  WARNING: cm update --forced returned non-zero; continuing" >&2
fi""" : ''}
echo "Plastic workspace cleanup complete" >&2
echo "CLEANUP_DONE"
echo "\$CHANGED"
echo "\$DELETED"
echo "\$PRIVATE"
""", returnStdout: true).trim()

    echo "  plasticCleanup stdout: ${result}"
    def lines = result.readLines().collect { it.trim() }.findAll { it }
    def status = lines[0]

    if (status == 'NO_WORKSPACE') {
        echo "[INFO] No Plastic workspace found at ${wsPath}, skipping cleanup"
        return
    }

    if (status == 'UNDO_FAILED') {
        error("[ERROR] Failed to undo workspace changes. Workspace may be corrupted - check Plastic SCM status on this agent.")
    }

    def changed = lines.size() > 1 ? lines[1] : '0'
    def deleted = lines.size() > 2 ? lines[2] : '0'
    def privates = lines.size() > 3 ? lines[3] : '0'

    echo "[OK] Plastic workspace cleanup complete (changed: ${changed}, deleted: ${deleted}, private: ${privates})"
}

/**
 * Deregister a Plastic SCM workspace if it exists but points to a stale path
 * (e.g. an @2 directory from a previous customWorkspace collision).
 * This allows cm workspace create to succeed with the same name at the correct path.
 */
def _deregisterStalePlasticWorkspace(String wsName) {
    try {
        def wsList = sh(script: "cm workspace list --format='{wkname}#{path}'", returnStdout: true).trim()
        for (line in wsList.split('\n')) {
            line = line.trim()
            if (!line.contains('#')) continue
            def parts = line.split('#', 2)
            def name = parts[0].trim()
            if (name == wsName) {
                def path = parts[1].trim()
                echo "[Checkout] Found stale workspace '${wsName}' at ${path} — deregistering"
                sh(script: "cm workspace delete '${wsName}'", returnStatus: true)
                break
            }
        }
    } catch (Exception e) {
        echo "[DEBUG] Could not check for stale workspace '${wsName}': ${e.message}"
    }
}

/**
 * Checkout from Plastic SCM using cm switch instead of the Jenkins plugin checkout.
 * This avoids the plugin trying to delete/recreate the workspace directory, which
 * fails when file locks exist.
 *
 * @param config Map with keys:
 *   - branch: Branch path (e.g., "/main") - used if changeset is null
 *   - changeset: Changeset ID (e.g., "9613") - takes priority over branch
 *   - repSpec: Repository spec (defaults to env.PLASTIC_REPSPEC)
 * @return Map of SCM variables: PLASTICSCM_CHANGESET_ID, PLASTICSCM_BRANCH,
 *         PLASTICSCM_AUTHOR, PLASTICSCM_CHANGESET_GUID
 */
def plasticCheckout(Map config) {
    def branch = config.branch
    def changeset = config.changeset
    def repSpec = config.repSpec ?: env.PLASTIC_REPSPEC
    def wsDir = "${env.WORKSPACE}/plastic"

    // 1. Ensure Plastic workspace exists and is registered
    def hasWorkspace = sh(script: "[ -d '${wsDir}/.plastic' ] && cd '${wsDir}' && cm status > /dev/null 2>&1 && echo true || echo false", returnStdout: true).trim()

    if (hasWorkspace != 'true') {
        sh "mkdir -p '${wsDir}'"
        def safeName = env.JOB_NAME.replaceAll('[^a-zA-Z0-9_-]', '_')
        def wsName = "ci_${env.NODE_NAME}_${safeName}"

        // Deregister any stale workspace with the same name (e.g. pointing to an old @2 path)
        _deregisterStalePlasticWorkspace(wsName)

        def createResult = sh(script: "cm workspace create '${wsName}' '${wsDir}' '${repSpec}'", returnStatus: true)
        if (createResult != 0) {
            // .plastic exists but unregistered — wipe it and try fresh
            echo "[Checkout] workspace create failed (orphaned .plastic?) — removing and retrying"
            sh "rm -rf '${wsDir}/.plastic'"
            def retryResult = sh(script: "cm workspace create '${wsName}' '${wsDir}' '${repSpec}'", returnStatus: true)
            if (retryResult != 0) {
                // Name conflict from previous agent - retry with executor suffix
                wsName = "${wsName}_${env.EXECUTOR_NUMBER ?: '0'}"
                _deregisterStalePlasticWorkspace(wsName)
                sh "cm workspace create '${wsName}' '${wsDir}' '${repSpec}'"
            }
        }
    }

    // 2. Undo any pending changes left from a previous build (prevents switch failure)
    sh "cd '${wsDir}' && cm undo . -r"

    // 3. Switch to desired changeset or branch
    if (changeset) {
        echo "[Checkout] Switching to changeset ${changeset}"
        sh "cd '${wsDir}' && cm switch cs:${changeset} --noinput"
    } else if (branch) {
        echo "[Checkout] Switching to branch ${branch}"
        sh "cd '${wsDir}' && cm switch 'br:${branch}' --noinput"
    } else {
        error "[Checkout] Either 'branch' or 'changeset' must be specified"
    }

    // 4. Get loaded changeset ID from workspace status
    //    cm status --header --machinereadable returns: STATUS <csId> <repo> <server>
    def statusOutput = sh(
        script: "cd '${wsDir}' && cm status --header --machinereadable",
        returnStdout: true
    ).trim()
    def statusParts = statusOutput.split(/\s+/)
    def csId = statusParts.length > 1 ? statusParts[1] : null
    if (!csId) error "[Checkout] Could not determine loaded changeset from: ${statusOutput}"

    // 5. Query changeset details (branch, author, GUID)
    def csInfo = sh(
        script: """cm find changeset "where changesetid=${csId}" --format="{changesetid}#{branch}#{owner}#{guid}" --nototal on repository "'${repSpec}'" """,
        returnStdout: true
    ).trim()
    if (!csInfo) error "[Checkout] Could not query details for changeset ${csId}"

    def parts = csInfo.split('#')
    def result = [
        PLASTICSCM_CHANGESET_ID: parts[0]?.trim(),
        PLASTICSCM_BRANCH: parts.length > 1 ? parts[1]?.trim() : '',
        PLASTICSCM_AUTHOR: parts.length > 2 ? parts[2]?.trim() : '',
        PLASTICSCM_CHANGESET_GUID: parts.length > 3 ? parts[3]?.trim() : ''
    ]

    echo "[OK] Loaded changeset ${result.PLASTICSCM_CHANGESET_ID} on ${result.PLASTICSCM_BRANCH} by ${result.PLASTICSCM_AUTHOR}"
    return result
}

/**
 * Get branch name from a changeset ID using cm find
 */
/**
 * Resolve branch and latest changeset from user input BEFORE checkout.
 * Uses cm find against the repo server — no workspace needed.
 */
def resolveTargetChangeset(String branch, String changeset) {
    def repo = env.PLASTIC_REPSPEC
    if (!repo) return [branch: branch, changeset: changeset]
    try {
        if (changeset) {
            def info = sh(
                script: """cm find changeset "where changesetid=${changeset}" --format="{branch}#{changesetid}" --nototal on repository "'${repo}'" """,
                returnStdout: true
            ).trim()
            def parts = info.split('#')
            return [branch: parts[0]?.trim() ?: branch, changeset: parts.length > 1 ? parts[1]?.trim() : changeset]
        } else if (branch) {
            def info = sh(
                script: """cm find changeset "where branch='${branch}' order by changesetid desc limit 1" --format="{branch}#{changesetid}" --nototal on repository "'${repo}'" """,
                returnStdout: true
            ).trim()
            def parts = info.split('#')
            return [branch: parts[0]?.trim() ?: branch, changeset: parts.length > 1 ? parts[1]?.trim() : null]
        }
    } catch (Exception e) {
        echo "[DEBUG] Could not resolve target changeset: ${e.message}"
    }
    return [branch: branch, changeset: changeset]
}

def getBranchFromChangeset(String changeset, String workspacePath = null) {
    try {
        def repo = workspacePath ?: env.PLASTIC_REPSPEC
        def branch = sh(
            script: """cm find changeset "where changesetid=${changeset}" --format="{branch}" --nototal on repository "'${repo}'" """,
            returnStdout: true
        ).trim()
        return branch ?: null
    } catch (Exception e) {
        echo "[WARN] Failed to get branch from changeset ${changeset}: ${e.message}"
        return null
    }
}

def getPlasticChangeHistory(String branch, int count = 3, String maxChangeset = null) {
    try {
        def whereClause = "where branch='${branch}'"
        if (maxChangeset) {
            whereClause += " and changesetid <= ${maxChangeset}"
        }
        def historyOutput = sh(
            script: """cd "\${WORKSPACE}/plastic" && cm find changesets "${whereClause} order by changesetid desc limit ${count}" --format="{changesetid}|{owner}|{comment}" --nototal""",
            returnStdout: true
        ).trim()

        def changes = historyOutput.split('\n').findAll { it.trim() }.collect { line ->
            def parts = line.split('\\|', 3)
            if (parts.size() >= 3) {
                def cs = parts[0].trim()
                def author = parts[1].trim().split('@')[0]
                def comment = parts[2].trim().take(60)
                if (comment.length() == 60) comment += '...'
                return "- `${cs}` ${author}: ${comment}"
            }
            return null
        }.findAll { it }

        return changes.join('\n')
    } catch (Exception e) {
        echo "Failed to get change history: ${e.message}"
        return ''
    }
}

// ============================================================================
// UPLOAD FUNCTIONS
// ============================================================================

def uploadToGoogleDrive(Map config) {
    def buildPath = config.buildPath
    def destFolder = config.destFolder
    def buildType = config.buildType

    // Ensure rclone is available (always check, don't trust env var from different stage)
    def rcloneCheck = checkRclone(true)
    if (!rcloneCheck.available) {
        error("[ERROR] rclone not available: ${rcloneCheck.message}")
    }
    def rclonePath = rcloneCheck.path
    echo "[INFO] Using rclone at: ${rclonePath}"

    // Folder link first, while nothing is transferring: after the upload only the file link
    // stands between rclone exiting and the Slack update. mkdir so the folder exists to link.
    def rawFolderLink = sh(
        script: "\"${rclonePath}\" --config \"\$RCLONE_CONFIG\" mkdir \"\$RCLONE_REMOTE/${destFolder}\" 2>&1 || true; " +
                "\"${rclonePath}\" --config \"\$RCLONE_CONFIG\" link \"\$RCLONE_REMOTE/${destFolder}\" 2>&1 || true",
        returnStdout: true
    ).trim()
    def gdriveFolderLink = rawFolderLink.split('\n').find { it.trim().startsWith('http') }?.trim() ?: ''
    if (!gdriveFolderLink && rawFolderLink) echo "[WARN] rclone folder link output (no URL found):\n${rawFolderLink.take(500)}"
    env.GDRIVE_FOLDER_LINK = gdriveFolderLink ?: ''

    // rclone re-sends the WHOLE file on every low-level retry (operations.Copy), not just the failed
    // chunk: the defaults (3 passes x 10) turned one ~630 MiB IPA into 19 GiB of uploads against a
    // quota that retrying cannot free. 3 x 3 with a minute between passes still covers a real
    // transient and lets a per-minute quota window reset. Errors go to the log file (--progress
    // keeps the console live); on failure failRcloneUpload() prints and explains them.
    def rcloneLog = "${env.ARTIFACT_PATH ?: env.WORKSPACE}/rclone_upload.log"  // under artifacts/ so post{always} archives it
    def status = sh(returnStatus: true, script: """
        DEST_PATH="\${RCLONE_REMOTE}/${destFolder}"
        echo "Uploading to: \$DEST_PATH"
        mkdir -p "\$(dirname "${rcloneLog}")" && rm -f "${rcloneLog}"

        cd "${buildPath}"
        FILE=\$(ls *.ipa | head -1)

        if [ -z "\$FILE" ]; then
            echo "[WARNING] No IPA file found in output directory, skipping upload"
            exit 0
        fi

        echo "Uploading: \$FILE"
        "${rclonePath}" copy "\$FILE" "\$DEST_PATH/" \\
            --config "\$RCLONE_CONFIG" \\
            --progress \\
            --transfers=16 \\
            --buffer-size=256M \\
            --drive-chunk-size=256M \\
            --drive-upload-cutoff=256M \\
            --retries=3 \\
            --retries-sleep=60s \\
            --low-level-retries=3 \\
            --stats=10s \\
            --stats-one-line \\
            --log-file "${rcloneLog}" \\
            -v
    """)
    if (status != 0) common.failRcloneUpload(rcloneLog)

    def fileName = sh(script: "cd \"${buildPath}\" && ls *.ipa | head -1", returnStdout: true).trim()
    def fileLink = ""
    if (fileName) {
        def rawFileLink = sh(script: "\"${rclonePath}\" --config \"\$RCLONE_CONFIG\" link \"\$RCLONE_REMOTE/${destFolder}/${fileName}\" 2>&1 || true", returnStdout: true).trim()
        fileLink = rawFileLink.split('\n').find { it.trim().startsWith('http') }?.trim() ?: ''
        if (!fileLink && rawFileLink) echo "[WARN] rclone link output (no URL found):\n${rawFileLink.take(500)}"
        // Capture human-readable file size for Slack
        def sizeBytes = sh(script: "stat -f%z \"${buildPath}/${fileName}\" || echo 0", returnStdout: true).trim()
        if (sizeBytes?.isNumber() && sizeBytes != '0') {
            env.ARTIFACT_SIZE = common.formatFileSize(sizeBytes as Long)
            echo "[INFO] Artifact size: ${env.ARTIFACT_SIZE}"
        }
    }
    if (fileLink) {
        common.addDriveArtifactBadge('ipa', fileLink)
        env.GDRIVE_FILE_LINK = fileLink
    }

    // Tell Slack now - the message only needs the links, and both are in env at this point.
    common.updateUploadStatus('gdrive', 'done')

    common.addGoogleDriveLinks(gdriveFolderLink, fileLink, 'IPA', 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/apple.png')

    return [folderLink: gdriveFolderLink, fileLink: fileLink, fileName: fileName]
}

def uploadToLocalShare(Map config) {
    def buildPath = config.buildPath
    def buildType = config.buildType ?: 'Release'

    def sharePath = env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds'
    // Convert UNC path (\\server\share) to SMB URL (//server/share) for mount_smbfs
    def smbUrl = sharePath.replace('\\', '/')
    def mountPoint = '/tmp/local_builds'
    def destPath = "${mountPoint}/${env.JOB_NAME}/${buildType}/${env.VERSION}"
    def uncPath = "${sharePath}\\${env.JOB_NAME}\\${buildType}\\${env.VERSION}"

    try {
        sh """
            # Unmount stale mount if present, then remount with correct credentials
            mkdir -p "${mountPoint}"
            if mount | grep -q "${mountPoint}"; then
                echo "[INFO] Unmounting existing share at ${mountPoint}"
                umount "${mountPoint}" || true
            fi
            echo "[INFO] Mounting SMB share: ${smbUrl}"
            mount_smbfs "//BUILD:build@${smbUrl.replaceFirst('^//', '')}" "${mountPoint}" || {
                echo "[WARNING] Failed to mount SMB share at ${smbUrl}"
                exit 1
            }

            # Create destination directory
            mkdir -p "${destPath}"

            cd "${buildPath}"
            FILE=\$(ls *.ipa | head -1)

            if [ -z "\$FILE" ]; then
                echo "[WARNING] No IPA file found, skipping local share upload"
                exit 0
            fi

            echo "[INFO] Copying \$FILE to local share: ${destPath}"
            cp "\$FILE" "${destPath}/"

            echo "[INFO] Local share copy complete"
        """

        env.LOCAL_BUILD_PATH = uncPath

        // Add sidebar link
        def fileUrl = common.localShareUrl(uncPath)
        common.addSidebarLink(fileUrl, 'Local Build', 'https://img.icons8.com/fluency/48/folder-invoices--v1.png')

        // Orange "ipa" badge + "Download IPA (Local)" link to the copy that just landed, so the
        // build is grabbable while the Drive upload (which turns the badge green) is still running.
        def fileName = sh(script: "cd \"${buildPath}\" && ls *.ipa 2>/dev/null | head -1", returnStdout: true).trim()
        common.addLocalArtifactLinks(uncPath, fileName ?: null)

        common.updateUploadStatus('local', 'done')

    } catch (Exception e) {
        echo "[WARNING] Local share upload failed: ${e.message}"
        common.updateUploadStatus('local', 'failed')
    } finally {
        // Unmount the share
        sh """
            if mount | grep -q "${mountPoint}"; then
                umount "${mountPoint}" || true
            fi
        """
    }
}

// No-op on macOS; cleanup runs on the Windows agent that owns the share.
def cleanupLocalShare(String sharePath = null) { }

def uploadToTestFlight(Map config) {
    def ipaPath = config.ipaPath
    def apiKeyId = config.apiKeyId
    def apiKeyIssuerId = config.apiKeyIssuerId

    try {
        sh """
            echo "Uploading to TestFlight..."
            xcrun altool --upload-app \\
                --type ios \\
                --file "${ipaPath}" \\
                --apiKey "${apiKeyId}" \\
                --apiIssuer "${apiKeyIssuerId}"

            echo "Upload to TestFlight complete"
        """
    } catch (Exception e) {
        echo "[WARNING] TestFlight upload failed: ${e.message}"
        common.setUnstable("TestFlight upload failed")
    }
}

// ============================================================================
// CONSOLE LOG COLLECTION & FILTERING
// ============================================================================

/**
 * Collect, filter, and archive the console log for failed builds.
 * macOS version — uses sh instead of bat for external log appending.
 */
def collectFilteredConsoleLog() {
    try {
        def failureCause = ""
        try {
            def execution = currentBuild.rawBuild.getExecution()
            if (execution) {
                def causeOfFailure = execution.getCauseOfFailure()
                if (causeOfFailure) {
                    def sw = new StringWriter()
                    causeOfFailure.printStackTrace(new PrintWriter(sw))
                    failureCause = """
=== ACTUAL BUILD FAILURE EXCEPTION ===
${causeOfFailure.getClass().getName()}: ${causeOfFailure.getMessage()}

Stack trace:
${sw.toString()}
=== END EXCEPTION ===

"""
                }
            }
        } catch (Exception ex) {
            echo "[DEBUG] Could not get failure cause: ${ex.message}"
        }

        def failedStageName = env.FAILED_STAGE ?: null
        def logContent = null
        if (failedStageName) {
            logContent = common.getStageLogsFromRawLog(failedStageName, 10000)
        }
        if (!logContent) {
            def logLines = currentBuild.rawBuild.getLog(10000)
            logContent = logLines.join('\n')
        }
        logContent = failureCause + logContent
        writeFile file: 'console_log.txt', text: logContent

        // Append external tool logs (e.g. xcodebuild_archive.log)
        try {
            if (env.ARTIFACT_PATH) {
                sh """
                    find "${env.ARTIFACT_PATH}" -maxdepth 1 -name "*.log" | sort | while IFS= read -r logfile; do
                        printf '\\n\\n=== EXTERNAL LOG: %s ===\\n' "\$(basename "\$logfile")" >> console_log.txt
                        cat "\$logfile" >> console_log.txt
                    done
                """
            }
        } catch (Exception ex) {
            echo "[DEBUG] Could not append teed logs: ${ex.message}"
        }

        // Filter noise and deduplicate via Ruby script
        def filterScript = '''
require "json"

MAX_LOG_LINES = 2000

raw_log = File.read("console_log.txt", mode: "r:bom|utf-8").encode("UTF-8", invalid: :replace, undef: :replace, replace: "")

noise_patterns = [
  /^Processing \\d+% \\(\\d+\\/\\d+\\)/,
  /^DisplayProgressbar:/,
  /^Compiling shader /,
  /^\\s+Full variant space:/,
  /^\\s+After settings filtering:/,
  /^\\s+After built-in stripping:/,
  /^\\s+After scriptable stripping:/,
  /^\\s+Processed in \\d+\\.\\d+ seconds/,
  /^\\s+starting compilation\\.\\.\\./,
  /^\\s+finished in \\d+\\.\\d+ seconds\\./,
  /^\\s+Prepared data for serialisation/,
  /^Serialized binary data for shader/,
  /^\\s+(gles|vulkan|metal)\\d* \\(total internal programs:/,
  /^Assets[\\\\\\/][^\\:]+$/,
  /^'.+' Manifest:$/,
  /^Parsing manifest '/,
  /^Add manifests to package '/,
  /^name: .+ --> alias/,
  /^jenkins_[a-f0-9]+#/,
  /^sync_[a-z_]+#/,
  /\\.bundle$/,
  /^\\[Pipeline\\] (?:\\{|\\}|\\/\\/|echo|script|stage|withEnv|withCredentials|timeout|node|libraryResource|isUnix|bat|sh)$/,
  /^\\s*$/,
  /^[-=]{20,}$/,
  /warning (?:CS|UDR|UNT)\\d+:/,
  /^(?:Version Handler|External Dependency Manager|Resolving |Constraint )/,
  /^(?:Opening scene|Unloading \\d+|Memory consumption)/,
  /^\\s+(?:Deserialize|Integration|Thread Wait Time|Loaded Objects|Unused Serialized files):/,
  /^(?:CompileC|CompileSwift|Ld|Touch|CpResource|CodeSign|ProcessInfoPlistFile|GenerateDSYMFile|PhaseScriptExecution) /,
  /^    (?:\\/Applications\\/Xcode|cd |export |builtin-)/,
  /^Shader warning in /,
  /^file: Assets/,
  /^Current files:$/,
  /: editor enabled (?:True|False), build targets/,
]

filtered = raw_log.lines.reject { |line| noise_patterns.any? { |p| line.strip.match?(p) } }

deduped = []
repeat_count = 0
filtered.each do |line|
  if deduped.last == line
    repeat_count += 1
  else
    if repeat_count > 0
      deduped << "  [repeated #{repeat_count} more time#{'s' if repeat_count > 1}]\\n"
      repeat_count = 0
    end
    deduped << line
  end
end
deduped << "  [repeated #{repeat_count} more time#{'s' if repeat_count > 1}]\\n" if repeat_count > 0

output = deduped.last(MAX_LOG_LINES).join
File.write("console_log_filtered.txt", output)
STDERR.puts "[INFO] Filtered #{filtered.size} -> #{deduped.size} lines (saved last #{[MAX_LOG_LINES, deduped.size].min})"
'''
        writeFile file: 'filter_log.rb', text: filterScript
        sh script: 'ruby filter_log.rb 2>&1', returnStatus: true

        def filteredLog = null
        if (fileExists('console_log_filtered.txt')) {
            filteredLog = readFile('console_log_filtered.txt')
            if (env.ARTIFACT_PATH) {
                writeFile file: "${env.ARTIFACT_PATH}/console_log.txt", text: filteredLog
                echo "[OK] Archived filtered console log (${filteredLog.readLines().size()} lines)"
            }
        } else {
            echo "[WARN] Log filtering failed - archiving unfiltered log"
            if (env.ARTIFACT_PATH && fileExists('console_log.txt')) {
                sh script: "cp console_log.txt '${env.ARTIFACT_PATH}/console_log.txt' 2>/dev/null", returnStatus: true
            }
        }

        return filteredLog
    } catch (Exception e) {
        echo "[WARN] Console log collection failed: ${e.message}"
        return null
    }
}

// ============================================================================
// NODE.JS
// ============================================================================

/**
 * Check if Node.js/npm is available, optionally auto-install via Homebrew
 */
def checkNodeJS(boolean autoInstall = false) {
    // Check PATH first
    try {
        def version = sh(script: 'node --version 2>/dev/null', returnStdout: true).trim()
        def npmVersion = sh(script: 'npm --version 2>/dev/null', returnStdout: true).trim()
        if (version) {
            return [available: true, message: "Node.js ${version}, npm ${npmVersion}"]
        }
    } catch (Exception e) {
        // Not in PATH
    }

    // Check common install locations
    def locations = ['/opt/homebrew/bin/node', '/usr/local/bin/node']
    for (loc in locations) {
        def exists = sh(script: "[ -f '${loc}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (exists == 'found') {
            def dir = loc.replace('/node', '')
            env.PATH = "${dir}:${env.PATH}"
            def version = sh(script: "'${loc}' --version 2>/dev/null || echo unknown", returnStdout: true).trim()
            echo "[INFO] Found Node.js at ${dir}, added to PATH"
            return [available: true, message: "Node.js ${version} (found at ${dir})"]
        }
    }

    if (autoInstall) {
        return installNodeJS()
    }

    return [
        available: false,
        message: 'Node.js/npm not installed',
        installInstructions: 'brew install node'
    ]
}

/**
 * Install Node.js via Homebrew
 */
def installNodeJS() {
    try {
        echo "[INFO] Installing Node.js via Homebrew..."
        sh 'brew install node'
        def version = sh(script: 'node --version 2>/dev/null || echo unknown', returnStdout: true).trim()
        return [available: true, installed: true, message: "Node.js ${version} installed via Homebrew"]
    } catch (Exception e) {
        return [available: false, installed: false, message: "Installation failed: ${e.message}"]
    }
}

// ============================================================================
// FIREBASE CLI
// ============================================================================

/**
 * Check if Firebase CLI is available
 */
def checkFirebaseCLI() {
    // Check PATH
    try {
        def version = sh(script: 'firebase --version 2>/dev/null', returnStdout: true).trim()
        if (version && version ==~ /^\d+\..*/) {
            return [available: true, message: "Firebase CLI ${version}"]
        } else if (version) {
            echo "[DEBUG] firebase --version returned non-version output: ${version} — CLI may be corrupt"
        }
    } catch (Exception e) {
        // Not in PATH
    }

    // Check common npm global locations
    def locations = [
        "${env.HOME}/.npm-global/bin/firebase",
        '/usr/local/bin/firebase',
        '/opt/homebrew/bin/firebase'
    ]
    for (loc in locations) {
        def exists = sh(script: "[ -f '${loc}' ] && echo found || echo notfound", returnStdout: true).trim()
        if (exists == 'found') {
            def version = sh(script: "'${loc}' --version 2>/dev/null || echo unknown", returnStdout: true).trim()
            if (version && version ==~ /^\d+\..*/) {
                env.FIREBASE_CMD = loc
                echo "[OK] Firebase CLI ${version} at ${loc}"
                return [available: true, message: "Firebase CLI ${version} (found at ${loc})"]
            } else {
                echo "[DEBUG] ${loc} --version returned '${version}' — CLI may be corrupt"
            }
        }
    }

    return [
        available: false,
        message: 'Firebase CLI not installed'
    ]
}

/**
 * Preflight check for Firebase CLI and Crashlytics configuration
 *
 * Validates Node.js, Firebase CLI, and extracts Firebase App ID from:
 *   - GoogleService-Info.plist (iOS — preferred)
 *   - google-services.json (Android — fallback)
 *   - GOOGLE_SERVICES_CREDENTIAL_ID Jenkins credential
 *
 * Sets env.FIREBASE_APP_ID for use by uploadCrashlyticsSymbols()
 */
def preflightFirebaseCLI() {
    // Skip if Crashlytics upload is not enabled
    if (env.UPLOAD_CRASHLYTICS_SYMBOLS != 'true') {
        echo "[INFO] UPLOAD_CRASHLYTICS_SYMBOLS not enabled, skipping Firebase CLI preflight"
        return
    }

    // Check Node.js/npm is available (required for Firebase CLI)
    def nodeCheck = checkNodeJS(true)
    if (!nodeCheck.available) {
        if (nodeCheck.installed) {
            error """[ERROR] Node.js was just installed but requires shell restart to update PATH

Run on the Jenkins agent:
  brew install node"""
        }
        error """[ERROR] Node.js/npm not available

Run on the Jenkins agent:
  brew install node
  npm install -g firebase-tools"""
    }
    echo "[OK] ${nodeCheck.message}"

    // Extract Firebase App ID from config files
    // Try Jenkins credential first, then check common Unity project locations
    def firebaseAppId = null
    def configSource = null

    // Check for Jenkins secret file credential
    def credentialId = env.GOOGLE_SERVICES_CREDENTIAL_ID
    if (credentialId) {
        try {
            withCredentials([file(credentialsId: credentialId, variable: 'GOOGLE_SERVICES_FILE')]) {
                def filename = sh(script: "basename \"\$GOOGLE_SERVICES_FILE\"", returnStdout: true).trim()
                if (filename.endsWith('.plist')) {
                    firebaseAppId = sh(script: "/usr/libexec/PlistBuddy -c 'Print :GOOGLE_APP_ID' \"\$GOOGLE_SERVICES_FILE\" 2>/dev/null", returnStdout: true).trim()
                } else {
                    def content = readFile(env.GOOGLE_SERVICES_FILE)
                    def match = content =~ /"mobilesdk_app_id"\s*:\s*"([^"]+)"/
                    if (match) {
                        firebaseAppId = match[0][1]
                        match = null
                    }
                }
                if (firebaseAppId) {
                    configSource = "(Jenkins credential: ${credentialId})"
                }
            }
        } catch (Exception e) {
            echo "[WARN] Could not load Firebase config from credential '${credentialId}': ${e.message}"
        }
    }

    // Check for GoogleService-Info.plist (iOS) in Unity project locations
    if (!firebaseAppId) {
        def plistLocations = [
            "${env.UNITY_PROJECT}/Assets/GoogleService-Info.plist",
            "${env.UNITY_PROJECT}/Assets/StreamingAssets/GoogleService-Info.plist",
            "${env.UNITY_PROJECT}/Assets/Plugins/iOS/GoogleService-Info.plist",
            "${env.UNITY_PROJECT}/Assets/Firebase/GoogleService-Info.plist"
        ]

        for (location in plistLocations) {
            def exists = sh(script: "[ -f '${location}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (exists == 'found') {
                firebaseAppId = sh(
                    script: "/usr/libexec/PlistBuddy -c 'Print :GOOGLE_APP_ID' '${location}' 2>/dev/null",
                    returnStdout: true
                ).trim()
                if (firebaseAppId) {
                    configSource = location
                    break
                }
            }
        }
    }

    // Fall back to google-services.json (Android config)
    if (!firebaseAppId) {
        def jsonLocations = [
            "${env.UNITY_PROJECT}/Assets/google-services.json",
            "${env.UNITY_PROJECT}/Assets/StreamingAssets/google-services.json",
            "${env.UNITY_PROJECT}/Assets/Plugins/Android/google-services.json",
            "${env.UNITY_PROJECT}/Assets/Firebase/google-services.json"
        ]

        for (location in jsonLocations) {
            def exists = sh(script: "[ -f '${location}' ] && echo found || echo notfound", returnStdout: true).trim()
            if (exists == 'found') {
                def content = readFile(location)
                def match = content =~ /"mobilesdk_app_id"\s*:\s*"([^"]+)"/
                if (match) {
                    firebaseAppId = match[0][1]
                    match = null
                    configSource = location
                    break
                }
            }
        }
    }

    if (!firebaseAppId) {
        error """[ERROR] Firebase config not found

Place one of these files in your Unity project:
  - Assets/GoogleService-Info.plist (iOS — download from Firebase Console)
  - Assets/google-services.json (Android — fallback)

Or set GOOGLE_SERVICES_CREDENTIAL_ID in the Jenkins job config to a credential containing the file."""
    }

    env.FIREBASE_APP_ID = firebaseAppId
    env.FIREBASE_CONFIG_PATH = configSource   // used by upload-symbols (-gsp) for iOS dSYM upload
    echo "[INFO] Using Firebase config from ${configSource}"
    echo "[INFO] Extracted Firebase App ID: ${firebaseAppId}"

    // Validate FIREBASE_APP_ID format (e.g., 1:123456789:android:abcdef or 1:123456789:ios:abcdef)
    def appIdPattern = /^\d+:\d+:(android|ios):[a-f0-9]+$/
    if (!(firebaseAppId ==~ appIdPattern)) {
        error """[ERROR] Firebase App ID format invalid: ${firebaseAppId}

Expected format: 1:123456789:ios:abcdef

To fix:
  1. Verify you downloaded the correct GoogleService-Info.plist from Firebase Console
  2. Ensure this is for a Firebase project (not just Google Cloud)
  3. The app must be registered in Firebase with Crashlytics enabled"""
    }

    // Check Firebase CLI — auto-install via npm if missing
    def firebaseCheck = checkFirebaseCLI()
    if (!firebaseCheck.available) {
        echo "[INFO] Firebase CLI not found, installing via npm..."
        def installExit = sh(script: 'npm install -g firebase-tools', returnStatus: true)
        if (installExit == 0) {
            firebaseCheck = checkFirebaseCLI()
        }
        if (!firebaseCheck.available) {
            error """[ERROR] Firebase CLI auto-install failed

Run manually on the Jenkins agent:
  npm install -g firebase-tools

Then verify:
  firebase --version"""
        }
        echo "[OK] Firebase CLI installed"
    }
    echo "[OK] ${firebaseCheck.message}"

    // Firebase CLI is present (checked above) and the App ID is extracted + well-formed.
    // We deliberately do NOT pre-validate with `firebase apps:sdkconfig`: that call needs
    // Firebase *read* access (Firebase Viewer), a different permission than the symbol upload
    // needs (Firebase Crashlytics Admin) — so the read check could wrongly disable an upload
    // that would actually succeed. The real `crashlytics:symbols:upload` is the source of
    // truth and fails soft (marks the build unstable, never breaks it).
    echo "[OK] Firebase CLI preflight passed (App ID: ${firebaseAppId}); upload is verified at upload time"
}

// ============================================================================
// CRASHLYTICS SYMBOL UPLOAD
// ============================================================================

/**
 * Upload native symbols to Firebase Crashlytics
 *
 * Requires both environment variables to be set in the Jenkins job:
 *   - UPLOAD_CRASHLYTICS_SYMBOLS=true (opt-in flag)
 *   - FIREBASE_APP_ID (the Firebase app ID — set by preflightFirebaseCLI)
 *
 * @param config Map containing:
 *   - buildPath: Path to the build output directory (e.g., Xcode project or archive path)
 *   - platform: 'Android' or 'iOS' (optional, defaults to env.PLATFORM)
 */
def uploadCrashlyticsSymbols(Map config) {
    // Skip if not opted in
    if (env.UPLOAD_CRASHLYTICS_SYMBOLS != 'true') {
        echo "[INFO] UPLOAD_CRASHLYTICS_SYMBOLS not set to 'true', skipping Crashlytics symbol upload"
        return
    }

    def firebaseAppId = env.FIREBASE_APP_ID

    // Skip if FIREBASE_APP_ID is not configured
    if (!firebaseAppId) {
        echo "[WARN] UPLOAD_CRASHLYTICS_SYMBOLS is true but FIREBASE_APP_ID is not set, skipping"
        return
    }

    def buildPath = config.buildPath
    def platform = config.platform ?: env.PLATFORM ?: 'iOS'

    echo "[INFO] Uploading symbols to Crashlytics"
    echo "[INFO] App ID: ${firebaseAppId}"
    echo "[INFO] Platform: ${platform}"
    echo "[INFO] Build path: ${buildPath}"

    // Check Firebase CLI is available (should have been validated in preflight)
    def firebaseCheck = checkFirebaseCLI()
    if (!firebaseCheck.available) {
        echo "[ERROR] Firebase CLI not available: ${firebaseCheck.message}"
        if (ensureCommon()) {
            common.setUnstable("Crashlytics upload skipped - Firebase CLI not available")
        }
        return
    }

    if (platform == 'iOS') {
        uploadiOSCrashlyticsSymbols(buildPath, firebaseAppId)
    } else if (platform == 'Android') {
        echo "[WARN] Android Crashlytics symbol upload on macOS is not supported — Android builds run on Windows agents"
    } else {
        echo "[WARN] Crashlytics symbol upload not supported for platform: ${platform}"
    }
}

/**
 * Upload iOS dSYM files to Crashlytics
 *
 * Searches buildPath recursively for *.dSYM bundles and uploads them
 * using Firebase CLI.
 */
def uploadiOSCrashlyticsSymbols(String buildPath, String appId) {
    // Find dSYM files — check xcarchive dSYMs dir first, then search recursively
    def dsymPath = sh(
        script: """
            # Check xcarchive dSYMs directory first (most common location)
            ARCHIVE_DSYMS=\$(find "${buildPath}" -path "*/output.xcarchive/dSYMs" -type d 2>/dev/null | head -1)
            if [ -n "\$ARCHIVE_DSYMS" ] && [ -d "\$ARCHIVE_DSYMS" ]; then
                DSYM_COUNT=\$(find "\$ARCHIVE_DSYMS" -name "*.dSYM" -type d 2>/dev/null | wc -l | tr -d ' ')
                if [ "\$DSYM_COUNT" -gt 0 ]; then
                    echo "\$ARCHIVE_DSYMS"
                    exit 0
                fi
            fi

            # Fall back to recursive search
            FIRST_DSYM=\$(find "${buildPath}" -name "*.dSYM" -type d 2>/dev/null | head -1)
            if [ -n "\$FIRST_DSYM" ]; then
                dirname "\$FIRST_DSYM"
                exit 0
            fi
        """,
        returnStdout: true
    ).trim()

    if (!dsymPath) {
        echo "[WARN] No dSYM files found in ${buildPath}, skipping Crashlytics upload"
        echo "[INFO] Ensure Xcode build generates dSYMs:"
        echo "[INFO]   Build Settings > Debug Information Format = DWARF with dSYM File"
        return
    }

    // List dSYM files + inspect their contents. This tells us WHY an upload might fail with
    // "No native libraries found": if dwarfdump shows no UUIDs or the DWARF binary is tiny,
    // the dSYM is stripped/empty (a build-settings problem, not an upload/permissions one).
    sh """
        echo "[INFO] Found dSYM files in: ${dsymPath}"
        find "${dsymPath}" -name "*.dSYM" -type d | while read dsym; do
            echo "  dSYM: \$(basename \"\$dsym\")"
            echo "    -- dwarfdump --uuid --"
            dwarfdump --uuid "\$dsym" 2>&1 | sed 's/^/    /' || echo "    (dwarfdump --uuid failed)"
            echo "    -- DWARF payload (size = symbols present?) --"
            ls -la "\$dsym/Contents/Resources/DWARF/" 2>/dev/null | sed 's/^/    /' || echo "    (no DWARF dir — dSYM has no debug info)"
        done
    """

    // Upload the dSYMs. Best-effort: never fails the build.
    //
    // Prefer the Firebase iOS SDK's `upload-symbols` tool — it's purpose-built for iOS dSYMs,
    // authenticates off the GoogleService-Info.plist (no service-account/IAM needed), and avoids
    // the generic CLI's native-symbol path that trips with "No native libraries found". Fall back
    // to `firebase crashlytics:symbols:upload` if the tool isn't present.
    def firebaseCmd = env.FIREBASE_CMD ?: 'firebase'
    def gsp = env.FIREBASE_CONFIG_PATH ?: ''
    def uploaded = false

    // upload-symbols ships in the FirebaseCrashlytics CocoaPod under the Xcode project (Pods/).
    def uploadSymbols = sh(returnStdout: true, script: """
        find "${buildPath}" -name upload-symbols -type f 2>/dev/null | head -1
    """).trim()

    if (uploadSymbols && gsp.endsWith('.plist')) {
        echo "[INFO] Uploading via upload-symbols (${uploadSymbols})..."
        def rc = sh(returnStatus: true, script: """
            chmod +x "${uploadSymbols}" 2>/dev/null || true
            "${uploadSymbols}" -gsp "${gsp}" -p ios "${dsymPath}"
        """)
        if (rc == 0) {
            uploaded = true
            echo "[OK] Crashlytics dSYMs uploaded via upload-symbols"
        } else {
            echo "[WARN] upload-symbols failed (exit ${rc}); falling back to the firebase CLI..."
        }
    } else {
        echo "[INFO] upload-symbols not found (or no .plist config) — using the firebase CLI"
    }

    if (!uploaded) {
        withCredentials([file(credentialsId: 'google-play-json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            def rc = sh(returnStatus: true, script: """
                echo "[INFO] Uploading iOS dSYMs to Crashlytics via firebase CLI..."
                "${firebaseCmd}" crashlytics:symbols:upload --app="${appId}" "${dsymPath}"
            """)
            if (rc == 0) {
                uploaded = true
                echo "[OK] Crashlytics dSYMs uploaded successfully"
            }
        }
    }

    if (!uploaded) {
        echo "[WARN] Crashlytics dSYM upload failed — build NOT failed, continuing."
        echo "[WARN] See the crashlytics error(s) above. If it says 'No native libraries found',"
        echo "[WARN] the dSYMs have no usable symbols — check the dwarfdump/DWARF output above. The"
        echo "[WARN] archive now forces DEBUG_INFORMATION_FORMAT=dwarf-with-dsym, so a fresh (clean)"
        echo "[WARN] build should produce full dSYMs. A 403 instead means the service account needs"
        echo "[WARN] the 'Firebase Crash Symbol Uploader' role (not full Firebase Admin)."
        if (ensureCommon()) {
            common.setUnstable("Crashlytics dSYM upload failed - see crashlytics/dwarfdump output in the log")
        }
    }
}

return this
