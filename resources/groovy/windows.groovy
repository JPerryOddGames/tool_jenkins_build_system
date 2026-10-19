// ============================================================================
// WINDOWS-SPECIFIC UTILITIES - Uses bat commands
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

// Short staging path for Steam uploads (avoids 260-char limit)
def setupSteamStaging(String sourcePath) {
    // Generate random folder name using build number + timestamp
    def randomId = "${env.BUILD_NUMBER}_${System.currentTimeMillis()}"
    def stagingPath = "C:\\Temp\\Steam\\${randomId}"

    echo "[INFO] Staging Steam content to short path: ${stagingPath}"
    echo "[INFO] Source: ${sourcePath}"

    bat """
        @echo off
        if not exist "C:\\Temp\\Steam" mkdir "C:\\Temp\\Steam"
        if exist "${stagingPath}" rmdir /s /q "${stagingPath}"
        mkdir "${stagingPath}"
        echo Copying to staging folder...
        robocopy "${sourcePath}" "${stagingPath}" /E /NFL /NDL /NJH /NJS /NP
        set ROBOCOPY_EXIT=%errorlevel%
        if %ROBOCOPY_EXIT% GEQ 8 (
            echo [ERROR] Robocopy failed with error %ROBOCOPY_EXIT%
            exit /b 1
        )
        echo [OK] Content staged to ${stagingPath}
        exit /b 0
    """

    logBuildOutputs(stagingPath)

    env.STEAM_STAGING_PATH = stagingPath
    return stagingPath
}

def cleanupSteamStaging() {
    try {
        bat """
            @echo off
            REM Clean up this build's staging folder
            if defined STEAM_STAGING_PATH (
                if exist "%STEAM_STAGING_PATH%" (
                    rmdir /s /q "%STEAM_STAGING_PATH%"
                    echo [OK] Cleaned up staging folder: %STEAM_STAGING_PATH%
                )
            )

            REM Clean up this build's local Steam cache
            if defined STEAM_LOCAL_CACHE_PATH (
                if exist "%STEAM_LOCAL_CACHE_PATH%" (
                    rmdir /s /q "%STEAM_LOCAL_CACHE_PATH%"
                    echo [OK] Cleaned up local cache: %STEAM_LOCAL_CACHE_PATH%
                )
            )

            REM Clean up any orphaned staging folders older than 1 day
            REM forfiles returns exit code 1 when no files match — use exit /b 0 to prevent false failure
            if exist "C:\\Temp\\Steam" (
                echo Checking for orphaned staging folders...
                forfiles /p "C:\\Temp\\Steam" /d -1 /c "cmd /c if @isdir==TRUE rmdir /s /q @path && echo [OK] Cleaned orphan: @file" 2>nul || echo [OK] No orphaned folders found
            )
            exit /b 0
        """
    } catch (Exception e) {
        echo "[WARNING] Steam staging cleanup failed (no node context?): ${e.message}"
    }
}

def preflightSteamStaging() {
    bat """
        @echo off
        if not exist "C:\\Temp\\Steam" mkdir "C:\\Temp\\Steam"
        echo [OK] Steam staging directory available: C:\\Temp\\Steam
    """
}

// ============================================================================
// STEAM DELTA CACHE - Persistent SteamCMD cache for delta uploads
//
// Adds steam_cache\ to existing build folders on \\odd-jenkins\builds.
// After a successful Steam upload, the SteamCMD .csm/.csd cache files are
// saved alongside the build output. On the next build, the cache is seeded
// from the previous version so SteamCMD only uploads changed chunks.
//
// Cache matching uses Steam manifest IDs for precision:
//   1. Before upload: query app_info_print for the current depot manifest
//   2. Find the cached build whose stored manifest_id matches
//   3. Seed from that cache (even across branches uploading to the same depot)
//   4. After upload: query the new manifest and save it with the cache
//
// Structure (extends existing local share layout):
//   \\odd-jenkins\builds\{JOB_NAME}\{buildType}\{VERSION}\
//     <game files>               ← from uploadToLocalShare (existing)
//     steam_cache\               ← SteamCMD .csm/.csd files (new)
//     steam_cache.properties     ← manifest_id, steam_branch, version (new)
//
// Failed uploads never write to the share - the cache is local until success.
// ============================================================================

/**
 * Queries SteamCMD for the current manifest ID of a depot on a specific branch.
 * Uses app_info_print (called twice to work around output truncation bug).
 * Must be called within a withCredentials block that provides STEAM_USERNAME/STEAM_PASSWORD.
 *
 * @param config Map with keys:
 *   appId   - Steam App ID (default: env.STEAM_APP_ID)
 *   depotId - Steam Depot ID (default: env.STEAM_DEPOT_ID)
 *   branch  - Steam branch name, e.g. 'beta', 'development' (required)
 * @return Manifest ID string, or null if query failed
 */
def querySteamManifest(Map config) {
    def appId = config.appId ?: env.STEAM_APP_ID
    def depotId = config.depotId ?: env.STEAM_DEPOT_ID
    def branch = config.branch

    if (!appId || !depotId || !branch) {
        echo "[WARN] querySteamManifest: missing params - appId=${appId}, depotId=${depotId}, branch=${branch}"
        return null
    }

    if (!env.STEAMCMD_PATH) {
        echo "[INFO] querySteamManifest: STEAMCMD_PATH not set — resolving..."
        def steamCheck = checkSteamCMD(false)
        if (steamCheck.available) {
            env.STEAMCMD_PATH = steamCheck.path
        } else {
            echo "[WARN] querySteamManifest: SteamCMD not found — skipping manifest query"
            return null
        }
    }

    def steamCmdDir = env.STEAMCMD_PATH.replace('\\steamcmd.exe', '')

    echo "========================================"
    echo "Steam Manifest Query"
    echo "========================================"
    echo "App ID: ${appId}"
    echo "Depot ID: ${depotId}"
    echo "Branch: ${branch}"
    echo "SteamCMD: ${env.STEAMCMD_PATH}"
    echo ""

    def output = ''
    try {
        // app_info_print called twice to work around SteamCMD non-TTY truncation bug
        output = bat(
            script: """
                @echo off
                cd /d "${steamCmdDir}"
                "${env.STEAMCMD_PATH}" +login "%STEAM_USERNAME%" "%STEAM_PASSWORD%" +app_info_update 1 +app_info_print ${appId} +app_info_print ${appId} +quit
            """,
            returnStdout: true
        )
    } catch (Exception e) {
        echo "[WARN] querySteamManifest: SteamCMD failed - ${e.message}"
        echo "[DEBUG] This is non-fatal, upload will continue without manifest matching"
        return null
    }

    // Log raw output for debugging (filter out empty lines and common noise)
    echo "[DEBUG] === SteamCMD app_info_print raw output (filtered) ==="
    def outputLines = output.split('\r?\n')
    echo "[DEBUG] Total output lines: ${outputLines.length}"
    def depotSectionFound = false
    def manifestsSectionFound = false
    for (line in outputLines) {
        def trimmed = line.trim()
        // Log lines that contain depot info, manifests, or branch data
        if (trimmed.contains("\"${depotId}\"") || trimmed.contains('"manifests"') ||
            trimmed.contains('"branches"') || trimmed.contains("\"${branch}\"") ||
            trimmed.contains('"buildid"') || trimmed.contains('"timeupdated"')) {
            echo "[DEBUG] >> ${trimmed}"
        }
    }
    echo "[DEBUG] === End raw output ==="

    // Parse VDF output: find depot section → manifests block → branch manifest ID
    def manifestId = null
    def inDepot = false
    def inManifests = false
    def depotBraceDepth = 0

    for (line in outputLines) {
        def trimmed = line.trim()

        // Enter depot section when we see the depot ID
        if (!inDepot && trimmed.contains("\"${depotId}\"")) {
            inDepot = true
            depotBraceDepth = 0
            if (trimmed.contains('{')) depotBraceDepth++
            depotSectionFound = true
            echo "[DEBUG] Found depot section for ${depotId} (braceDepth=${depotBraceDepth})"
            continue
        }

        if (!inDepot) continue

        // Track brace depth to know when we exit the depot section
        if (trimmed.contains('{')) depotBraceDepth++
        if (trimmed.contains('}')) depotBraceDepth--
        if (depotBraceDepth <= 0) {
            echo "[DEBUG] Exited depot section (braceDepth=${depotBraceDepth})"
            inDepot = false
            continue
        }

        // Look for manifests sub-section
        if (trimmed.contains('"manifests"')) {
            inManifests = true
            manifestsSectionFound = true
            echo "[DEBUG] Entered manifests section"
            continue
        }

        if (inManifests) {
            // End of manifests block
            if (trimmed == '}') {
                echo "[DEBUG] Exited manifests section"
                inManifests = false
                continue
            }
            if (trimmed == '{') continue

            // Log every manifest entry we see
            echo "[DEBUG] Manifest entry: ${trimmed}"

            // Match: "branch_name"    "manifest_id"
            def match = (trimmed =~ "\"${branch}\"\\s+\"(\\d+)\"")
            if (match.find()) {
                manifestId = match.group(1)
                echo "[DEBUG] Matched branch '${branch}' → manifest ${manifestId}"
                break
            }
        }
    }

    if (!depotSectionFound) {
        echo "[WARN] querySteamManifest: depot ${depotId} not found in app_info_print output"
        echo "[DEBUG] This may mean the account doesn't have access or the depot ID is wrong"
    } else if (!manifestsSectionFound) {
        echo "[WARN] querySteamManifest: 'manifests' section not found in depot ${depotId}"
    }

    if (manifestId) {
        echo "[OK] Current Steam manifest for '${branch}': ${manifestId}"
    } else {
        echo "[WARN] querySteamManifest: no manifest found for depot ${depotId} on branch '${branch}'"
        echo "[DEBUG] This is non-fatal - cache seeding will fall back to latest available"
    }

    return manifestId
}

/**
 * Seeds the local Steam cache from a previous build on the network share.
 * Uses manifest ID matching to find the exact cache that corresponds to what's
 * currently live on Steam, with fallbacks for branch match and any available cache.
 *
 * Call after setupSteamStaging() and before steamUpload().
 * Must be within a withCredentials block for Steam credentials.
 *
 * @param config Map with keys:
 *   buildType    - 'Debug' or 'Release' (required)
 *   steamBranch  - Steam branch name, e.g. 'beta', 'development' (required)
 */
def seedSteamCache(Map config = [:]) {
    def buildType = config.buildType ?: 'Release'
    def steamBranch = config.steamBranch ?: (buildType == 'Release' ? 'beta' : 'development')
    def sharePath = env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds'
    def versionsPath = "${sharePath}\\${env.JOB_NAME}\\${buildType}"

    echo "========================================"
    echo "Steam Delta Cache - Seed"
    echo "========================================"
    echo "Build type: ${buildType}"
    echo "Steam branch: ${steamBranch}"
    echo "Share path: ${versionsPath}"
    echo ""

    // Create local cache directory alongside staging (separate from content to avoid uploading it)
    def localCachePath = "C:\\Temp\\Steam\\${env.BUILD_NUMBER}_cache"
    bat """
        @echo off
        if not exist "${localCachePath}" mkdir "${localCachePath}"
    """
    env.STEAM_LOCAL_CACHE_PATH = localCachePath
    echo "[DEBUG] Local cache path: ${localCachePath}"

    // List all available caches on the share for debugging
    try {
        echo "[DEBUG] Scanning versions on share..."
        def dirListing = bat(
            script: """@powershell -NoProfile -Command "if (Test-Path '${versionsPath}') { Get-ChildItem -Path '${versionsPath}' -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object { \$hasCache = Test-Path (Join-Path \$_.FullName 'steam_cache'); \$hasProps = Test-Path (Join-Path \$_.FullName 'steam_cache.properties'); \$props = ''; if (\$hasProps) { \$props = (Get-Content (Join-Path \$_.FullName 'steam_cache.properties') -Raw).Trim() -replace '\\r?\\n', ' | ' }; Write-Output ('{0}  cache={1}  props={2}  [{3}]' -f \$_.Name, \$hasCache, \$hasProps, \$props) } } else { Write-Output '(directory does not exist)' }" """,
            returnStdout: true
        ).trim()
        def versionLines = dirListing
            .split('\r?\n')
            .collect { it.trim() }
            .findAll { it }

        if (versionLines) {
            echo "[DEBUG] Available versions:\n[DEBUG]   ${versionLines.join('\n[DEBUG]   ')}"
        } else {
            echo "[DEBUG] Available versions: (none)"
        }
    } catch (Exception e) {
        echo "[DEBUG] Could not list share contents: ${e.message}"
    }

    // Query Steam for the current manifest on the target branch
    echo ""
    echo "[INFO] Querying Steam for current manifest on '${steamBranch}'..."
    def currentManifest = null
    try {
        currentManifest = querySteamManifest(branch: steamBranch)
    } catch (Exception e) {
        echo "[WARN] Steam manifest query failed: ${e.message}"
        echo "[DEBUG] Continuing without manifest matching"
    }

    // Tier 1: Find a cache whose manifest_id matches what's currently on Steam
    def seedVersion = null
    def seedTier = ''

    if (currentManifest) {
        echo "[DEBUG] Tier 1: Searching for cache with manifest_id=${currentManifest}..."
        try {
            seedVersion = bat(
                script: """@powershell -NoProfile -Command "if (Test-Path '${versionsPath}') { Get-ChildItem -Path '${versionsPath}' -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object { \$p = Join-Path \$_.FullName 'steam_cache.properties'; if (Test-Path \$p) { \$c = Get-Content \$p -Raw; if (\$c -match 'manifest_id=${currentManifest}') { Write-Output \$_.Name; return } } } | Select-Object -First 1 }" """,
                returnStdout: true
            ).trim()
        } catch (Exception e) {
            echo "[DEBUG] Tier 1 search failed: ${e.message}"
        }
        if (seedVersion) {
            seedTier = 'manifest'
            echo "[DEBUG] Tier 1 hit: ${seedVersion}"
        } else {
            echo "[DEBUG] Tier 1: no match found"
        }
    } else {
        echo "[DEBUG] Tier 1: skipped (no manifest to match against)"
    }

    // Tier 2: Fall back to latest cache from the same Steam branch
    if (!seedVersion) {
        echo "[DEBUG] Tier 2: Searching for cache with steam_branch=${steamBranch}..."
        try {
            seedVersion = bat(
                script: """@powershell -NoProfile -Command "if (Test-Path '${versionsPath}') { Get-ChildItem -Path '${versionsPath}' -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object { \$p = Join-Path \$_.FullName 'steam_cache.properties'; if (Test-Path \$p) { \$c = Get-Content \$p -Raw; if (\$c -match 'steam_branch=${steamBranch}') { Write-Output \$_.Name; return } } } | Select-Object -First 1 }" """,
                returnStdout: true
            ).trim()
        } catch (Exception e) {
            echo "[DEBUG] Tier 2 search failed: ${e.message}"
        }
        if (seedVersion) {
            seedTier = 'branch'
            echo "[DEBUG] Tier 2 hit: ${seedVersion}"
        } else {
            echo "[DEBUG] Tier 2: no match found"
        }
    }

    // Tier 3: Last resort - any version with a steam_cache directory
    if (!seedVersion) {
        echo "[DEBUG] Tier 3: Searching for any version with steam_cache dir..."
        try {
            seedVersion = bat(
                script: """@powershell -NoProfile -Command "if (Test-Path '${versionsPath}') { \$v = Get-ChildItem -Path '${versionsPath}' -Directory | Where-Object { Test-Path (Join-Path \$_.FullName 'steam_cache') } | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty Name; if (\$v) { Write-Output \$v } }" """,
                returnStdout: true
            ).trim()
        } catch (Exception e) {
            echo "[DEBUG] Tier 3 search failed: ${e.message}"
        }
        if (seedVersion) {
            seedTier = 'any'
            echo "[DEBUG] Tier 3 hit: ${seedVersion}"
        } else {
            echo "[DEBUG] Tier 3: no match found"
        }
    }

    echo ""
    if (seedVersion) {
        def tierDesc = [manifest: "exact manifest match", branch: "same Steam branch '${steamBranch}'", any: "latest available cache"]
        echo "[OK] Seeding delta cache from ${seedVersion} (${tierDesc[seedTier]})"
        def prevCachePath = "${versionsPath}\\${seedVersion}\\steam_cache"

        // Log what we're copying
        echo "[DEBUG] Source: ${prevCachePath}"
        echo "[DEBUG] Destination: ${localCachePath}"
        bat """
            @echo off
            echo [DEBUG] Cache contents to seed:
            dir /s "${prevCachePath}"
            echo.
            robocopy "${prevCachePath}" "${localCachePath}" /E /NJH /NJS /NP
            if %errorlevel% GEQ 8 (
                echo [WARN] Robocopy failed with exit code %errorlevel%, upload will be full
            ) else (
                echo [OK] Delta cache seeded successfully
            )
            exit /b 0
        """
    } else {
        echo "[INFO] No previous Steam cache found - first upload will be full"
        echo "[DEBUG] This is normal for the first build or after cache cleanup"
    }
}

/**
 * Saves the local Steam cache to the network share after a successful upload.
 * Queries Steam for the new manifest ID and writes a properties file alongside
 * the cache so future builds can match by manifest.
 *
 * Call after a successful steamUpload().
 * Must be within a withCredentials block for Steam credentials.
 *
 * @param config Map with keys:
 *   steamBranch - Steam branch name, e.g. 'beta', 'development' (required)
 */
def syncSteamCache(Map config = [:]) {
    def steamBranch = config.steamBranch ?: 'beta'

    echo "========================================"
    echo "Steam Delta Cache - Sync"
    echo "========================================"
    echo "Steam branch: ${steamBranch}"

    def localCachePath = env.STEAM_LOCAL_CACHE_PATH
    if (!localCachePath) {
        echo "[WARN] syncSteamCache: STEAM_LOCAL_CACHE_PATH not set (seedSteamCache not called?)"
        echo "[DEBUG] Skipping cache sync - this won't affect the upload, just future delta seeding"
        return
    }
    echo "[DEBUG] Local cache: ${localCachePath}"

    def destPath = env.LOCAL_BUILD_PATH
    if (!destPath) {
        echo "[WARN] syncSteamCache: LOCAL_BUILD_PATH not set - uploadToLocalShare may not have run"
        echo "[DEBUG] Skipping cache sync - this won't affect the upload, just future delta seeding"
        return
    }
    echo "[DEBUG] Destination: ${destPath}"

    // Log local cache contents before copying
    echo "[DEBUG] Local cache contents after upload:"
    bat """
        @echo off
        dir /s "${localCachePath}"
    """

    // Query Steam for the new manifest after upload
    echo ""
    echo "[INFO] Querying Steam for new manifest after upload..."
    def newManifest = null
    try {
        newManifest = querySteamManifest(branch: steamBranch)
    } catch (Exception e) {
        echo "[WARN] Post-upload manifest query failed: ${e.message}"
        echo "[DEBUG] Cache will be saved with manifest_id=unknown"
    }

    // Copy cache files to the share
    def cacheDest = "${destPath}\\steam_cache"
    echo ""
    echo "[INFO] Copying cache to share: ${cacheDest}"

    try {
        bat """
            @echo off
            if not exist "${cacheDest}" mkdir "${cacheDest}"
            robocopy "${localCachePath}" "${cacheDest}" /E /NJH /NJS /NP
            if %errorlevel% GEQ 8 (
                echo [ERROR] Robocopy failed with exit code %errorlevel%
                exit /b 1
            )
            echo [OK] Steam cache copied to share
            echo.
            echo [DEBUG] Saved cache contents:
            dir /s "${cacheDest}"
            exit /b 0
        """
    } catch (Exception e) {
        echo "[WARN] Failed to copy Steam cache to share: ${e.message}"
        echo "[DEBUG] This won't affect the current upload, but future builds won't have a delta seed"
        return
    }

    // Write properties file for future manifest-based cache matching
    def propsFile = "${destPath}\\steam_cache.properties"
    echo ""
    echo "[INFO] Writing cache properties: ${propsFile}"

    try {
        bat """
            @echo off
            (
                echo manifest_id=${newManifest ?: 'unknown'}
                echo steam_branch=${steamBranch}
                echo version=${env.VERSION ?: 'unknown'}
                echo build_number=${env.BUILD_NUMBER ?: 'unknown'}
            ) > "${propsFile}"
            echo [OK] Cache properties:
            type "${propsFile}"
        """
    } catch (Exception e) {
        echo "[WARN] Failed to write cache properties: ${e.message}"
    }
}

/**
 * Logs the contents of a build output directory for debugging.
 * Shows all files recursively to help diagnose missing or unexpected outputs.
 *
 * @param buildPath Path to the directory to list
 */
def logBuildOutputs(String buildPath) {
    echo "[INFO] Listing build outputs: ${buildPath}"
    bat """
        @echo off
        if not exist "${buildPath}" (
            echo [WARN] Build output directory does not exist: ${buildPath}
            exit /b 0
        )
        echo ======== Build Outputs ========
        dir /s /b "${buildPath}"
        echo ================================

        REM Copy .log files to artifact path for archiving
        if defined ARTIFACT_PATH (
            for /r "${buildPath}" %%f in (*.log) do (
                if not exist "%ARTIFACT_PATH%\\build_logs" mkdir "%ARTIFACT_PATH%\\build_logs"
                copy "%%f" "%ARTIFACT_PATH%\\build_logs\\%%~nxf" >nul 2>&1
                echo [OK] Archived log: %%~nxf
            )
        )
    """
}

// ============================================================================
// TEXTURE CAP
// ============================================================================

/**
 * Check for Python 3. Tries py launcher (System32), then python in PATH,
 * then common install locations. Caches result in env.PYTHON_EXE.
 * @param autoInstall If true, attempt to install via winget/choco if not found.
 */
def checkPython(boolean autoInstall = false) {
    if (env.PYTHON_EXE) return [available: true, message: "Python 3 (${env.PYTHON_EXE})"]

    // Build LOCALAPPDATA path checks as a Groovy variable (env.LOCALAPPDATA might not be set)
    def localAppData = env.LOCALAPPDATA ?: ''
    def localPaths = localAppData ? """
        for %%d in ("${localAppData}\\Programs\\Python\\Python313" "${localAppData}\\Programs\\Python\\Python312" "${localAppData}\\Programs\\Python\\Python311" "${localAppData}\\Programs\\Python\\Python310") do (
            if exist "%%~d\\python.exe" (
                echo   Found Python at %%~d >&2
                set "PYVER="
                for /f "delims=" %%v in ('"%%~d\\python.exe" --version 2^>^&1') do if not defined PYVER set "PYVER=%%v"
                echo !PYVER! | findstr /C:"Python 3" >nul
                if not errorlevel 1 (
                    echo FOUND_DIR
                    echo %%~d
                    echo !PYVER!
                    exit /b 0
                )
            )
        )""" : ''

    // Single bat call searches all locations: py launcher, python in PATH, common dirs, where
    def result = bat(
        script: """@echo off
            setlocal EnableDelayedExpansion
            echo Checking Python installation... >&2

            REM 1. py launcher (C:\\Windows\\System32 - always in PATH)
            set "PYVER="
            for /f "delims=" %%v in ('py -3 --version 2^>^&1') do if not defined PYVER set "PYVER=%%v"
            if defined PYVER (
                echo !PYVER! | findstr /C:"Python 3" >nul
                if not errorlevel 1 (
                    echo   Found py launcher: !PYVER! >&2
                    echo FOUND_PY
                    echo !PYVER!
                    exit /b 0
                )
            )

            REM 2. python in PATH
            set "PYVER="
            for /f "delims=" %%v in ('python --version 2^>^&1') do if not defined PYVER set "PYVER=%%v"
            if defined PYVER (
                echo !PYVER! | findstr /C:"Python 3" >nul
                if not errorlevel 1 (
                    echo   Found python in PATH: !PYVER! >&2
                    echo FOUND_PYTHON
                    echo !PYVER!
                    exit /b 0
                )
            )

            REM 3. Common installation paths (LOCALAPPDATA + Program Files)
            ${localPaths}
            for %%d in ("C:\\Program Files\\Python313" "C:\\Program Files\\Python312" "C:\\Program Files\\Python311" "C:\\Program Files\\Python310" "C:\\Python313" "C:\\Python312" "C:\\Python311" "C:\\Python310" "C:\\Python39") do (
                if exist "%%~d\\python.exe" (
                    echo   Found Python at %%~d >&2
                    set "PYVER="
                    for /f "delims=" %%v in ('"%%~d\\python.exe" --version 2^>^&1') do if not defined PYVER set "PYVER=%%v"
                    echo !PYVER! | findstr /C:"Python 3" >nul
                    if not errorlevel 1 (
                        echo FOUND_DIR
                        echo %%~d
                        echo !PYVER!
                        exit /b 0
                    )
                )
            )

            REM 4. Last resort: where python.exe
            set "WPATH="
            for /f "delims=" %%p in ('where python.exe 2^>nul') do if not defined WPATH set "WPATH=%%p"
            if defined WPATH (
                set "PYVER="
                for /f "delims=" %%v in ('"!WPATH!" --version 2^>^&1') do if not defined PYVER set "PYVER=%%v"
                if defined PYVER (
                    echo !PYVER! | findstr /C:"Python 3" >nul
                    if not errorlevel 1 (
                        echo   Found Python via where: !WPATH! >&2
                        echo FOUND_WHERE
                        echo !WPATH!
                        echo !PYVER!
                        exit /b 0
                    )
                )
            )

            echo   Python not found >&2
            echo NOT_FOUND
            exit /b 0""",
        returnStdout: true
    ).trim()

    echo "  checkPython stdout: ${result}"
    def lines = result.readLines()
    def statusCode = lines[0]

    if (statusCode == 'FOUND_PY') {
        env.PYTHON_EXE = 'py -3'
        return [available: true, message: lines[1]]
    }

    if (statusCode == 'FOUND_PYTHON') {
        env.PYTHON_EXE = 'python'
        return [available: true, message: lines[1]]
    }

    if (statusCode == 'FOUND_DIR' && lines.size() >= 3) {
        def pyDir = lines[1]
        def version = lines[2]
        env.PATH = "${pyDir};${env.PATH}"
        env.PYTHON_EXE = "\"${pyDir}\\python.exe\""
        echo "[INFO] Found Python at ${pyDir}, added to PATH"
        return [available: true, message: "${version} (found at ${pyDir})"]
    }

    if (statusCode == 'FOUND_WHERE' && lines.size() >= 3) {
        def pyPath = lines[1]
        def version = lines[2]
        def pyDir = pyPath.replaceAll('(?i)\\\\python\\.exe$', '')
        env.PATH = "${pyDir};${env.PATH}"
        env.PYTHON_EXE = "\"${pyPath}\""
        echo "[INFO] Found Python via 'where': ${pyPath}"
        return [available: true, message: "${version} (found via where: ${pyPath})"]
    }

    // Log what we tried so failures are diagnosable
    echo "[WARN] Python 3 not found. Tried: py -3, python, common paths, where python.exe"
    if (localAppData) echo "[DEBUG] LOCALAPPDATA = ${localAppData}"

    if (autoInstall) {
        return installPython()
    }

    return [available: false, message: 'Python 3 not installed',
            installInstructions: 'winget install Python.Python.3 --scope machine --silent --accept-source-agreements --accept-package-agreements']
}

/**
 * Install Python 3 via winget or Chocolatey.
 */
def installPython() {
    if (isWingetAvailable()) {
        try {
            echo "[INFO] Installing Python 3 via winget..."
            // Python.Python.3 doesn't resolve on all machines - try specific versions
            def wingetIds = ['Python.Python.3.13', 'Python.Python.3.12', 'Python.Python.3.11']
            def wingetInstalled = false
            for (String pkgId in wingetIds) {
                def exitCode = bat(script: "@winget install ${pkgId} --scope machine --silent --accept-source-agreements --accept-package-agreements", returnStatus: true)
                if (exitCode == 0) {
                    echo "[OK] winget installed ${pkgId}"
                    wingetInstalled = true
                    break
                }
                echo "[WARN] winget install ${pkgId} failed (exit ${exitCode}), trying next..."
            }
            if (!wingetInstalled) {
                throw new Exception("All winget Python packages failed")
            }
            // winget installs to %LOCALAPPDATA%\Programs\Python\Python3xx or Program Files - check immediately
            def installCandidates = []
            if (env.LOCALAPPDATA) {
                installCandidates += [
                    "${env.LOCALAPPDATA}\\Programs\\Python\\Python313",
                    "${env.LOCALAPPDATA}\\Programs\\Python\\Python312",
                    "${env.LOCALAPPDATA}\\Programs\\Python\\Python311",
                    "${env.LOCALAPPDATA}\\Programs\\Python\\Python310",
                ]
            }
            installCandidates += [
                'C:\\Program Files\\Python313', 'C:\\Program Files\\Python312',
                'C:\\Program Files\\Python311', 'C:\\Program Files\\Python310',
            ]
            for (String pyDir in installCandidates) {
                try {
                    def exists = bat(script: "@if exist \"${pyDir}\\python.exe\" echo found", returnStdout: true).trim()
                    if (exists == 'found') {
                        env.PATH = "${pyDir};${env.PATH}"
                        env.PYTHON_EXE = "\"${pyDir}\\python.exe\""
                        echo "[OK] Python 3 installed at ${pyDir}"
                        return [available: true, message: "Python 3 installed at ${pyDir}"]
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            echo "[WARN] winget Python install failed: ${e.message}"
        }
    }

    try {
        def chocoVersion = bat(script: '@choco --version', returnStdout: true).trim()
        if (chocoVersion) {
            echo "[INFO] Installing Python 3 via Chocolatey..."
            bat "@choco install python3 -y"
            def chocoCandidates = ['C:\\Python313', 'C:\\Python312', 'C:\\Python311', 'C:\\Python310']
            for (String pyDir in chocoCandidates) {
                try {
                    def exists = bat(script: "@if exist \"${pyDir}\\python.exe\" echo found", returnStdout: true).trim()
                    if (exists == 'found') {
                        env.PATH = "${pyDir};${env.PATH}"
                        env.PYTHON_EXE = "\"${pyDir}\\python.exe\""
                        echo "[OK] Python 3 installed at ${pyDir}"
                        return [available: true, message: "Python 3 installed at ${pyDir}"]
                    }
                } catch (Exception e) {
                    echo "[WARN] Failed to verify choco Python at ${pyDir}: ${e.message}"
                }
            }
        }
    } catch (Exception e) {
        echo "[WARN] Chocolatey Python install failed: ${e.message}"
    }

    return [available: false, message: 'Python 3 auto-install failed',
            installInstructions: 'winget install Python.Python.3 --scope machine --silent --accept-source-agreements --accept-package-agreements']
}

def preflightPython() {
    def result = checkPython(true)
    if (result.available) {
        echo "[OK] Python: ${result.message}"
    } else {
        error "[ERROR] Python 3 not found\nFix: ${result.installInstructions}"
    }
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

    def assetsPath = config.assetsPath ?: "${env.UNITY_PROJECT}\\Assets"
    def outputFile = "${env.WORKSPACE}\\texture_cap_modified.txt"
    def scriptPath = "${env.WORKSPACE}\\texture_cap.py"
    def dryRunFlag = config.dryRun ? '--dry-run' : ''

    writeFile file: scriptPath, text: libraryResource('scripts/texture_cap.py')

    bat """
        @echo off
        ${env.PYTHON_EXE} "${scriptPath}" --assets "${assetsPath}" --max-size ${maxSize} --output "${outputFile}" ${dryRunFlag}
        if errorlevel 1 (
            echo [ERROR] Texture cap script failed
            exit /b 1
        )
    """
    env.TEXTURE_CAP_MODIFIED_LIST = outputFile
    echo "[OK] Texture cap complete (max ${maxSize}px)"
}

/**
 * Restore .meta files patched by capTextures() via Plastic SCM cm undo.
 * Safe to call even if capTextures() was skipped - no-ops if no list exists.
 */
def restoreTextures(Map config = [:]) {
    def inputFile = config.inputFile ?: env.TEXTURE_CAP_MODIFIED_LIST ?: "${env.WORKSPACE}\\texture_cap_modified.txt"
    def exists = bat(script: "@if exist \"${inputFile}\" echo found", returnStdout: true).trim()
    if (exists != 'found') {
        echo "[INFO] No texture cap list found - nothing to restore"
        return
    }

    def result = checkPython(false)
    if (!result.available) {
        echo "[WARN] Python 3 not found - cannot run texture restore script. Run `cm undo . -r` manually if needed."
        return
    }

    def scriptPath = "${env.WORKSPACE}\\texture_restore.py"
    writeFile file: scriptPath, text: libraryResource('scripts/texture_restore.py')

    def status = bat(script: """@echo off
${env.PYTHON_EXE} "${scriptPath}" --input "${inputFile}"
""", returnStatus: true)
    if (status != 0) {
        echo "[WARN] Texture restore had errors - run `cm undo . -r` manually in the Unity project if needed"
    }
}

/**
 * Upload a build to Steam with automatic retry on transient failures.
 * SteamCMD uploads can fail due to connection drops ('No Connection'),
 * timeouts, or Steam backend issues - these are retryable.
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
    // Ensure STEAMCMD_PATH is set — preflight may have been skipped (cached within 24h)
    if (!env.STEAMCMD_PATH) {
        echo "[INFO] STEAMCMD_PATH not set — resolving SteamCMD location..."
        def steamCheck = checkSteamCMD(true)
        if (!steamCheck.available) {
            error "[ERROR] SteamCMD not available: ${steamCheck.message}"
        }
        env.STEAMCMD_PATH = steamCheck.path
    }

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

    // Use persistent local cache for delta uploads if seedSteamCache() was called
    def buildOutputPath = env.STEAM_LOCAL_CACHE_PATH ?: 'steam_logs'

    echo "========================================"
    echo "Steam Upload"
    echo "========================================"
    // Derive VDF name from platform (e.g. steam_standalonewindows64.vdf, steam_standalonelinux64.vdf)
    def platformSuffix = (env.PLATFORM ?: 'windows').toLowerCase()
    def vdfName = "steam_${platformSuffix}.vdf"

    echo "App ID: ${appId}"
    echo "Depot ID: ${depotId}"
    echo "Content: ${contentRoot}"
    echo "BuildOutput: ${buildOutputPath}"
    echo "Set Live: ${setLive ?: '(none)'}"
    echo "Max retries: ${maxRetries}"
    echo ""

    // Create VDF build script
    bat """
        @echo off
        set "VDF_FILE=${artifactPath}\\${vdfName}"

        (
            echo "AppBuild"
            echo {
            echo     "AppID" "${appId}"
            echo     "Desc" "Build ${version}"
            echo     "ContentRoot" "${contentRoot}"
            echo     "BuildOutput" "${buildOutputPath}"
            echo     "Depots"
            echo     {
            echo         "${depotId}"
            echo         {
            echo             "FileMapping"
            echo             {
            echo                 "LocalPath" "*"
            echo                 "DepotPath" "."
            echo                 "recursive" "1"
            echo             }
            echo             "FileExclusion" "*_BackUpThisFolder_ButDontShipItWithYourGame"
            echo             "FileExclusion" "*_BurstDebugInformation_DoNotShip"
            echo         }
            echo     }
            ${setLive ? "echo     \"SetLive\" \"${setLive}\"" : ''}
            echo }
        ) > "%VDF_FILE%"

        echo [OK] VDF build script created at: %VDF_FILE%
        type "%VDF_FILE%"
    """

    def vdfFile = "${artifactPath}\\${vdfName}"
    def lastError = ''

    def failure = null

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        echo "[INFO] Steam upload attempt ${attempt}/${maxRetries}..."

        try {
            // Force exit /b 0 so bat() never throws - we parse the output ourselves
            def output = bat(
                script: """
                    @echo off
                    "%STEAMCMD_PATH%" +login "%STEAM_USERNAME%" "%STEAM_PASSWORD%" +run_app_build "${vdfFile}" +quit 2>&1
                    set STEAM_EXIT=%errorlevel%
                    echo.
                    echo STEAM_EXIT_CODE:%STEAM_EXIT%
                    exit /b 0
                """,
                returnStdout: true
            ).trim()

            // Print full SteamCMD output to Jenkins console
            echo output

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
            echo "[WARN] Steam upload attempt ${attempt} failed: ${lastError}"
            echo "[INFO] Not retrying - this failure won't clear on its own."
            dumpSteamContentLog()
            common.reportSteamFailure(failure, failure.branchSuspect ? getSteamBranches(appId) : null)
            common.updateUploadStatus('store', 'failed')
            env.STEAM_UPLOAD_ERROR = lastError
            error("[ERROR] ${lastError}")
        }

        if (attempt < maxRetries) {
            def waitSeconds = 30 * attempt
            echo "[WARN] Steam upload attempt ${attempt} failed: ${lastError}"
            echo "[INFO] Waiting ${waitSeconds}s before retry..."
            sleep(waitSeconds)
        }
    }

    dumpSteamContentLog()
    if (failure) common.reportSteamFailure(failure, null)
    common.updateUploadStatus('store', 'failed')
    env.STEAM_UPLOAD_ERROR = lastError
    error("[ERROR] Steam upload failed after ${maxRetries} attempts. Last error: ${lastError}")
}

/**
 * Dump the tail of SteamCMD's own content log - it carries the real reason a
 * commit was rejected, which the console output only summarises as 'Failure'.
 */
def dumpSteamContentLog(int tailLines = 60) {
    if (!env.STEAMCMD_PATH) return

    def logDir = "${env.STEAMCMD_PATH.replace('\\steamcmd.exe', '')}\\logs"
    bat(script: """@echo off
        if exist "${logDir}\\content_log.txt" (
            echo ===== SteamCMD content_log.txt ^(last ${tailLines} lines^) =====
            powershell -NoProfile -Command "Get-Content -LiteralPath '${logDir}\\content_log.txt' -Tail ${tailLines}"
            echo ===== end content_log.txt =====
        ) else (
            echo [INFO] No content_log.txt found in ${logDir}
        )
        exit /b 0
    """)
}

/**
 * Best-effort list of the branches that exist on a Steam app, for failure
 * diagnostics. Must run inside a withCredentials block providing Steam creds.
 *
 * @return List of branch names ('public' is the default branch), or null if the
 *         query failed or app_info returned no branches block.
 */
def getSteamBranches(appId) {
    if (!appId || !env.STEAMCMD_PATH) return null

    def steamCmdDir = env.STEAMCMD_PATH.replace('\\steamcmd.exe', '')
    def output = ''
    try {
        // app_info_print twice - works around the SteamCMD non-TTY truncation bug
        output = bat(
            script: """
                @echo off
                cd /d "${steamCmdDir}"
                "${env.STEAMCMD_PATH}" +login "%STEAM_USERNAME%" "%STEAM_PASSWORD%" +app_info_update 1 +app_info_print ${appId} +app_info_print ${appId} +quit
            """,
            returnStdout: true
        )
    } catch (Exception e) {
        echo "[WARN] getSteamBranches: app_info_print failed - ${e.message}"
        return null
    }

    def branches = []
    def inBranches = false
    def depth = 0
    for (raw in output.split('\r?\n')) {
        def trimmed = raw.trim()
        if (!inBranches) {
            if (trimmed == '"branches"') { inBranches = true; depth = 0 }
            continue
        }
        if (trimmed == '{') { depth++; continue }
        if (trimmed == '}') {
            depth--
            if (depth <= 0) break
            continue
        }
        // Branch names sit at depth 1 as a lone quoted token; keys inside a
        // branch are "key" "value" pairs and won't match.
        if (depth == 1 && trimmed ==~ /"[^"]+"/) branches << trimmed.replace('"', '')
    }

    return inBranches ? branches : null
}

// Persistent tools directory (survives workspace cleanup)
// Uses C:\BuildTools to avoid spaces in user profile paths (e.g. "ODDGAMES PHILIPPINES")
def getToolsDir() {
    if (env._BUILD_TOOLS_DIR) return env._BUILD_TOOLS_DIR
    def toolsDir = 'C:\\BuildTools'
    bat(script: "@if not exist \"${toolsDir}\" mkdir \"${toolsDir}\"", returnStatus: true)
    env._BUILD_TOOLS_DIR = toolsDir
    return toolsDir
}

/**
 * Check if a periodic update should run for the given tool.
 * Returns true if the marker file is missing or older than 14 days.
 */
def shouldRunPeriodicUpdate(String toolName) {
    def toolsDir = getToolsDir()
    def markerDir = "${toolsDir}\\.update_checks"
    def markerFile = "${markerDir}\\${toolName}"
    bat(script: "@if not exist \"${markerDir}\" mkdir \"${markerDir}\"", returnStatus: true)
    def exists = bat(script: "@if exist \"${markerFile}\" echo found", returnStdout: true).trim()
    if (exists != 'found') return true
    def daysOld = bat(script: "@powershell -NoProfile -Command \"((Get-Date) - (Get-Item '${markerFile}').LastWriteTime).Days\"", returnStdout: true).trim()
    return !daysOld.isInteger() || daysOld.toInteger() >= 14
}

/**
 * Touch the update marker file to reset the 14-day timer.
 */
def markUpdateChecked(String toolName) {
    def toolsDir = getToolsDir()
    def markerDir = "${toolsDir}\\.update_checks"
    def markerFile = "${markerDir}\\${toolName}"
    bat(script: "@if not exist \"${markerDir}\" mkdir \"${markerDir}\"", returnStatus: true)
    bat(script: "@powershell -NoProfile -Command \"New-Item -Path '${markerFile}' -ItemType File -Force | Out-Null\"", returnStatus: true)
}

/**
 * Check if preflight checks can be skipped (passed within last 24 hours).
 * @return true if preflights should be skipped, false if they need to run
 */
def shouldSkipPreflight() {
    def toolsDir = getToolsDir()
    def markerFile = "${toolsDir}\\.preflight_ok"
    def result = bat(script: """@echo off
if not exist "${markerFile}" (echo NOT_FOUND & exit /b 0)
set /p stored=<"${markerFile}"
if not "%stored%"=="${PREFLIGHT_VERSION}" (echo VERSION_MISMATCH & exit /b 0)
for /f %%a in ('powershell -NoProfile -Command "[int]((Get-Date) - (Get-Item '${markerFile}').LastWriteTime).TotalHours"') do echo %%a
exit /b 0""", returnStdout: true).trim()
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
    def markerFile = "${toolsDir}\\.preflight_ok"
    bat(script: "@echo ${PREFLIGHT_VERSION}> \"${markerFile}\"", returnStatus: true)
    echo "[Preflight] Marked as passed (v${PREFLIGHT_VERSION})"
}

// ============================================================================
// PREREQUISITE DETECTION AND INSTALLATION
// ============================================================================

/**
 * Check if winget package manager is available
 * @return true if winget is available, false otherwise
 */
def isWingetAvailable() {
    try {
        def version = bat(script: '@winget --version', returnStdout: true).trim()
        return version != ''
    } catch (Exception e) {
        // winget not in PATH - check known WindowsApps location and add to PATH if found
        try {
            def version = bat(script: '@"%LOCALAPPDATA%\\Microsoft\\WindowsApps\\winget.exe" --version', returnStdout: true).trim()
            if (version) {
                def windowsApps = bat(script: '@echo %LOCALAPPDATA%\\Microsoft\\WindowsApps', returnStdout: true).trim()
                env.PATH = "${windowsApps};${env.PATH}"
                echo "[INFO] Added ${windowsApps} to PATH for winget"
                return true
            }
        } catch (Exception e2) {}
        return false
    }
}

/**
 * Check winget availability, optionally install it
 */
def checkWinget(boolean autoInstall = false) {
    if (isWingetAvailable()) {
        def version = bat(script: '@winget --version', returnStdout: true).trim()
        return [available: true, message: "winget ${version}"]
    }

    if (autoInstall) {
        return installWinget()
    }

    return [
        available: false,
        message: 'winget not installed',
        installInstructions: 'Install from Microsoft Store (App Installer) or run: buildUtils.installWinget()'
    ]
}

/**
 * Install winget (Windows Package Manager) from GitHub releases
 */
def installWinget() {
    try {
        echo "[INFO] Installing winget from GitHub..."
        bat '''
            @echo off
            setlocal EnableDelayedExpansion

            REM Check if already installed
            winget --version >nul 2>&1 && (
                echo [OK] winget is already installed
                exit /b 0
            )

            REM Create temp directory
            set "TEMP_DIR=%TEMP%\\winget_install"
            if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"
            cd /d "%TEMP_DIR%"

            echo Downloading winget and dependencies...

            REM Download VCLibs dependency
            echo Downloading VCLibs...
            powershell -Command "Invoke-WebRequest -Uri 'https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx' -OutFile 'VCLibs.appx'"
            if errorlevel 1 (
                echo [ERROR] Failed to download VCLibs
                exit /b 1
            )

            REM Download UI.Xaml dependency from NuGet
            echo Downloading UI.Xaml...
            powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $progressPreference = 'silentlyContinue'; Invoke-WebRequest -Uri 'https://www.nuget.org/api/v2/package/Microsoft.UI.Xaml/2.8.6' -OutFile 'uixaml.zip'; Expand-Archive -Path 'uixaml.zip' -DestinationPath 'uixaml' -Force; Copy-Item 'uixaml\\tools\\AppX\\x64\\Release\\Microsoft.UI.Xaml.2.8.appx' 'UIXaml.appx'"

            REM Get latest winget release URL from GitHub API
            echo Downloading winget...
            powershell -Command "$progressPreference = 'silentlyContinue'; $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'; $asset = $release.assets | Where-Object { $_.name -match '.msixbundle$' } | Select-Object -First 1; Invoke-WebRequest -Uri $asset.browser_download_url -OutFile 'winget.msixbundle'"
            if errorlevel 1 (
                echo [ERROR] Failed to download winget
                exit /b 1
            )

            REM Also get the license
            powershell -Command "$progressPreference = 'silentlyContinue'; $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'; $license = $release.assets | Where-Object { $_.name -match 'License.*\\.xml$' } | Select-Object -First 1; if ($license) { Invoke-WebRequest -Uri $license.browser_download_url -OutFile 'license.xml' }"

            echo Installing packages...

            REM Install VCLibs
            powershell -Command "Add-AppxPackage -Path 'VCLibs.appx'"

            REM Install UI.Xaml
            if exist "UIXaml.appx" (
                powershell -Command "Add-AppxPackage -Path 'UIXaml.appx'"
            )

            REM Install winget
            if exist "license.xml" (
                powershell -Command "Add-AppxProvisionedPackage -Online -PackagePath 'winget.msixbundle' -LicensePath 'license.xml' -ErrorAction SilentlyContinue"
            )
            powershell -Command "Add-AppxPackage -Path 'winget.msixbundle'"
            if errorlevel 1 (
                echo [ERROR] Failed to install winget
                exit /b 1
            )

            REM Cleanup
            cd /d "%TEMP%"
            rmdir /s /q "%TEMP_DIR%"
            REM Verify installation
            winget --version >nul 2>&1
            if errorlevel 1 (
                echo [WARN] winget installed but may require shell restart
                exit /b 0
            )

            echo [OK] winget installed successfully
        '''

        // Check if it's now available
        if (isWingetAvailable()) {
            return [available: true, installed: true, message: 'winget installed successfully']
        } else {
            return [available: false, installed: true, message: 'winget installed - restart shell or Jenkins agent to use']
        }
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Install manually from Microsoft Store (search for "App Installer")'
        ]
    }
}

/**
 * Get required Unity modules for a platform.
 * Only list top-level modules — the -cm (child modules) flag on install/install-modules
 * automatically pulls sub-dependencies (e.g. 'android' with -cm pulls SDK, NDK, and JDK).
 */
def getRequiredUnityModules(String platform) {
    switch (platform) {
        case 'Android':
        case 'Amazon':
            return ['android']  // -cm pulls android-sdk-ndk-tools + android-open-jdk automatically
        case 'iOS':
            return ['ios']
        case 'StandaloneWindows64':
            return ['windows-il2cpp']
        case 'StandaloneLinux64':
            return ['linux-il2cpp']
        case 'Switch':
            return ['nintendo-switch']
        case 'GameCoreXboxSeries':
        case 'GameCoreXboxOne':
        case 'PS5':
            // Licensed console modules are not distributed through Unity Hub, so there is no
            // module ID to install. Presence is verified against the PlaybackEngines folder by
            // validateXboxSupport() / validatePS5Support() instead.
            return []
        default:
            return []
    }
}

/**
 * Get the PlaybackEngines base path for a Unity version.
 * Unity 6+ (6000.x) uses Editor\Data\PlaybackEngines\, older versions use PlaybackEngines\ directly.
 */
def getPlaybackEnginesPath(String version) {
    def basePath = "C:\\UnityEditors\\${version}"
    // Unity 6+ layout: Editor\Data\PlaybackEngines (Unity 6000.x+)
    def newPath = "${basePath}\\Editor\\Data\\PlaybackEngines"
    // Legacy layout: PlaybackEngines (Unity 2019-2023)
    def legacyPath = "${basePath}\\PlaybackEngines"

    try {
        def exists = bat(script: "@if exist \"${newPath}\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            echo "[INFO] PlaybackEngines path (Unity 6+ layout): ${newPath}"
            return newPath
        }
        echo "[INFO] PlaybackEngines path (legacy layout): ${legacyPath}"
        return legacyPath
    } catch (Exception e) {
        // Agent disconnect or similar — fall back to version-based detection
        echo "[WARN] Could not probe PlaybackEngines path: ${e.message}"
        def major = version.tokenize('.')[0]
        if (major.isInteger() && major.toInteger() >= 6000) {
            echo "[INFO] PlaybackEngines path (Unity 6+ assumed): ${newPath}"
            return newPath
        }
        echo "[INFO] PlaybackEngines path (legacy assumed): ${legacyPath}"
        return legacyPath
    }
}

/**
 * Check if Unity Hub is installed
 */
def checkUnityHub(boolean autoInstall = false) {
    def hubPath = null

    // Check standard path first
    def standardPath = 'C:\\Program Files\\Unity Hub\\Unity Hub.exe'
    def exists = bat(script: "@if exist \"${standardPath}\" echo found", returnStdout: true).trim()
    if (exists == 'found') {
        hubPath = standardPath
    }

    // Check LOCALAPPDATA path (use bat to get agent's env, not controller's)
    if (!hubPath) {
        def localAppDataPath = bat(script: '@echo %LOCALAPPDATA%\\Programs\\Unity Hub\\Unity Hub.exe', returnStdout: true).trim()
        exists = bat(script: "@if exist \"${localAppDataPath}\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            hubPath = localAppDataPath
        }
    }

    if (hubPath) {
        env.UNITY_HUB_PATH = hubPath

        // Ensure Unity Hub looks in C:\UnityEditors\ for editor installations
        bat(script: "cmd /c \"\"${hubPath}\" -- --headless install-path -s \"C:\\UnityEditors\"\"", returnStatus: true)

        // Periodic update check (every 14 days)
        try {
            if (shouldRunPeriodicUpdate('unity_hub')) {
                echo "[INFO] Checking for Unity Hub updates..."
                bat(script: 'winget upgrade Unity.UnityHub --silent --accept-source-agreements --accept-package-agreements', returnStatus: true)
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
        installInstructions: 'Run: winget install Unity.UnityHub'
    ]
}

/**
 * Install Unity Hub via winget
 */
def installUnityHub() {
    if (!isWingetAvailable()) {
        return [
            available: false,
            installed: false,
            message: 'winget not available for automatic installation',
            installInstructions: 'Download from https://unity.com/download or install winget (Windows Package Manager)'
        ]
    }

    try {
        echo "[INFO] Installing Unity Hub via winget..."
        bat """
            @echo off
            winget install Unity.UnityHub --scope machine --silent --accept-source-agreements --accept-package-agreements
            if errorlevel 1 (
                echo [ERROR] Unity Hub installation failed
                exit /b 1
            )
            echo [OK] Unity Hub installed
        """
        env.UNITY_HUB_PATH = 'C:\\Program Files\\Unity Hub\\Unity Hub.exe'
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
 * Check if a specific Unity version is installed, optionally install it.
 * When auto-installing and modules are provided, installs editor + modules in one Hub command.
 */
def checkUnity(String version, boolean autoInstall = false, List modules = []) {
    def unityPath = "C:\\UnityEditors\\${version}\\Editor\\Unity.exe"
    echo "[INFO] Checking for Unity at: ${unityPath}"
    def exists = bat(script: "@if exist \"${unityPath}\" echo found", returnStdout: true).trim()

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
    // No-op: Hub CLI takes ~20s to respond, not worth the delay for diagnostics
}

/**
 * Install Unity version (and optionally modules) via Unity Hub CLI.
 * When modules are provided, they're included in the install command via -m flags
 * so Hub installs everything in one pass — avoids the "editor not tracked" problem
 * that occurs when install-modules is called separately.
 */
def installUnity(String version, List modules = []) {
    if (!env.UNITY_HUB_PATH) {
        def hubCheck = checkUnityHub()
        if (!hubCheck.available) {
            return [available: false, installed: false, message: 'Unity Hub required for installation']
        }
    }

    // Lock on PC hostname — only one Unity install at a time per machine.
    // NODE_NAME isn't enough since multiple Jenkins agents can run on the same PC,
    // all sharing C:\UnityEditors\.
    def hostname = bat(script: '@hostname', returnStdout: true).trim()
    def lockName = "unity-install-${hostname}"
    echo "[INFO] Acquiring lock: ${lockName}"
    def result = null
    lock(resource: lockName) {
        result = _doInstallUnity(version, modules)
    }
    return result
}

private def _doInstallUnity(String version, List modules) {
    // Extract changeset from ProjectVersion.txt if available (needed for non-release-list versions)
    def changesetArg = ''
    if (env.UNITY_CHANGESET) {
        changesetArg = "-c ${env.UNITY_CHANGESET}"
        echo "[INFO] Using changeset: ${env.UNITY_CHANGESET}"
    }

    // If the install directory exists but Unity.exe is missing, it's a partial/corrupted install.
    // Unity Hub will refuse to install with "Editor already installed in this location" — remove it first.
    def installDir = "C:\\UnityEditors\\${version}"
    def unityExe = "${installDir}\\Editor\\Unity.exe"
    def dirExists = bat(script: "@if exist \"${installDir}\" echo found", returnStdout: true).trim()
    if (dirExists == 'found') {
        def exeExists = bat(script: "@if exist \"${unityExe}\" echo found", returnStdout: true).trim()
        if (exeExists != 'found') {
            echo "[WARN] Removing partial Unity installation at ${installDir} (Unity.exe missing)"
            bat script: "rmdir /s /q \"${installDir}\""
        }
    }

    try {
        def unityPath = "C:\\UnityEditors\\${version}\\Editor\\Unity.exe"
        def hubExe = env.UNITY_HUB_PATH.split('\\\\').last()
        def moduleArgs = modules ? modules.collect { "-m ${it}" }.join(' ') + ' -cm' : ''
        def cmd = "cmd /c \"\"${env.UNITY_HUB_PATH}\" -- --headless install --version ${version} ${changesetArg} ${moduleArgs}\""

        if (modules) {
            echo "[INFO] Installing Unity ${version} with modules: ${modules.join(', ')}"
        }

        // Retry install for up to 2 hours — Unity Hub downloads can fail on transient network issues
        def maxAttempts = 4
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            echo "[INFO] Installing Unity ${version} via Unity Hub CLI (attempt ${attempt}/${maxAttempts})..."

            // Kill any existing Unity Hub processes to avoid conflicts
            bat script: "@taskkill /f /im \"${hubExe}\" >nul 2>&1 || exit /b 0"

            // Run Hub install with Jenkins timeout guard.
            // Unity Hub CLI hangs at "validating installation..." after install finishes — it never exits.
            // The timeout() step will interrupt it, then we verify Unity.exe appeared on disk.
            def exitCode = 0
            try {
                timeout(time: 30, unit: 'MINUTES') {
                    exitCode = bat(script: "@${cmd} 2>&1", returnStatus: true)
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                echo "[WARN] Unity Hub install timed out — checking if Unity.exe is present anyway..."
            }
            if (exitCode != 0) {
                echo "[WARN] Unity Hub install exited with code ${exitCode}"
            }

            // Kill Hub process (may still be running after timeout)
            bat script: "@taskkill /f /im \"${hubExe}\" >nul 2>&1 || exit /b 0"

            // Verify Unity.exe exists on disk — this is the source of truth,
            // not the Hub's exit code or output
            def exists = bat(script: "@if exist \"${unityPath}\" echo found", returnStdout: true).trim()
            if (exists == 'found') {
                echo "[OK] Unity ${version} installed at ${unityPath}"
                return [available: true, installed: true, message: "Unity ${version} installed successfully"]
            }

            echo "[ERROR] Unity.exe not found at: ${unityPath}"
            if (attempt < maxAttempts) {
                echo "[INFO] Retrying in 60 seconds..."
                sleep(60)
            }
        }

        logInstalledEditors()
        return [available: false, installed: false, message: "Installation failed after ${maxAttempts} attempts — Unity.exe not found at ${unityPath}"]
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

    // Check for module markers in Unity installation
    def playbackEngines = getPlaybackEnginesPath(version)
    def missingModules = []
    def foundModules = []

    // Marker directories to check inside PlaybackEngines for each module.
    // Nintendo Switch is NOT included here - it's validated separately by validateNintendoSwitchSupport()
    // because the addon installs differently and can't be auto-installed.
    def moduleMarkers = [
        'android': 'AndroidPlayer',
        'android-sdk-ndk-tools': 'AndroidPlayer\\SDK',
        'android-open-jdk': 'AndroidPlayer\\OpenJDK',
        'ios': 'iOSSupport',
        'windows-il2cpp': 'WindowsStandaloneSupport\\Variations\\win64_player_nondevelopment_il2cpp',
        'linux-il2cpp': 'LinuxStandaloneSupport\\Variations\\linux64_player_nondevelopment_il2cpp'
    ]

    echo "[INFO] Checking Unity modules in: ${playbackEngines}"

    modules.each { module ->
        def marker = moduleMarkers[module]
        if (marker) {
            def checkPath = "${playbackEngines}\\${marker}"
            def exists = bat(script: "@if exist \"${checkPath}\" echo found", returnStdout: true).trim()
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

    // Install missing modules
    return installUnityModules(version, missingModules)
}

/**
 * Install Unity modules via Unity Hub CLI.
 * First tries install-modules (for Hub-tracked editors), then falls back to
 * install --version -m (which re-registers the editor and installs modules in one pass).
 */
def installUnityModules(String version, List modules) {
    if (!env.UNITY_HUB_PATH) {
        def hubCheck = checkUnityHub()
        if (!hubCheck.available) {
            return [available: false, installed: false, message: 'Unity Hub required for module installation']
        }
    }

    // Lock on PC hostname — only one Unity install at a time per machine
    def hostname = bat(script: '@hostname', returnStdout: true).trim()
    def lockName = "unity-install-${hostname}"
    echo "[INFO] Acquiring lock: ${lockName}"
    def result = null
    lock(resource: lockName) {
        result = _doInstallUnityModules(version, modules)
    }
    return result
}

private def _doInstallUnityModules(String version, List modules) {
    def moduleArgs = modules.collect { "-m ${it}" }.join(' ')
    def installDir = "C:\\UnityEditors\\${version}"
    def hubExe = env.UNITY_HUB_PATH.split('\\\\').last()
    def changesetArg = env.UNITY_CHANGESET ? "-c ${env.UNITY_CHANGESET}" : ''
    def exitCode = 0

    // Try install-modules first (works when Hub tracks the editor).
    // Fall back to delete + reinstall if Hub doesn't recognize the editor.
    bat script: "@taskkill /f /im \"${hubExe}\" >nul 2>&1 || exit /b 0"

    def cmd = "cmd /c \"\"${env.UNITY_HUB_PATH}\" -- --headless install-modules --version ${version} ${moduleArgs} -cm\""
    echo "[INFO] Running: ${cmd}"
    def output = ''
    try {
        timeout(time: 15, unit: 'MINUTES') {
            // Use '|| exit /b 0' so returnStdout works even when Hub returns non-zero exit code.
            // We check the output text for errors instead of relying on exit code.
            output = bat(script: "@${cmd} 2>&1 || exit /b 0", returnStdout: true).trim()
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        echo "[WARN] Unity Hub install-modules timed out — checking if modules are present anyway..."
    }
    if (output) { echo output }
    bat script: "@taskkill /f /im \"${hubExe}\" >nul 2>&1 || exit /b 0"

    // If Hub doesn't recognize the editor, try `install` with -m flags to re-register it.
    // Don't delete the editor — that triggers a full re-download (~5GB+).
    if (output.contains('only supported for editors installed with Unity Hub') || output.contains('No modules found for this editor') || output.contains('No editor found for version')) {
        echo "[WARN] Hub doesn't track this editor — trying install command to re-register and add modules..."
        def installCmd = "cmd /c \"\"${env.UNITY_HUB_PATH}\" -- --headless install --version ${version} ${changesetArg} ${moduleArgs} -cm\""
        echo "[INFO] Running: ${installCmd}"
        try {
            timeout(time: 30, unit: 'MINUTES') {
                exitCode = bat(script: "@${installCmd} 2>&1", returnStatus: true)
            }
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            echo "[WARN] Unity Hub install timed out — checking if modules are present anyway..."
        }
        bat script: "@taskkill /f /im \"${hubExe}\" >nul 2>&1 || exit /b 0"
    }

    // Verify modules are actually present by checking marker directories.
    // This is the source of truth - Unity Hub may return errors even when modules are already installed
    // (e.g. "Validation Failed" when another build has a file lock on the module directory).
    def playbackEngines = getPlaybackEnginesPath(version)
    def moduleMarkers = [
        'android': 'AndroidPlayer',
        'android-sdk-ndk-tools': 'AndroidPlayer\\SDK',
        'android-open-jdk': 'AndroidPlayer\\OpenJDK',
        'ios': 'iOSSupport',
        'windows-il2cpp': 'WindowsStandaloneSupport\\Variations\\win64_player_nondevelopment_il2cpp',
        'linux-il2cpp': 'LinuxStandaloneSupport\\Variations\\linux64_player_nondevelopment_il2cpp'
    ]

    def stillMissing = modules.findAll { module ->
        def marker = moduleMarkers[module]
        if (!marker) return false  // No marker to check
        def checkPath = "${playbackEngines}\\${marker}"
        def exists = bat(script: "@if exist \"${checkPath}\" echo found", returnStdout: true).trim()
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
 * which is the only reliable method in non-interactive CI environments
 * (piping 'y' to sdkmanager is fragile on Windows Jenkins agents).
 * Then installs the target SDK platform if the project specifies one.
 */
def acceptAndroidSdkLicenses() {
    def sdkPath = env.ANDROID_HOME
    if (!sdkPath) {
        if (env.UNITY_VERSION) {
            sdkPath = "${getPlaybackEnginesPath(env.UNITY_VERSION)}\\AndroidPlayer\\SDK"
        }
    }
    if (!sdkPath) {
        echo "[WARN] Cannot accept Android SDK licenses: SDK path not found"
        return
    }

    // Verify SDK path exists before attempting to write licenses
    def sdkExists = bat(script: "@if exist \"${sdkPath}\" echo found", returnStdout: true).trim()
    if (sdkExists != 'found') {
        echo "[WARN] Android SDK path does not exist: ${sdkPath}"
        echo "[WARN] Install the Android build support module first (Unity Hub > Installs > Add Modules)"
        return
    }

    echo "[INFO] Accepting Android SDK licenses at ${sdkPath}..."

    // Write license acceptance files directly - this is how sdkmanager persists
    // accepted licenses. Each file contains newline-separated hash(es) of the
    // license text. These are the standard Google license hashes.
    try {
        bat """
            @echo off
            if not exist "${sdkPath}\\licenses" mkdir "${sdkPath}\\licenses"
            echo [INFO] Writing android-sdk-license...
            (
                echo 8933bad161af4178b1185d1a37fbf41ea5269c55
                echo d56f5187479451eabf01fb78af6dfcb131a6481e
                echo 24333f8a63b6825ea9c5514f83c2829b004d1fee
            ) > "${sdkPath}\\licenses\\android-sdk-license"

            echo [INFO] Writing android-sdk-preview-license...
            (
                echo 84831b9409646a918e30573bab4c9c91346d8abd
            ) > "${sdkPath}\\licenses\\android-sdk-preview-license"

            echo [OK] License files written to ${sdkPath}\\licenses
        """
        echo "[OK] Android SDK licenses accepted"
    } catch (Exception e) {
        echo "[WARN] Failed to write Android SDK license files: ${e.message}"
        echo "[WARN] Check permissions on ${sdkPath} for the Jenkins service account"
    }

    // Locate sdkmanager for platform installation (prefer modern cmdline-tools over legacy)
    def sdkmanager = null
    def cmdlineToolsPath = "${sdkPath}\\cmdline-tools\\latest\\bin\\sdkmanager.bat"
    def legacyToolsPath = "${sdkPath}\\tools\\bin\\sdkmanager.bat"

    def cmdlineExists = bat(script: "@if exist \"${cmdlineToolsPath}\" echo found", returnStdout: true).trim()
    if (cmdlineExists == 'found') {
        sdkmanager = cmdlineToolsPath
    } else {
        def legacyExists = bat(script: "@if exist \"${legacyToolsPath}\" echo found", returnStdout: true).trim()
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
        def unityJdk = "${getPlaybackEnginesPath(env.UNITY_VERSION)}\\AndroidPlayer\\OpenJDK"
        def jdkExists = bat(script: "@if exist \"${unityJdk}\\bin\\java.exe\" echo found", returnStdout: true).trim()
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
            def projectSettingsFile = "${projectPath}\\ProjectSettings\\ProjectSettings.asset"
            def settingsExists = bat(script: "@if exist \"${projectSettingsFile}\" echo found", returnStdout: true).trim()
            if (settingsExists == 'found') {
                def targetSdk = bat(script: """
                    @echo off
                    for /f "tokens=2" %%a in ('findstr /C:"AndroidTargetSdkVersion:" "${projectSettingsFile}" 2^>nul') do echo %%a
                    exit /b 0
                """, returnStdout: true).trim()

                if (targetSdk && targetSdk.isInteger() && targetSdk.toInteger() > 0) {
                    // Check if platform is already installed before invoking sdkmanager
                    def platformDir = "${sdkPath}\\platforms\\android-${targetSdk}"
                    def platformExists = bat(script: "@if exist \"${platformDir}\\android.jar\" echo found", returnStdout: true).trim()
                    if (platformExists == 'found') {
                        echo "[OK] Android SDK platform ${targetSdk} already installed"
                    } else {
                        echo "[INFO] Unity project targets Android SDK ${targetSdk}, installing platform..."
                        def sdkInstallResult = bat(script: """
                            @echo off
                            ${javaHome ? "set \"JAVA_HOME=${javaHome}\"\nset \"PATH=%JAVA_HOME%\\bin;%PATH%\"" : ''}
                            "${sdkmanager}" "platforms;android-${targetSdk}" --sdk_root="${sdkPath}" 2>&1
                        """, returnStatus: true)
                        if (sdkInstallResult == 0) {
                            echo "[OK] Android SDK platform ${targetSdk} installed"
                        } else {
                            echo "[WARN] sdkmanager failed to install Android SDK platform ${targetSdk} (exit ${sdkInstallResult}) — Unity may still build if the platform is already present"
                        }
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
 * Check if Ruby is installed, optionally provide install instructions
 */
def checkRuby(boolean autoInstall = false) {
    // Single bat call: check PATH then common installation paths
    def result = bat(
        script: '''@echo off
            setlocal EnableDelayedExpansion
            echo Checking Ruby installation... >&2

            REM 1. Check PATH
            set "RVER="
            for /f "delims=" %%v in ('ruby --version 2^>nul') do if not defined RVER set "RVER=%%v"
            if defined RVER (
                echo !RVER! | findstr /C:"ruby" >nul
                if not errorlevel 1 (
                    echo   Found ruby in PATH: !RVER! >&2
                    echo FOUND_PATH
                    echo !RVER!
                    exit /b 0
                )
            )

            REM 2. Check common installation paths
            for %%d in ("C:\\Ruby32-x64\\bin" "C:\\Ruby31-x64\\bin" "C:\\Ruby30-x64\\bin") do (
                if exist "%%~d\\ruby.exe" (
                    set "RVER="
                    for /f "delims=" %%v in ('"%%~d\\ruby.exe" --version 2^>nul') do if not defined RVER set "RVER=%%v"
                    echo   Found Ruby at %%~d: !RVER! >&2
                    echo FOUND_DIR
                    echo %%~d
                    echo !RVER!
                    exit /b 0
                )
            )

            echo   Ruby not found >&2
            echo NOT_FOUND
            exit /b 0''',
        returnStdout: true
    ).trim()

    echo "  checkRuby stdout: ${result}"
    def lines = result.readLines()
    def statusCode = lines[0]

    if (statusCode == 'FOUND_PATH' && lines.size() >= 2) {
        return [available: true, message: lines[1], version: lines[1]]
    }

    if (statusCode == 'FOUND_DIR' && lines.size() >= 3) {
        def rubyDir = lines[1]
        env.PATH = "${rubyDir};${env.PATH}"
        echo "[INFO] Found Ruby at ${rubyDir}, added to PATH"
        return [available: true, message: lines[2], version: lines[2]]
    }

    if (autoInstall) {
        return installRuby()
    }

    return [
        available: false,
        message: 'Ruby not installed',
        installInstructions: 'Download from https://rubyinstaller.org/downloads/ (Ruby+Devkit recommended)'
    ]
}

/**
 * Install Ruby using winget or provide manual instructions
 */
def installRuby() {
    if (!isWingetAvailable()) {
        return [
            available: false,
            installed: false,
            message: 'winget not available for automatic installation',
            installInstructions: 'Download from https://rubyinstaller.org/downloads/ or install winget (Windows Package Manager)'
        ]
    }

    try {
        echo "[INFO] Attempting to install Ruby via winget..."
        bat """
            @echo off
            winget install --id RubyInstallerTeam.RubyWithDevKit.3.2 --scope machine --silent --accept-source-agreements --accept-package-agreements
            if errorlevel 1 (
                echo [ERROR] Ruby installation via winget failed
                exit /b 1
            )
            echo [OK] Ruby installed, restart Jenkins agent to update PATH
        """
        return [available: false, installed: true, message: 'Ruby installed, restart required for PATH update']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: 'Automatic installation failed',
            installInstructions: 'Download from https://rubyinstaller.org/downloads/'
        ]
    }
}

/**
 * Check if Fastlane is installed
 */
def checkFastlane(boolean autoInstall = false) {
    try {
        def version = bat(script: '@fastlane --version', returnStdout: true).trim()
        if (version.contains('fastlane')) {
            // Periodic update check (every 14 days)
            try {
                if (shouldRunPeriodicUpdate('fastlane')) {
                    echo "[INFO] Checking for Fastlane updates..."
                    bat(script: 'gem update fastlane --no-document', returnStatus: true)
                    markUpdateChecked('fastlane')
                }
            } catch (Exception e) {
                echo "[WARN] Fastlane update check failed: ${e.message}"
            }

            return [available: true, message: version.split('\n')[0]]
        }
    } catch (Exception e) {
        // Fastlane not in PATH
    }

    // Check if Ruby is available for gem install — auto-install if needed
    def rubyCheck = checkRuby(autoInstall)
    if (!rubyCheck.available) {
        return [
            available: false,
            message: 'Fastlane requires Ruby',
            installInstructions: 'Install Ruby first, then: gem install fastlane'
        ]
    }

    if (autoInstall) {
        return installFastlane()
    }

    return [
        available: false,
        message: 'Fastlane not installed',
        installInstructions: 'Run: gem install fastlane'
    ]
}

/**
 * Install Fastlane via gem
 */
def installFastlane() {
    try {
        echo "[INFO] Installing Fastlane via gem..."
        bat """
            @echo off
            gem install fastlane --no-document
            if errorlevel 1 (
                echo [ERROR] Fastlane installation failed
                exit /b 1
            )
            fastlane --version
            echo [OK] Fastlane installed
        """
        markUpdateChecked('fastlane')
        return [available: true, installed: true, message: 'Fastlane installed successfully']
    } catch (Exception e) {
        return [available: false, installed: false, message: "Installation failed: ${e.message}"]
    }
}

/**
 * Check Android SDK installation
 */
def checkAndroidSdk() {
    // Check ANDROID_HOME or ANDROID_SDK_ROOT (use env.*, not System.getenv which returns controller's env)
    def sdkPath = env.ANDROID_HOME ?: env.ANDROID_SDK_ROOT ?: ''

    if (sdkPath) {
        def exists = bat(script: "@if exist \"${sdkPath}\\platform-tools\\adb.exe\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            return [available: true, message: "Found at ${sdkPath}"]
        }
    }

    // Check Unity's bundled Android SDK
    if (env.UNITY_VERSION) {
        def unityAndroidSdk = "${getPlaybackEnginesPath(env.UNITY_VERSION)}\\AndroidPlayer\\SDK"
        def exists = bat(script: "@if exist \"${unityAndroidSdk}\\platform-tools\\adb.exe\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            env.ANDROID_HOME = unityAndroidSdk
            return [available: true, message: "Using Unity bundled SDK at ${unityAndroidSdk}"]
        }
    }

    return [
        available: false,
        message: 'Android SDK not found',
        installInstructions: 'Install Unity with Android Build Support module (includes SDK)'
    ]
}

/**
 * Check PlasticSCM installation
 */
def checkPlasticSCM(boolean autoInstall = false) {
    try {
        def version = bat(script: '@cm version', returnStdout: true).trim()
        if (version) {
            // Periodic update check (every 14 days)
            try {
                if (shouldRunPeriodicUpdate('plastic_scm')) {
                    echo "[INFO] Checking for Plastic SCM updates..."
                    bat(script: 'winget upgrade Codice.PlasticSCM --silent --accept-source-agreements --accept-package-agreements', returnStatus: true)
                    markUpdateChecked('plastic_scm')
                }
            } catch (Exception e) {
                echo "[WARN] Plastic SCM update check failed: ${e.message}"
            }

            // Check if authenticated
            try {
                def whoami = bat(script: '@cm whoami', returnStdout: true).trim()
                if (whoami && !whoami.contains('not logged')) {
                    return [available: true, message: "${version} (logged in as: ${whoami})"]
                }
                return [available: true, message: "${version} (not authenticated - preflight will handle auth)"]
            } catch (Exception e) {
                return [available: true, message: version]
            }
        }
    } catch (Exception e) {
        // PlasticSCM not in PATH
    }

    // Check common installation paths
    def plasticPaths = [
        'C:\\Program Files\\PlasticSCM5\\client\\cm.exe',
        'C:\\Program Files\\Unity\\Hub\\Editor\\*\\Editor\\Data\\Tools\\PlasticSCM\\cm.exe'
    ]

    for (path in plasticPaths) {
        def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            def dir = path.replaceAll(/\\[^\\]+$/, '')
            echo "[INFO] Adding PlasticSCM to PATH: ${dir}"
            env.PATH = "${dir};${env.PATH}"
            return [available: true, message: "PlasticSCM found at ${path} (added to PATH)"]
        }
    }

    if (autoInstall) {
        return installPlasticSCM()
    }

    return [
        available: false,
        message: 'PlasticSCM not installed',
        installInstructions: 'Run: winget install Codice.PlasticSCM'
    ]
}

/**
 * Install PlasticSCM via winget
 */
def installPlasticSCM() {
    if (!isWingetAvailable()) {
        return [
            available: false,
            installed: false,
            message: 'winget not available for automatic installation',
            installInstructions: 'Download from https://www.plasticscm.com/download or install winget (Windows Package Manager)'
        ]
    }

    try {
        echo "[INFO] Installing PlasticSCM via winget..."
        bat """
            @echo off
            winget install Codice.PlasticSCM --scope machine --silent --accept-source-agreements --accept-package-agreements
            if errorlevel 1 (
                echo [ERROR] PlasticSCM installation failed
                exit /b 1
            )
            echo [OK] PlasticSCM installed - restart Jenkins agent and run cm login to authenticate
        """
        markUpdateChecked('plastic_scm')
        return [available: false, installed: true, message: 'PlasticSCM installed - restart agent and run cm login']
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
 * Check Git installation
 */
def checkGit(boolean autoInstall = false) {
    try {
        def version = bat(script: '@git --version', returnStdout: true).trim()
        if (version.contains('git version')) {
            return [available: true, message: version]
        }
    } catch (Exception e) {
        // Git not in PATH
    }

    // Check common installation paths
    def gitPaths = [
        'C:\\Program Files\\Git\\bin\\git.exe',
        'C:\\Program Files (x86)\\Git\\bin\\git.exe'
    ]

    for (path in gitPaths) {
        def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
        if (exists == 'found') {
            return [
                available: false,
                message: "Git found at ${path} but not in PATH",
                installInstructions: "Add ${path.replace('\\git.exe', '')} to system PATH"
            ]
        }
    }

    if (autoInstall) {
        return installGit()
    }

    return [
        available: false,
        message: 'Git not installed',
        installInstructions: 'Run: winget install Git.Git'
    ]
}

/**
 * Install Git via winget
 */
def installGit() {
    if (!isWingetAvailable()) {
        return [
            available: false,
            installed: false,
            message: 'winget not available for automatic installation',
            installInstructions: 'Download from https://git-scm.com/download/win or install winget first'
        ]
    }

    try {
        echo "[INFO] Installing Git via winget..."
        bat """
            @echo off
            winget install Git.Git --scope machine --silent --accept-source-agreements --accept-package-agreements
            if errorlevel 1 (
                echo [ERROR] Git installation failed
                exit /b 1
            )
            echo [OK] Git installed - restart Jenkins agent to update PATH
        """
        return [available: false, installed: true, message: 'Git installed - restart agent to update PATH']
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Download from https://git-scm.com/download/win'
        ]
    }
}

/**
 * Check Node.js/npm installation
 * Checks PATH first, then common installation directories
 */
def checkNodeJS(boolean autoInstall = false) {
    // Single bat call checks PATH, then common install locations
    def result = bat(
        script: '''@echo off
            setlocal EnableDelayedExpansion
            echo Checking Node.js installation... >&2

            REM 1. Check PATH
            set "NVER="
            for /f "delims=" %%v in ('node --version 2^>nul') do if not defined NVER set "NVER=%%v"
            if defined NVER (
                set "NPM="
                for /f "delims=" %%n in ('npm --version 2^>nul') do if not defined NPM set "NPM=%%n"
                echo   Found Node.js !NVER! in PATH >&2
                echo FOUND_PATH
                echo !NVER!
                echo !NPM!
                exit /b 0
            )

            REM 2. Check common install paths
            for %%d in ("C:\\Program Files\\nodejs" "C:\\Program Files (x86)\\nodejs") do (
                if exist "%%~d\\node.exe" (
                    echo   Found Node.js at %%~d >&2
                    set "NVER="
                    for /f "delims=" %%v in ('"%%~d\\node.exe" --version 2^>nul') do if not defined NVER set "NVER=%%v"
                    set "NPM="
                    for /f "delims=" %%n in ('"%%~d\\npm.cmd" --version 2^>nul') do if not defined NPM set "NPM=%%n"
                    echo FOUND_DIR
                    echo %%~d
                    echo !NVER!
                    echo !NPM!
                    exit /b 0
                )
            )

            echo NOT_FOUND
            exit /b 0''',
        returnStdout: true
    ).trim()

    echo "  checkNodeJS stdout: ${result}"
    def lines = result.readLines()
    def statusCode = lines[0]

    if (statusCode == 'FOUND_PATH' && lines.size() >= 3) {
        // Resolve Node.js directory so downstream tools (firebase-tools) can install alongside node.exe
        if (!env.NODEJS_HOME) {
            def nodeDir = bat(script: '@for /f "delims=" %%i in (\'where node.exe 2^>nul\') do @echo %%~dpi& goto :eof', returnStdout: true).trim().replaceAll(/\\$/, '')
            if (nodeDir) {
                env.NODEJS_HOME = nodeDir
                echo "[INFO] Node.js directory: ${nodeDir}"
            }
        }
        return [available: true, message: "Node.js ${lines[1]}, npm ${lines[2]}"]
    }

    if (statusCode == 'FOUND_DIR' && lines.size() >= 4) {
        def nodePath = lines[1]
        env.NODEJS_HOME = nodePath
        env.PATH = "${nodePath};${env.PATH}"
        echo "[INFO] Found Node.js at ${nodePath}, added to PATH"
        return [available: true, message: "Node.js ${lines[2]}, npm ${lines[3]} (found at ${nodePath})"]
    }

    if (autoInstall) {
        return installNodeJS()
    }

    return [
        available: false,
        message: 'Node.js/npm not installed',
        installInstructions: 'Run: winget install OpenJS.NodeJS.LTS'
    ]
}

/**
 * Install Node.js (includes npm) via winget or chocolatey
 * Note: Auto-install is best-effort - Jenkins service accounts often lack access to winget
 */
def installNodeJS() {
    // Try winget if available
    if (isWingetAvailable()) {
        try {
            echo "[INFO] Installing Node.js via winget..."
            bat """
                @echo off
                winget install OpenJS.NodeJS.LTS --scope machine --silent --accept-source-agreements --accept-package-agreements
                if errorlevel 1 (
                    echo [ERROR] Node.js installation failed
                    exit /b 1
                )
            """
        } catch (Exception e) {
            echo "[WARN] winget install failed: ${e.message}"
        }
    } else {
        // Try chocolatey as fallback
        try {
            def chocoVersion = bat(script: '@choco --version', returnStdout: true).trim()
            if (chocoVersion) {
                echo "[INFO] Installing Node.js via Chocolatey..."
                bat """
                    @echo off
                    choco install nodejs-lts -y
                    if errorlevel 1 (
                        echo [ERROR] Node.js installation failed
                        exit /b 1
                    )
                """
            }
        } catch (Exception e) {
            // Chocolatey not available
        }
    }

    // Refresh PATH from registry — winget/choco update the system PATH but the
    // current Jenkins process still has the old PATH from when the agent started.
    def refreshedPath = bat(script: '''@echo off
        for /f "tokens=2*" %%a in ('reg query "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment" /v Path 2^>nul') do set "SYS_PATH=%%b"
        for /f "tokens=2*" %%a in ('reg query "HKCU\\Environment" /v Path 2^>nul') do set "USR_PATH=%%b"
        echo %SYS_PATH%;%USR_PATH%''', returnStdout: true).trim()
    if (refreshedPath) {
        env.PATH = refreshedPath
        echo "[INFO] Refreshed PATH from registry"
    }

    // Check common install locations in case PATH refresh missed it
    def nodePaths = ['C:\\Program Files\\nodejs', 'C:\\Program Files (x86)\\nodejs']
    for (nodePath in nodePaths) {
        def found = bat(script: "@if exist \"${nodePath}\\node.exe\" echo found", returnStdout: true).trim()
        if (found == 'found') {
            if (!env.PATH.contains(nodePath)) {
                env.PATH = "${nodePath};${env.PATH}"
            }
            env.NODEJS_HOME = nodePath
            def ver = bat(script: "@\"${nodePath}\\node.exe\" --version", returnStdout: true).trim()
            echo "[OK] Node.js ${ver} installed at ${nodePath}"
            return [available: true, installed: true, message: "Node.js ${ver} (installed at ${nodePath})"]
        }
    }

    return [
        available: false,
        installed: false,
        message: 'Node.js/npm not available and auto-install failed'
    ]
}

// ============================================================================
// DOWNLOADABLE TOOLS (rclone, SteamCMD, UnityDataTool)
// ============================================================================

/**
 * Check if rclone is installed in tools directory
 */
def checkRclone(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def rclonePath = "${toolsDir}\\rclone\\rclone.exe"

    // Single bat call: check existence and get version
    def result = bat(
        script: """@echo off
            setlocal EnableDelayedExpansion
            echo Checking rclone installation... >&2
            if not exist "${rclonePath}" (echo   rclone not found >&2& echo NOT_FOUND& exit /b 0)
            echo   rclone found at ${rclonePath} >&2
            set "VER="
            for /f "delims=" %%v in ('${rclonePath} version 2^>nul ^| findstr /B "rclone"') do if not defined VER set "VER=%%v"
            if defined VER (echo FOUND& echo !VER!) else (echo FOUND)""",
        returnStdout: true
    ).trim()

    echo "  checkRclone stdout: ${result}"
    def lines = result.readLines()
    if (lines[0] == 'FOUND') {
        env.RCLONE_PATH = rclonePath
        def version = lines.size() >= 2 ? lines[1] : 'rclone found'
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
    def rcloneDir = "${toolsDir}\\rclone"

    echo "[INFO] Installing rclone (latest)..."
    bat """
        @echo off
        setlocal EnableDelayedExpansion

        set "RCLONE_DIR=${rcloneDir}"
        set "TEMP_DIR=%TEMP%\\rclone_install"

        if not exist "%RCLONE_DIR%" mkdir "%RCLONE_DIR%"
        if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

        echo Fetching latest rclone release...
        powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \$ProgressPreference = 'SilentlyContinue'; \$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/rclone/rclone/releases/latest'; \$asset = \$release.assets | Where-Object { \$_.name -match 'windows-amd64\\.zip\$' } | Select-Object -First 1; Invoke-WebRequest -Uri \$asset.browser_download_url -OutFile '%TEMP_DIR%\\rclone.zip'; \$release.tag_name | Out-File -FilePath '%TEMP_DIR%\\version.txt' -NoNewline"
        if errorlevel 1 (
            echo [ERROR] Failed to download rclone
            exit /b 1
        )

        set /p RCLONE_VERSION=<"%TEMP_DIR%\\version.txt"
        echo Downloaded rclone %RCLONE_VERSION%

        echo Extracting...
        powershell -Command "Expand-Archive -Path '%TEMP_DIR%\\rclone.zip' -DestinationPath '%TEMP_DIR%' -Force"
        if errorlevel 1 (
            echo [ERROR] Failed to extract rclone
            exit /b 1
        )

        for /d %%d in ("%TEMP_DIR%\\rclone-*") do (
            copy /y "%%d\\rclone.exe" "%RCLONE_DIR%\\rclone.exe"
        )
        if errorlevel 1 (
            echo [ERROR] Failed to copy rclone
            exit /b 1
        )

        rmdir /s /q "%TEMP_DIR%"
        echo [OK] rclone installed to %RCLONE_DIR%
    """

    env.RCLONE_PATH = "${rcloneDir}\\rclone.exe"
    return [available: true, installed: true, message: "rclone installed", path: env.RCLONE_PATH]
}

/**
 * Check if SteamCMD is installed in tools directory
 */
def checkSteamCMD(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def steamCmdPath = "${toolsDir}\\steamcmd\\steamcmd.exe"

    def exists = bat(script: "@if exist \"${steamCmdPath}\" echo found", returnStdout: true).trim()
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
        installInstructions: "Run: buildUtils.installSteamCMD() or download from https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip"
    ]
}

/**
 * Download and install SteamCMD to tools directory
 */
def installSteamCMD() {
    def toolsDir = getToolsDir()
    def steamCmdDir = "${toolsDir}\\steamcmd"

    try {
        echo "[INFO] Installing SteamCMD..."
        bat """
            @echo off
            setlocal EnableDelayedExpansion

            set "STEAMCMD_DIR=${steamCmdDir}"
            set "TEMP_DIR=%TEMP%\\steamcmd_install"

            if not exist "%STEAMCMD_DIR%" mkdir "%STEAMCMD_DIR%"
            if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

            echo Downloading SteamCMD...
            powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri 'https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip' -OutFile '%TEMP_DIR%\\steamcmd.zip'"
            if errorlevel 1 (
                echo [ERROR] Failed to download SteamCMD
                exit /b 1
            )

            echo Extracting...
            powershell -Command "Expand-Archive -Path '%TEMP_DIR%\\steamcmd.zip' -DestinationPath '%STEAMCMD_DIR%' -Force"
            if errorlevel 1 (
                echo [ERROR] Failed to extract SteamCMD
                exit /b 1
            )

            rmdir /s /q "%TEMP_DIR%"
            echo Running SteamCMD initial update...
            "%STEAMCMD_DIR%\\steamcmd.exe" +quit
            REM Ignore SteamCMD exit code - it often returns non-zero even on successful update

            if not exist "%STEAMCMD_DIR%\\steamcmd.exe" (
                echo [ERROR] SteamCMD executable not found after installation
                exit /b 1
            )

            echo [OK] SteamCMD installed to %STEAMCMD_DIR%
            exit /b 0
        """

        env.STEAMCMD_PATH = "${steamCmdDir}\\steamcmd.exe"
        return [available: true, installed: true, message: "SteamCMD installed", path: env.STEAMCMD_PATH]
    } catch (Exception e) {
        return [
            available: false,
            installed: false,
            message: "Installation failed: ${e.message}",
            installInstructions: 'Download from https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip'
        ]
    }
}

/**
 * Check that the pinned UnityDataTool release (common.unityDataToolVersion()) is installed in
 * the tools directory, (re)installing it when missing or a different version.
 */
def checkUnityDataTool(boolean autoInstall = false) {
    def toolsDir = getToolsDir()
    def toolDir = "${toolsDir}\\unity_data_tool"
    def toolPath = "${toolDir}\\UnityDataTool.exe"
    def versionFile = "${toolDir}\\.version"
    def wanted = common.unityDataToolVersion()

    // Outputs: MISSING | INSTALLED + version line
    def status = bat(
        script: """@echo off
            if not exist "${toolPath}" (echo MISSING& exit /b 0)
            echo INSTALLED
            if exist "${versionFile}" type "${versionFile}"
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
 *
 * NOTE: The bundled UnityFileSystemApi.dll is NOT backwards compatible across Unity versions
 * (see https://github.com/Unity-Technologies/UnityDataTools/issues/26).
 * runUnityDataTool() replaces the bundled DLL with the one from the Unity editor that built
 * the project. Releases from v2.3.0 refuse any DLL older than Unity 6.7, which is why the
 * version is pinned (common.unityDataToolVersion()) rather than tracking latest.
 */
def installUnityDataTool() {
    def toolsDir = getToolsDir()
    def toolDir = "${toolsDir}\\unity_data_tool"
    def wanted = common.unityDataToolVersion()

    echo "[INFO] Installing UnityDataTool ${wanted}..."

    bat """
        @echo off
        setlocal EnableDelayedExpansion

        set "TOOL_DIR=${toolDir}"
        set "TEMP_DIR=%TEMP%\\unity_data_tool_install"

        REM Wipe the old install so files from another release can't linger
        if exist "%TOOL_DIR%" rmdir /s /q "%TOOL_DIR%"
        mkdir "%TOOL_DIR%"
        if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

        echo Fetching UnityDataTool ${wanted}...
        powershell -NoProfile -Command "\$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \$ProgressPreference = 'SilentlyContinue'; \$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/Unity-Technologies/UnityDataTools/releases/tags/${wanted}'; \$asset = \$release.assets | Where-Object { \$_.name -match 'windows-x64.*\\.zip\$' } | Select-Object -First 1; if (-not \$asset) { throw 'no windows-x64 zip in release ${wanted}' }; Invoke-WebRequest -Uri \$asset.browser_download_url -OutFile '%TEMP_DIR%\\tool.zip'"
        if errorlevel 1 (
            echo [ERROR] Failed to download UnityDataTool ${wanted}
            exit /b 1
        )

        echo Extracting...
        powershell -NoProfile -Command "Expand-Archive -Path '%TEMP_DIR%\\tool.zip' -DestinationPath '%TOOL_DIR%' -Force"
        if errorlevel 1 (
            echo [ERROR] Failed to extract UnityDataTool
            exit /b 1
        )
        if not exist "%TOOL_DIR%\\UnityDataTool.exe" (
            echo [ERROR] UnityDataTool.exe not at the root of the ${wanted} zip
            exit /b 1
        )

        REM Save version tag for the pinned-version check
        powershell -NoProfile -Command "'${wanted}' | Out-File -FilePath '%TOOL_DIR%\\.version' -NoNewline -Encoding ascii"

        rmdir /s /q "%TEMP_DIR%"
        echo [OK] UnityDataTool ${wanted} installed to %TOOL_DIR%
    """

    env.UNITY_DATA_TOOL_PATH = "${toolDir}\\UnityDataTool.exe"
    return [available: true, installed: true, message: "UnityDataTool ${wanted} installed", path: env.UNITY_DATA_TOOL_PATH]
}

/**
 * Check Java installation
 */
def checkJava() {
    // Build Unity JDK path checks inline (avoids calling getPlaybackEnginesPath which makes its own bat call)
    def unityChecks = ''
    if (env.UNITY_VERSION) {
        def base = "C:\\UnityEditors\\${env.UNITY_VERSION}"
        def newJdk = "${base}\\Editor\\Data\\PlaybackEngines\\AndroidPlayer\\OpenJDK"
        def legacyJdk = "${base}\\PlaybackEngines\\AndroidPlayer\\OpenJDK"
        unityChecks = """
            if exist "${newJdk}\\bin\\java.exe" (
                echo   Found Unity bundled OpenJDK at ${newJdk} >&2
                echo FOUND_UNITY
                echo ${newJdk}
                exit /b 0
            )
            if exist "${legacyJdk}\\bin\\java.exe" (
                echo   Found Unity bundled OpenJDK at ${legacyJdk} >&2
                echo FOUND_UNITY
                echo ${legacyJdk}
                exit /b 0
            )"""
    }

    // Build JAVA_HOME check (env.JAVA_HOME might not be set)
    def javaHomeCheck = ''
    if (env.JAVA_HOME) {
        javaHomeCheck = """
            if exist "${env.JAVA_HOME}\\bin\\java.exe" (
                echo   JAVA_HOME is valid: ${env.JAVA_HOME} >&2
                echo FOUND_JAVA_HOME
                echo ${env.JAVA_HOME}
                exit /b 0
            )"""
    }

    // Single bat call searches all locations in priority order
    def result = bat(
        script: """@echo off
            setlocal EnableDelayedExpansion
            echo Checking Java installation... >&2

            REM 0. Check existing JAVA_HOME
            ${javaHomeCheck}

            REM 1. Check Unity's bundled OpenJDK first (known compatible version)
            ${unityChecks}

            REM 2. Check PATH
            set "JVER="
            for /f "delims=" %%v in ('java -version 2^>^&1') do if not defined JVER set "JVER=%%v"
            if defined JVER (
                echo !JVER! | findstr /C:"version" >nul
                if not errorlevel 1 (
                    set "JPATH="
                    for /f "delims=" %%p in ('where java 2^>nul') do if not defined JPATH set "JPATH=%%p"
                    echo   Found java in PATH >&2
                    echo FOUND_PATH
                    echo !JPATH!
                    echo !JVER!
                    exit /b 0
                )
            )

            REM 3. Check common system JDK locations
            for /d %%j in ("C:\\Program Files\\Microsoft\\jdk-*") do (
                if exist "%%j\\bin\\java.exe" (
                    echo   Found system JDK at %%j >&2
                    echo FOUND_SYSTEM
                    echo %%j
                    exit /b 0
                )
            )
            if exist "C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\java.exe" (
                echo   Found Android Studio JBR >&2
                echo FOUND_SYSTEM
                echo C:\\Program Files\\Android\\Android Studio\\jbr
                exit /b 0
            )
            if exist "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot\\bin\\java.exe" (
                echo   Found Eclipse Adoptium JDK >&2
                echo FOUND_SYSTEM
                echo C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot
                exit /b 0
            )
            for /d %%j in ("C:\\Program Files\\Java\\jdk-*") do (
                if exist "%%j\\bin\\java.exe" (
                    echo   Found system JDK at %%j >&2
                    echo FOUND_SYSTEM
                    echo %%j
                    exit /b 0
                )
            )

            echo NOT_FOUND
            exit /b 0""",
        returnStdout: true
    ).trim()

    echo "  checkJava stdout: ${result}"
    def lines = result.readLines()
    def statusCode = lines[0]

    if (statusCode == 'FOUND_JAVA_HOME') {
        return [available: true, message: "Using Java from JAVA_HOME: ${lines[1]}"]
    }

    if (statusCode == 'FOUND_PATH' && lines.size() >= 3) {
        def javaPath = lines[1]
        if (javaPath) {
            env.JAVA_HOME = javaPath.replaceAll(/(?i)\\bin\\java\.exe$/, '')
        }
        return [available: true, message: lines[2]]
    }

    if (statusCode == 'FOUND_UNITY' && lines.size() >= 2) {
        env.JAVA_HOME = lines[1]
        return [available: true, message: "Using Unity bundled OpenJDK at ${lines[1]}"]
    }

    if (statusCode == 'FOUND_SYSTEM' && lines.size() >= 2) {
        env.JAVA_HOME = lines[1]
        return [available: true, message: "Using system JDK at ${lines[1]}"]
    }

    // 4. Auto-install via winget if available
    if (isWingetAvailable()) {
        try {
            echo "[INFO] Java not found - attempting auto-install via winget (Microsoft OpenJDK 17)..."
            bat """
                @echo off
                winget install --id Microsoft.OpenJDK.17 --scope machine --silent --accept-source-agreements --accept-package-agreements
                if errorlevel 1 (
                    echo [ERROR] Java installation via winget failed
                    exit /b 1
                )
                echo [OK] Microsoft OpenJDK 17 installed
            """
            // Check if it's immediately available (winget installs to Program Files)
            def jdkPath = bat(
                script: '''@echo off
                    for /d %%j in ("C:\\Program Files\\Microsoft\\jdk-*") do (
                        if exist "%%j\\bin\\java.exe" (
                            echo %%j
                            goto :eof
                        )
                    )
                ''',
                returnStdout: true
            ).trim()
            if (jdkPath) {
                env.JAVA_HOME = jdkPath
                return [available: true, message: "Installed and using Microsoft OpenJDK at ${jdkPath}"]
            }
            return [available: false, installed: true, message: 'Java installed via winget, restart Jenkins agent to update PATH']
        } catch (Exception e) {
            echo "[WARN] Java auto-install failed: ${e.message}"
        }
    }

    return [
        available: false,
        message: 'Java not found',
        installInstructions: 'Install Unity with Android Build Support module (includes OpenJDK), or install a JDK: winget install Microsoft.OpenJDK.17'
    ]
}

// ============================================================================
// PREFLIGHT CHECK FUNCTIONS
// ============================================================================

/**
 * Preflight check for Java - required by bundletool (AAB→APK conversion) and Firebase Crashlytics.
 * Sets JAVA_HOME if found via Unity's bundled OpenJDK.
 */
def preflightJava() {
    def result = checkJava()
    if (result.available) {
        echo "[OK] Java: ${result.message}"
    } else if (result.installed) {
        echo "[WARN] Java was installed but requires agent restart: ${result.message}"
    } else {
        error "[ERROR] Java not found\nFix: ${result.installInstructions}"
    }
}

def preflightNodeJS() {
    def result = checkNodeJS(true)
    if (result.available) {
        echo "[OK] Node.js: ${result.message}"
    } else {
        error "[ERROR] Node.js not found\nFix: Install Node.js LTS from https://nodejs.org/ or run: winget install OpenJS.NodeJS.LTS"
    }
}

/**
 * Verify network connectivity to cloud services before running other preflights.
 * Retries up to 3 times with 15s delay - catches transient DNS/firewall/cloud issues early.
 */
def preflightNetwork() {
    // Single PowerShell call tests all endpoints - avoids per-endpoint process startup overhead.
    // Uses raw TcpClient instead of Test-NetConnection (which is extremely slow on Windows).
    def script = '''@powershell -Command ^
        $endpoints = @( ^
            @{Name='Plastic SCM Cloud'; Host='asianortheast1-00-cloud.plasticscm.com'; Port=8787}, ^
            @{Name='GitHub API'; Host='api.github.com'; Port=443} ^
        ); ^
        $maxRetries = 3; ^
        $retryDelay = 15; ^
        $failed = @(); ^
        for ($attempt = 1; $attempt -le $maxRetries; $attempt++) { ^
            $failed = @(); ^
            foreach ($ep in $endpoints) { ^
                try { ^
                    $tcp = New-Object System.Net.Sockets.TcpClient; ^
                    $result = $tcp.BeginConnect($ep.Host, $ep.Port, $null, $null); ^
                    $success = $result.AsyncWaitHandle.WaitOne(10000); ^
                    if ($success -and $tcp.Connected) { ^
                        if ($attempt -eq 1) { Write-Host ('[OK] ' + $ep.Name + ' reachable') } ^
                        else { Write-Host ('[OK] ' + $ep.Name + ' reachable (attempt ' + $attempt + ')') } ^
                    } else { $failed += $ep.Name } ^
                    $tcp.Close(); ^
                } catch { $failed += $ep.Name } ^
            }; ^
            if ($failed.Count -eq 0) { break }; ^
            if ($attempt -lt $maxRetries) { ^
                Write-Host ('[WARN] Network check attempt ' + $attempt + '/' + $maxRetries + ' - unreachable: ' + ($failed -join ', ') + '. Retrying in ' + $retryDelay + 's...'); ^
                Start-Sleep -Seconds $retryDelay ^
            } ^
        }; ^
        if ($failed.Count -gt 0) { ^
            Write-Error ('[ERROR] Cloud services unreachable after ' + $maxRetries + ' attempts: ' + ($failed -join ', ') + '. Check agent network/DNS/firewall.'); ^
            exit 1 ^
        }'''

    bat script
}

def preflightWinget() {
    def result = checkWinget(true)
    if (result.available) {
        echo "[OK] winget: ${result.message}"
    } else {
        echo "[WARN] winget not available - auto-install of tools (Python, Java, .NET, etc.) will be skipped"
    }
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
        bat script: "@git config --global url.\"https://${user}:${pass}@github.com/\".insteadOf \"https://github.com/\"", returnStatus: true

        // Method 2: GIT_ASKPASS script (works for any git, including Unity's embedded git)
        def askpassPath = "${env.WORKSPACE}\\.git-askpass.bat"
        writeFile file: askpassPath, text: """@echo off
echo %1 | findstr /i "password" >nul && (echo ${pass}& exit /b 0)
echo ${user}
"""
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
 * The token is passed via the credential's env var (%VAR%, expanded by cmd), never
 * interpolated into the command by Groovy — so it isn't exposed in the script text.
 */
def configureGitHubOrgPats() {
    def pats = common.parseOrgPats(env.GITHUB_ORG_PATS)
    if (!pats) return

    pats.each { p ->
        try {
            withCredentials([string(credentialsId: p.credId, variable: 'GH_ORG_PAT')]) {
                def rc = bat(script: "@git config --global url.\"https://x-access-token:%GH_ORG_PAT%@github.com/${p.org}/\".insteadOf \"https://github.com/${p.org}/\"", returnStatus: true)
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
 * Remove git auth configuration added by configureGitAuth()/configureGitHubOrgPats().
 * Called in the post block to avoid leaving credentials on disk. Unsets every
 * url.*@github.com/ insteadOf rewrite (default + org-scoped). `for /f` captures git's
 * output — which includes token-bearing key names — without echoing it to the log.
 */
def cleanupGitAuth() {
    bat script: '@for /f "usebackq tokens=1" %%K in (`git config --global --get-regexp "url\\..*@github\\.com/" 2^>nul`) do @git config --global --unset-all "%%K" >nul 2>nul', returnStatus: true
    bat script: "@if exist \"${env.WORKSPACE}\\.git-askpass.bat\" del \"${env.WORKSPACE}\\.git-askpass.bat\" 2>nul", returnStatus: true
}

def preflightRclone() {
    def rcloneCheck = checkRclone(true)  // auto-install if missing
    if (!rcloneCheck.available) {
        error "[ERROR] rclone not available: ${rcloneCheck.message}"
    }

    // Always use the tools folder path (ignore any custom env vars)
    env.RCLONE_PATH = rcloneCheck.path

    withCredentials([file(credentialsId: 'rclone', variable: 'RCLONE_CONFIG')]) {
        bat """
            @echo off
            "${env.RCLONE_PATH}" version || exit /b 1
            "${env.RCLONE_PATH}" --config "%RCLONE_CONFIG%" about "%RCLONE_REMOTE%" >nul || exit /b 1
            echo rclone authenticated
        """
        // Own OAuth client or service account? Only a yes/no leaves the shell - the file is a secret.
        def ownClient = bat(script: '@findstr /r /c:"^client_id *= *[^ ]" /c:"^service_account_[a-z]* *= *[^ ]" "%RCLONE_CONFIG%" >nul 2>&1', returnStatus: true) == 0
        common.reportRcloneClient(ownClient)
    }
}

def preflightPlasticSCM() {
    def plasticCheck = checkPlasticSCM(true)  // auto-install if missing
    if (!plasticCheck.available) {
        error "[ERROR] PlasticSCM not available: ${plasticCheck.message}"
    }

    // Lock Plastic auth so only one agent refreshes the SSO token at a time.
    // Concurrent SSO logins can invalidate each other's sessions.
    lock(resource: 'plastic-scm-auth', quantity: 1) {
        withCredentials([string(credentialsId: 'plastic-token', variable: 'PLASTIC_TOKEN')]) {
            bat '''@echo off
                cm profile list | findstr /C:"oddgames_external@cloud" >nul 2>&1 && (
                    cm profile delete oddgames_external@cloud >nul 2>&1
                )
                cm profile create --server=oddgames_external@cloud --username=builds@oddgames.com.au --token="%PLASTIC_TOKEN%" --workingmode=SSOWorkingMode
                cm whoami || exit /b 1
                echo PlasticSCM authenticated >&2'''
        }
    }
    echo "[OK] PlasticSCM authenticated"
}

def preflightFastlane() {
    def rubyCheck = checkRuby(true)  // auto-install if missing
    if (!rubyCheck.available) {
        error "[ERROR] Ruby not available (required for Fastlane): ${rubyCheck.message}"
    }

    def fastlaneCheck = checkFastlane(true)  // auto-install if missing
    if (!fastlaneCheck.available) {
        error "[ERROR] Fastlane not available: ${fastlaneCheck.message}"
    }
}

def preflightSteamCMD() {
    def steamCheck = checkSteamCMD(true)  // auto-install if missing
    if (!steamCheck.available) {
        error "[ERROR] SteamCMD not available: ${steamCheck.message}"
    }

    // Always use the tools folder path (ignore any custom env vars)
    env.STEAMCMD_PATH = steamCheck.path

    bat """
        @echo off
        if not exist "${env.STEAMCMD_PATH}" (
            echo [ERROR] SteamCMD not found at: ${env.STEAMCMD_PATH}
            exit /b 1
        )
        echo [OK] SteamCMD found at ${env.STEAMCMD_PATH}
    """

    // Test Steam authentication with the build credentials.
    // If Steam Guard is required, send a Slack notification with a link to
    // the Jenkins input page, then pause the build for the user to enter the code.
    //
    // SteamCMD hangs waiting for interactive Steam Guard input - +quit only
    // runs after login succeeds, so if login blocks for a code, the process
    // never exits. We use Jenkins timeout() to kill it after 30s, then read
    // the output log to determine what happened.
    def steamCmdDir = env.STEAMCMD_PATH.replace('\\steamcmd.exe', '')
    withCredentials([usernamePassword(credentialsId: 'steam-credentials', usernameVariable: 'STEAM_USERNAME', passwordVariable: 'STEAM_PASSWORD')]) {
        echo "[INFO] Testing Steam authentication..."

        def logFile = "${env.WORKSPACE}\\steamcmd_login_test.log"
        def timedOut = false

        // Run SteamCMD from its own directory so login tokens are cached correctly.
        // If it hangs (Steam Guard prompt), Jenkins timeout() kills the step and
        // we read the log file to see what SteamCMD printed before it hung.
        try {
            timeout(time: 5, unit: 'MINUTES') {
                bat """
                    @echo off
                    cd /d "${steamCmdDir}"
                    "${env.STEAMCMD_PATH}" +login %STEAM_USERNAME% %STEAM_PASSWORD% +quit > "${logFile}" 2>&1
                """
            }
        } catch (Exception e) {
            // timeout() throws FlowInterruptedException when it kills the step
            timedOut = true
        }

        def testResult = ''
        try {
            testResult = readFile(file: logFile).trim()
        } catch (Exception e) {
            echo "[WARN] Could not read SteamCMD output log"
        }

        echo "[INFO] SteamCMD login test output:"
        echo testResult ?: "(no output)"
        if (timedOut) echo "[INFO] SteamCMD was killed after 5m timeout"

        // Determine what happened based on actual output
        def steamGuardKeywords = testResult.contains("Steam Guard") ||
                                 testResult.contains("Two-factor") ||
                                 testResult.contains("Account Logon Denied")
        def loginSuccess = testResult.contains("Logged in OK") ||
                           testResult.contains("Waiting for user info")
        def needsSteamGuard = false

        if (steamGuardKeywords) {
            needsSteamGuard = true
        } else if (timedOut && !loginSuccess) {
            // Timed out but no Steam Guard text - could be network, server, or update
            def stuckConnecting = testResult.contains("Connecting anonymously") ||
                                  testResult.contains("Logging in user") ||
                                  testResult.isEmpty()
            if (stuckConnecting) {
                error("[ERROR] SteamCMD login timed out - could not connect to Steam servers. Check network connectivity and Steam server status. Output:\n${testResult}")
            } else {
                echo "[WARNING] SteamCMD timed out with unexpected output - may need Steam Guard or may be a server issue"
                needsSteamGuard = true
            }
        }

        if (needsSteamGuard) {
            echo ""
            echo "=========================================="
            echo "Steam Guard Authorization Required"
            echo "=========================================="
            echo "A Steam Guard code has been sent to your email/authenticator."
            echo "Enter the code in Jenkins to continue the build."
            echo "=========================================="

            // Notify via Slack with a direct link to the Jenkins input page
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

            // Give failFast a moment to propagate, then skip if build is already failing
            sleep(time: 5, unit: 'SECONDS')
            if (currentBuild.result in ['FAILURE', 'ABORTED']) {
                error("Skipping Steam Guard prompt - build already failed in another stage")
            }

            // Prompt user for Steam Guard code via Jenkins input step.
            // Timeout prevents infinite hang if no one responds (e.g. failFast from parallel branch).
            def steamGuardCode = ''
            try {
                timeout(time: 30, unit: 'MINUTES') {
                    steamGuardCode = input(
                        message: 'Enter Steam Guard code from your email or authenticator',
                        parameters: [string(
                            name: 'STEAM_GUARD_CODE',
                            description: 'Steam Guard code',
                            trim: true
                        )]
                    )
                }
            } catch (Exception e) {
                error("Steam Guard authorization was not provided within 30 minutes - build cannot continue without Steam login")
            }

            if (!steamGuardCode) {
                error("No Steam Guard code provided - build cannot continue without Steam login")
            }

            // Login with the Steam Guard code from the SteamCMD directory
            // so the login token is cached in the right place for future builds
            echo "[INFO] Logging in with Steam Guard code..."
            def guardResult
            withEnv(["STEAM_GUARD_CODE=${steamGuardCode}"]) {
                guardResult = bat(
                    script: """
                        @echo off
                        cd /d "${steamCmdDir}"
                        "${env.STEAMCMD_PATH}" +set_steam_guard_code %STEAM_GUARD_CODE% +login %STEAM_USERNAME% %STEAM_PASSWORD% +quit
                    """,
                    returnStdout: true
                ).trim()
            }

            echo "[INFO] Login output: ${guardResult}"

            if (guardResult.contains("Logged in OK") || guardResult.contains("Waiting for user info")) {
                echo "[OK] Steam Guard authorization successful - token cached for future builds"
            } else if (guardResult.contains("Steam Guard") || guardResult.contains("Account Logon Denied")) {
                error("Steam Guard code was rejected - check the code and retry the build")
            } else {
                echo "[WARNING] Uncertain login status after Steam Guard - build will continue"
            }
        } else if (loginSuccess) {
            echo "[OK] Steam authentication successful"
        } else {
            echo "[WARNING] Uncertain Steam auth status - build will continue but may fail at upload"
            echo "[INFO] Login output: ${testResult}"
        }
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

def preflightNintendoSDK() {
    // NX Addon structure: C:\Nintendo\Unity{VERSION}_LTS-NXAddon...\NintendoSDK\
    // NINTENDO_SDK_ROOT must point to the NintendoSDK subfolder (contains NintendoSDK_Revision.txt)
    // Strip Unity revision suffix (f2, p1, etc.) - folder names use base version only
    // e.g. 6000.0.58f2 -> 6000.0.58 to match Unity6000.0.58_LTS-NXAddon...
    def unityBaseVersion = env.UNITY_VERSION.replaceAll(/[a-z]\d+$/, '')
    def result = bat(
        script: """
        @echo off
            setlocal enabledelayedexpansion

            set "NINTENDO_BASE=C:\\Nintendo"
            set "UNITY_BASE_VER=${unityBaseVersion}"

            if not exist "%NINTENDO_BASE%" (
                echo [ERROR] Nintendo directory not found: %NINTENDO_BASE%
                echo.
                echo [FIX] Install NX Addon for Unity %UNITY_VERSION% using Nintendo Package Manager
                exit /b 1
            )

            REM Find the NX Addon matching the Unity base version (without revision suffix)
            set "FOUND_ADDON="
            for /d %%D in ("%NINTENDO_BASE%\\Unity%UNITY_BASE_VER%*") do (
                set "FOUND_ADDON=%%D"
            )

            if not defined FOUND_ADDON (
                echo [ERROR] NX Addon for Unity %UNITY_BASE_VER% not found in %NINTENDO_BASE%
                echo.
                echo [INFO] Available addons:
                for /d %%D in ("%NINTENDO_BASE%\\Unity*") do (
                    echo   - %%~nxD
                )
                echo.
                echo [FIX] Install NX Addon for Unity %UNITY_VERSION% via Nintendo Package Manager
                exit /b 1
            )

            echo NX_ADDON_PATH=!FOUND_ADDON!

            REM Verify NintendoSDK subfolder exists
            if not exist "!FOUND_ADDON!\\NintendoSDK\\Revisions\\NintendoSDK_Revision.txt" (
                echo [ERROR] NintendoSDK subfolder missing or incomplete in !FOUND_ADDON!
                echo [INFO] Expected: !FOUND_ADDON!\\NintendoSDK\\Revisions\\NintendoSDK_Revision.txt
                echo.
                echo [FIX] Reinstall NX Addon for Unity %UNITY_VERSION% - the NintendoSDK component may be missing
                exit /b 1
            )

            echo NINTENDO_SDK_ROOT=!FOUND_ADDON!\\NintendoSDK
        """,
        returnStdout: true
    ).trim()

    // Parse NX Addon path
    def addonMatch = result =~ /NX_ADDON_PATH=(.+)/
    if (addonMatch) {
        env.NX_ADDON_PATH = addonMatch[0][1]
        echo "[OK] NX Addon found: ${env.NX_ADDON_PATH}"
    } else {
        error result
    }

    // Parse NINTENDO_SDK_ROOT (subfolder of addon)
    def sdkMatch = result =~ /NINTENDO_SDK_ROOT=(.+)/
    if (sdkMatch) {
        env.NINTENDO_SDK_ROOT = sdkMatch[0][1]
        echo "[OK] Nintendo SDK: ${env.NINTENDO_SDK_ROOT}"
    } else {
        error result
    }
}

def preflightDotNetSDK(String minVersion = '8.0') {
    // Check for .NET SDK and add to PATH/DOTNET_ROOT for Unity IL2CPP
    // Unity IL2CPP looks for DOTNET_ROOT environment variable

    def dotnetPath = "C:\\Program Files\\dotnet"

    // Check common installation paths
    def paths = [
        "C:\\Program Files\\dotnet",
        "${env.USERPROFILE}\\.dotnet",
        "${env.ProgramFiles}\\dotnet"
    ]

    def foundPath = null
    def foundVersion = null

    for (path in paths) {
        def checkResult = bat(
            script: """
                @echo off
                if exist "${path}\\dotnet.exe" (
                    "${path}\\dotnet.exe" --version
                ) else (
                    echo NOT_FOUND
                )
            """,
            returnStdout: true
        ).trim()

        if (checkResult && checkResult != 'NOT_FOUND') {
            // Extract version number (e.g., "8.0.100" -> "8")
            def majorVersion = checkResult.tokenize('.')[0]
            def minMajor = minVersion.tokenize('.')[0]

            if (majorVersion.isInteger() && majorVersion.toInteger() >= minMajor.toInteger()) {
                foundPath = path
                foundVersion = checkResult
                break
            }
        }
    }

    if (!foundPath) {
        // Attempt auto-install via winget
        if (isWingetAvailable()) {
            try {
                echo "[INFO] .NET SDK not found - attempting auto-install via winget..."
                bat """
                    @echo off
                    winget install --id Microsoft.DotNet.SDK.8 --scope machine --silent --accept-source-agreements --accept-package-agreements
                    if errorlevel 1 (
                        echo [ERROR] .NET SDK installation via winget failed
                        exit /b 1
                    )
                    echo [OK] .NET SDK 8 installed
                """
                // Re-check after install
                def dotnetExe = "C:\\Program Files\\dotnet\\dotnet.exe"
                def verCheck = bat(script: "@if exist \"${dotnetExe}\" \"${dotnetExe}\" --version", returnStdout: true).trim()
                if (verCheck && verCheck != 'NOT_FOUND') {
                    foundPath = "C:\\Program Files\\dotnet"
                    foundVersion = verCheck
                    echo "[OK] .NET SDK ${foundVersion} installed via winget"
                }
            } catch (Exception e) {
                echo "[WARN] .NET SDK auto-install failed: ${e.message}"
            }
        }

        if (!foundPath) {
            error """[ERROR] .NET SDK ${minVersion}+ not found

[FIX] Install .NET SDK:
  1. Run: winget install Microsoft.DotNet.SDK.8 --silent --accept-source-agreements --accept-package-agreements
  2. Or download from https://dotnet.microsoft.com/download/dotnet/8.0
  3. Restart the Jenkins agent"""
        }
    }

    // Set environment variables that Unity IL2CPP needs
    env.DOTNET_ROOT = foundPath

    // Prepend to PATH if not already there
    if (!env.PATH?.contains(foundPath)) {
        env.PATH = "${foundPath};${env.PATH}"
    }

    echo "[OK] .NET SDK ${foundVersion} found at ${foundPath}"
    echo "[OK] Set DOTNET_ROOT=${foundPath}"

    // Verify it works in a new bat context
    bat """
        @echo off
        echo [INFO] Verifying .NET SDK accessibility...
        echo [INFO] DOTNET_ROOT=%DOTNET_ROOT%
        echo [INFO] PATH includes: ${foundPath}
        "${foundPath}\\dotnet.exe" --list-sdks
    """
}

def preflightLongPaths() {
    // Verify Windows long path support is enabled (LongPathsEnabled registry key)
    // Required for Switch builds where Bee/AppPkg intermediate paths exceed 260 chars
    def output = bat(
        script: """
            @echo off
            reg query "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\FileSystem" /v LongPathsEnabled
        """,
        returnStdout: true
    ).trim()

    if (output.contains('0x1')) {
        echo "[OK] Windows long path support is enabled (LongPathsEnabled=1)"
        return
    }

    error """[ERROR] Windows long path support is not enabled

Unity Switch builds require paths longer than 260 characters (Bee/AppPkg layout paths).

[FIX] Run this command on the build agent as Administrator:
  reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\FileSystem" /v LongPathsEnabled /t REG_DWORD /d 1 /f

Then restart the Jenkins agent service for the change to take effect."""
}

def preflightVisualStudio() {
    // Check for Visual Studio with C++ components (required for IL2CPP Windows builds)
    // Unity uses vswhere.exe to find VS installations
    def vswhereResult = bat(script: '''@echo off
set "VSWHERE=%ProgramFiles(x86)%\\Microsoft Visual Studio\\Installer\\vswhere.exe"
if not exist "%VSWHERE%" (
    echo NO_VSWHERE
    exit /b 1
)

REM Check for VS with C++ desktop workload
"%VSWHERE%" -latest -requires Microsoft.VisualStudio.Workload.NativeDesktop -property installationPath > "%TEMP%\\vs_check.txt" 2>&1
for /f "usebackq tokens=*" %%a in ("%TEMP%\\vs_check.txt") do (
    if not "%%a"=="" (
        echo VS_FOUND=%%a
        exit /b 0
    )
)

REM VS exists but no C++ workload
"%VSWHERE%" -latest -property installationPath > "%TEMP%\\vs_check.txt" 2>&1
for /f "usebackq tokens=*" %%a in ("%TEMP%\\vs_check.txt") do (
    if not "%%a"=="" (
        echo VS_NO_CPP=%%a
        exit /b 1
    )
)

echo NO_VS
exit /b 1
''', returnStdout: true).trim()

    if (vswhereResult.contains('VS_FOUND=')) {
        def vsPath = vswhereResult.replace('VS_FOUND=', '').trim()
        echo "[OK] Visual Studio with C++ components found: ${vsPath}"
    } else if (vswhereResult.contains('VS_NO_CPP=')) {
        def vsPath = vswhereResult.replace('VS_NO_CPP=', '').trim()
        error """[ERROR] Visual Studio found but missing C++ components
Found: ${vsPath}

Unity IL2CPP builds require the C++ desktop development workload.

[FIX] Run the Visual Studio Installer on the build agent and add:
  - Workload: "Desktop development with C++"
  - Or from command line (as admin):
    "${vsPath}\\..\\Installer\\vs_installer.exe" modify --installPath "${vsPath}" --add Microsoft.VisualStudio.Workload.NativeDesktop --includeRecommended --quiet"""
    } else {
        error """[ERROR] Visual Studio not found

Unity IL2CPP builds require Visual Studio 2019 or 2022 with C++ components.

[FIX] Install Visual Studio on the build agent:
  winget install Microsoft.VisualStudio.2022.BuildTools
  Then add the C++ desktop development workload via the VS Installer."""
    }

    // Check Windows SDK (version 10.0.19041.0 or newer)
    def sdkResult = bat(script: '''@echo off
setlocal EnableDelayedExpansion
reg query "HKEY_LOCAL_MACHINE\\SOFTWARE\\Wow6432Node\\Microsoft\\Microsoft SDKs\\Windows\\v10.0" /v InstallationFolder >nul 2>&1
if errorlevel 1 (
    echo NO_SDK
    exit /b 1
)

REM Check for a SDK version >= 10.0.19041.0
set "SDK_ROOT="
for /f "tokens=2*" %%a in ('reg query "HKEY_LOCAL_MACHINE\\SOFTWARE\\Wow6432Node\\Microsoft\\Microsoft SDKs\\Windows\\v10.0" /v InstallationFolder 2^>nul ^| findstr InstallationFolder') do set "SDK_ROOT=%%b"
if "%SDK_ROOT%"=="" (
    echo NO_SDK
    exit /b 1
)

set "BEST_VER="
set "BEST_BUILD=0"
for /d %%d in ("%SDK_ROOT%Include\\10.0.*") do (
    for /f "tokens=3 delims=." %%b in ("%%~nxd") do (
        if %%b GEQ 19041 (
            if %%b GTR !BEST_BUILD! (
                set "BEST_BUILD=%%b"
                set "BEST_VER=%%~nxd"
            )
        )
    )
)
if not "%BEST_VER%"=="" (
    echo SDK_VERSION=%BEST_VER%
    exit /b 0
)
echo SDK_TOO_OLD
exit /b 1
''', returnStdout: true).trim()

    if (sdkResult.contains('SDK_VERSION=')) {
        echo "[OK] Windows SDK ${sdkResult.replace('SDK_VERSION=', '').trim()} installed"
    } else {
        error """[ERROR] Windows 10 SDK (version 10.0.19041.0 or newer) not installed

Unity IL2CPP builds require the Windows 10 SDK.

[FIX] Install on the build agent:
  winget install Microsoft.WindowsSDK.10.0.19041
  Or download from: https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk/"""
    }
}

def preflightRuby() {
    def rubyCheck = checkRuby(true)  // auto-install if missing
    if (!rubyCheck.available) {
        error "[ERROR] Ruby not available: ${rubyCheck.message}\n${rubyCheck.installInstructions ?: ''}"
    }
    echo "[OK] Ruby available: ${rubyCheck.version ?: 'found'}"
}

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
            error """[ERROR] Node.js was just installed but requires agent restart to update PATH

Run on the Jenkins agent (as admin):
  Restart-Service Jenkins"""
        }
        error """[ERROR] Node.js/npm not available

FIX: Remote into the build agent, then:
  1. If Node.js is not installed: winget install OpenJS.NodeJS.LTS
  2. Ensure 'C:\\Program Files\\nodejs' is in the system-level PATH environment variable
  3. Restart the Jenkins agent service: Restart-Service Jenkins"""
    }
    echo "[OK] ${nodeCheck.message}"

    // Extract Firebase App ID from google-services.json
    // Try Jenkins credential first, then check common Unity project locations
    def googleServicesPath = null
    def googleServicesContent = null

    // Check for Jenkins secret file credential (credential ID from GOOGLE_SERVICES_CREDENTIAL_ID env var or common IDs)
    def credentialId = env.GOOGLE_SERVICES_CREDENTIAL_ID
    if (credentialId) {
        try {
            withCredentials([file(credentialsId: credentialId, variable: 'GOOGLE_SERVICES_JSON')]) {
                googleServicesContent = readFile(env.GOOGLE_SERVICES_JSON)
                googleServicesPath = "(Jenkins credential: ${credentialId})"
            }
        } catch (Exception e) {
            echo "[WARN] Could not load google-services.json from credential '${credentialId}': ${e.message}"
        }
    }

    // Fall back to checking Unity project locations
    if (!googleServicesContent) {
        def googleServicesLocations = [
            "${env.UNITY_PROJECT}/Assets/google-services.json",
            "${env.UNITY_PROJECT}/Assets/StreamingAssets/google-services.json",
            "${env.UNITY_PROJECT}/Assets/Plugins/Android/google-services.json",
            "${env.UNITY_PROJECT}/Assets/Firebase/google-services.json"
        ]

        for (location in googleServicesLocations) {
            def exists = bat(script: "@if exist \"${location}\" echo found", returnStdout: true).trim()
            if (exists == 'found') {
                googleServicesPath = location
                googleServicesContent = readFile(location)
                break
            }
        }
    }

    if (!googleServicesContent) {
        error """[ERROR] google-services.json not found

Set GOOGLE_SERVICES_CREDENTIAL_ID in the Jenkins job config to the credential ID containing google-services.json,
or place the file in one of these Unity project locations:
  - Assets/google-services.json
  - Assets/StreamingAssets/google-services.json
  - Assets/Plugins/Android/google-services.json
  - Assets/Firebase/google-services.json"""
    }

    // Parse the mobilesdk_app_id from google-services.json
    echo "[INFO] Using google-services.json from ${googleServicesPath}"
    def appIdMatch = googleServicesContent =~ /"mobilesdk_app_id"\s*:\s*"([^"]+)"/
    if (!appIdMatch) {
        error """[ERROR] Could not find mobilesdk_app_id in ${googleServicesPath}

The google-services.json file appears to be invalid or incomplete.

To fix:
  1. Go to Firebase Console: https://console.firebase.google.com
  2. Select your project > Project Settings > Your apps
  3. Re-download google-services.json for your Android app
  4. Replace the file in your Unity project
  5. Verify the file contains "mobilesdk_app_id" field"""
    }
    def firebaseAppId = appIdMatch[0][1]
    appIdMatch = null // Clear Matcher to avoid serialization issues
    env.FIREBASE_APP_ID = firebaseAppId
    echo "[INFO] Extracted Firebase App ID from google-services.json: ${firebaseAppId}"

    // Validate FIREBASE_APP_ID format (e.g., 1:123456789:android:abcdef or 1:123456789:ios:abcdef)
    def appIdPattern = /^\d+:\d+:(android|ios):[a-f0-9]+$/
    if (!(firebaseAppId ==~ appIdPattern)) {
        error """[ERROR] Firebase App ID format invalid: ${firebaseAppId}

Expected format: 1:123456789:android:abcdef

The mobilesdk_app_id in google-services.json doesn't match the expected Firebase format.

To fix:
  1. Verify you downloaded the correct google-services.json from Firebase Console
  2. Ensure this is for a Firebase project (not just Google Cloud)
  3. The app must be registered in Firebase with Crashlytics enabled"""
    }

    // Check Firebase CLI — auto-install via npm if missing or corrupt
    def firebaseCheck = checkFirebaseCLI()
    if (!firebaseCheck.available) {
        echo "[INFO] Firebase CLI not found or corrupt, installing via npm..."
        def nodejsDir = (env.NODEJS_HOME ?: 'C:\\Program Files\\nodejs').replace('/', '\\')

        // Single bat block: sets PATH (so npm postinstall scripts can find 'node'),
        // cleans up EPERM-locked leftovers, then installs to the Node.js directory
        // so firebase.cmd is co-located with node.exe (%~dp0\node.exe always resolves).
        def installExit = bat(script: """@echo off
            set "PATH=${nodejsDir};%PATH%"
            echo [INFO] Node.js PATH set to: ${nodejsDir}

            REM --- Phase 1: Clean up old/corrupt installs ---
            echo [INFO] Uninstalling existing firebase-tools...
            call "${nodejsDir}\\npm.cmd" uninstall -g firebase-tools 2>nul

            REM Force-delete leftovers from both possible locations (npm uninstall often fails on EPERM)
            for %%d in ("%APPDATA%\\npm\\node_modules\\firebase-tools" "${nodejsDir}\\node_modules\\firebase-tools") do (
                if exist "%%~d" (
                    echo [INFO] Force-deleting leftover %%~d
                    rmdir /s /q "%%~d" 2>nul
                    if exist "%%~d" (
                        echo [WARN] Could not delete %%~d - trying via robocopy empty-dir trick
                        mkdir "%TEMP%\\empty_dir_firebase" 2>nul
                        robocopy "%TEMP%\\empty_dir_firebase" "%%~d" /mir /njh /njs /nfl /ndl >nul 2>&1
                        rmdir /s /q "%%~d" 2>nul
                        rmdir /q "%TEMP%\\empty_dir_firebase" 2>nul
                        if exist "%%~d" (
                            echo [ERROR] Still could not delete %%~d - files locked by another process
                        )
                    )
                )
            )

            REM Remove stale firebase.cmd shims from user npm dir (would shadow the co-located install)
            if exist "%APPDATA%\\npm\\firebase.cmd" del /q "%APPDATA%\\npm\\firebase.cmd" 2>nul
            if exist "%APPDATA%\\npm\\firebase" del /q "%APPDATA%\\npm\\firebase" 2>nul
            if exist "%APPDATA%\\npm\\firebase.ps1" del /q "%APPDATA%\\npm\\firebase.ps1" 2>nul

            REM --- Phase 2: Install firebase-tools into the Node.js directory ---
            REM Using --prefix so firebase.cmd lands next to node.exe, making firebase.cmd's
            REM internal "%%~dp0\\node.exe" check succeed without needing node in system PATH.
            echo [INFO] Installing firebase-tools to ${nodejsDir}...
            call "${nodejsDir}\\npm.cmd" install -g --prefix "${nodejsDir}" firebase-tools

            REM Verify by checking if firebase.cmd actually exists (errorlevel from call+npm is unreliable)
            if not exist "${nodejsDir}\\firebase.cmd" (
                echo [WARN] --prefix install did not produce firebase.cmd, trying default npm global install...
                call "${nodejsDir}\\npm.cmd" install -g firebase-tools
            )

            REM Final check — if firebase.cmd still missing, fail
            if not exist "${nodejsDir}\\firebase.cmd" (
                if not exist "%APPDATA%\\npm\\firebase.cmd" (
                    echo [ERROR] npm install -g firebase-tools failed - firebase.cmd not found
                    exit /b 1
                )
            )
            echo [OK] firebase-tools installed
        """, returnStatus: true)

        if (installExit == 0) {
            firebaseCheck = checkFirebaseCLI()
        }

        if (!firebaseCheck.available) {
            error """[ERROR] Firebase CLI auto-install failed

FIX: Remote into the build agent, then:
  1. Delete '%APPDATA%\\npm\\node_modules\\firebase-tools' to clear file locks/EPERM issues
  2. Ensure '${nodejsDir}' is in the system-level PATH environment variable
  3. Run: "${nodejsDir}\\npm.cmd" install -g --prefix "${nodejsDir}" firebase-tools
  4. Verify: "${nodejsDir}\\firebase.cmd" --version
  5. Restart the Jenkins agent service"""
        }
        echo "[OK] Firebase CLI installed"
    }
    echo "[OK] ${firebaseCheck.message}"

    // Verify Firebase CLI works and validate the app ID exists with Crashlytics access
    // Reuses google-play-json credential (add Firebase Crashlytics Admin role to the service account)
    // Use FIREBASE_CMD if set (for Jenkins service accounts without PATH)
    def firebaseCmd = env.FIREBASE_CMD ?: 'firebase'
    def nodejsDirValidation = (env.NODEJS_HOME ?: 'C:\\Program Files\\nodejs').replace('/', '\\')
    withCredentials([file(credentialsId: 'google-play-json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        bat """
            @echo off
            REM Ensure Node.js is in PATH (required for firebase.cmd)
            if exist "${nodejsDirValidation}\\node.exe" set "PATH=${nodejsDirValidation};%PATH%"
            "${firebaseCmd}" --version || exit /b 1
            echo [OK] Firebase CLI available

            echo [INFO] Validating Firebase App ID: ${firebaseAppId}
            "${firebaseCmd}" apps:sdkconfig --app="${firebaseAppId}" >nul 2>&1
            if errorlevel 1 (
                echo [ERROR] Firebase App ID validation failed - app not found or credentials invalid
                echo.
                echo To fix:
                echo   1. Verify google-services.json contains the correct mobilesdk_app_id
                echo   2. Add Firebase Admin permissions to google-play-json service account:
                echo      - Go to: https://console.firebase.google.com
                echo      - Project Settings ^> Service accounts
                echo      - Ensure the service account has Firebase Admin role
                echo   3. Alternatively, use Google Cloud Console:
                echo      - Go to: https://console.cloud.google.com/iam-admin/iam
                echo      - Find the service account email from google-play-json
                echo      - Grant role: Firebase Admin
                exit /b 1
            )
            echo [OK] Firebase App ID validated

            echo [INFO] Validating Crashlytics upload access...
            REM Create unique temp dir to test upload permissions
            set "TEMP_TEST_DIR=%TEMP%\\firebase_preflight_%BUILD_NUMBER%_%RANDOM%"
            if exist "%TEMP_TEST_DIR%" rmdir /s /q "%TEMP_TEST_DIR%"
            mkdir "%TEMP_TEST_DIR%"

            REM Try to upload - expect "no symbols found" error (exit code 1) but NOT auth errors
            "${firebaseCmd}" crashlytics:symbols:upload --app="${firebaseAppId}" "%TEMP_TEST_DIR%" 2>&1 | findstr /i "permission denied unauthorized forbidden invalid" >nul
            if not errorlevel 1 (
                echo [ERROR] Crashlytics upload permission denied - service account lacks Crashlytics permissions
                echo.
                echo To fix:
                echo   1. Go to: https://console.firebase.google.com
                echo   2. Select your project ^> Project Settings ^> Service accounts
                echo   3. Find the service account used in google-play-json credential
                echo   4. Grant role: Firebase Crashlytics Admin
                echo.
                echo   Alternatively via Google Cloud Console:
                echo   1. Go to: https://console.cloud.google.com/iam-admin/iam
                echo   2. Find the service account email
                echo   3. Add role: Firebase Crashlytics Admin
                rmdir /s /q "%TEMP_TEST_DIR%"
                exit /b 1
            )

            rmdir /s /q "%TEMP_TEST_DIR%"
            echo [OK] Crashlytics upload access validated
        """
    }

    echo "[OK] Firebase CLI preflight passed (App ID: ${firebaseAppId})"
}

// ============================================================================
// UNITY FUNCTIONS
// ============================================================================

/**
 * Preflight check for Unity license activation.
 * Checks if a valid license file exists on the agent. If not, activates
 * using the Build Server serial key (node-locked to this machine).
 *
 * Required Jenkins credentials:
 *   unity-build-serial  - Secret text: Build Server serial (SB-XXXX-XXXX-XXXX-XXXX-XXXX)
 *   unity-credentials   - Username/password: Unity account for activation
 */
def preflightUnityLicense() {
    // Check if a license file already exists
    def hasLicense = bat(
        script: '@if exist "C:\\ProgramData\\Unity\\Unity_lic.ulf" echo found',
        returnStdout: true
    ).trim()

    if (hasLicense == 'found') {
        echo "[OK] Unity license file found (C:\\ProgramData\\Unity\\Unity_lic.ulf)"
        return
    }

    echo "[INFO] No Unity license found on this agent - attempting activation..."

    if (!env.UNITY_VERSION) {
        error "[ERROR] Cannot activate Unity license: UNITY_VERSION not set. Run extractUnityVersion() first."
    }

    def unityExe = "C:\\UnityEditors\\${env.UNITY_VERSION}\\Editor\\Unity.exe"
    def exeExists = bat(script: "@if exist \"${unityExe}\" echo found", returnStdout: true).trim()
    if (exeExists != 'found') {
        error "[ERROR] Unity editor not found at ${unityExe}. Run validateUnityInstallation() first."
    }

    withCredentials([
        string(credentialsId: 'unity-build-serial', variable: 'UNITY_SERIAL'),
        usernamePassword(credentialsId: 'unity-credentials', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD')
    ]) {
        bat """
            @echo off
            echo [INFO] Activating Unity Build Server license...
            "${unityExe}" -quit -batchmode -nographics -serial "%UNITY_SERIAL%" -username "%UNITY_USERNAME%" -password "%UNITY_PASSWORD%" -logFile - 2>&1
            if errorlevel 1 (
                echo [ERROR] Unity license activation failed with exit code %errorlevel%
                exit /b 1
            )
            echo [OK] Unity license activated successfully
        """
    }

    // Verify the license file was created
    // Note: Build Server licenses (SB-XXXX) may not always create Unity_lic.ulf —
    // they can use a different licensing mechanism. Trust Unity's exit code as primary signal.
    def verified = bat(
        script: '@if exist "C:\\ProgramData\\Unity\\Unity_lic.ulf" echo found',
        returnStdout: true
    ).trim()

    if (verified != 'found') {
        echo "[WARN] Unity activation succeeded (exit code 0) but Unity_lic.ulf was not found at C:\\ProgramData\\Unity\\. Build Server licenses may store licensing differently — proceeding."
    } else {
        echo "[OK] Unity license activated and verified on this agent"
    }
}

// ============================================================================

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
    def platformModules = getRequiredUnityModules(env.PLATFORM)
    def autoInstallableModules = (platformModules - 'nintendo-switch') ?: []

    // Auto-install Unity if missing — pass modules so Hub installs everything in one pass
    def unityCheck = checkUnity(env.UNITY_VERSION, true, autoInstallableModules)

    if (!unityCheck.available) {
        error "[ERROR] Unity ${env.UNITY_VERSION} installation failed: ${unityCheck.message}"
    }

    if (unityCheck.installed) {
        echo "[OK] Unity ${env.UNITY_VERSION} was automatically installed"
    } else {
        echo "[OK] Unity ${env.UNITY_VERSION} found"
    }

    // Add Windows Firewall rule for Unity to prevent the firewall dialog from
    // blocking headless builds. Silently succeeds if rule already exists.
    def unityExe = "C:\\UnityEditors\\${env.UNITY_VERSION}\\Editor\\Unity.exe"
    bat(script: "@netsh advfirewall firewall add rule name=\"Unity ${env.UNITY_VERSION}\" dir=in action=allow program=\"${unityExe}\" enable=yes profile=any >nul 2>&1", returnStatus: true)

    // Log PlaybackEngines contents for diagnostics
    def playbackEngines = getPlaybackEnginesPath(env.UNITY_VERSION)
    try {
        def peContents = bat(script: """
            @echo off
            dir /b "${playbackEngines}" 2>&1
        """, returnStdout: true).trim()
        echo "[INFO] PlaybackEngines contents:\n${peContents}"

        // Show platform-specific module contents if they exist
        def platformDirs = [
            'Android': 'AndroidPlayer',
            'Amazon': 'AndroidPlayer',
            'iOS': 'iOSSupport',
            'StandaloneWindows64': 'WindowsStandaloneSupport',
            'StandaloneLinux64': 'LinuxStandaloneSupport',
            'Switch': 'Switch',
            'GameCoreXboxSeries': 'GameCoreXboxSeries',
            'GameCoreXboxOne': 'GameCoreXboxOne',
            'PS5': 'PS5'
        ]
        def platformDir = platformDirs[env.PLATFORM]
        if (platformDir) {
            def modContents = bat(script: """
                @echo off
                dir /b "${playbackEngines}\\${platformDir}" 2>&1
            """, returnStdout: true).trim()
            echo "[INFO] ${platformDir} contents:\n${modContents}"
        }
    } catch (Exception e) {
        echo "[WARN] Could not list PlaybackEngines: ${e.message}"
    }

    if (!autoInstallableModules) {
        echo "[OK] No auto-installable Unity modules required for ${env.PLATFORM}"
        return
    }

    echo "[INFO] Required modules for ${env.PLATFORM}: ${autoInstallableModules.join(', ')}"

    // Check modules — if editor was just installed with -m flags, these should already be present
    def modulesCheck = checkUnityModules(env.UNITY_VERSION, autoInstallableModules, true)

    if (modulesCheck.installed) {
        echo "[OK] Unity modules automatically installed"
    } else if (modulesCheck.available) {
        echo "[OK] Unity modules already installed"
    } else {
        // Standalone IL2CPP modules (windows-il2cpp, linux-il2cpp) are part of the Unity editor install.
        // Unity Hub often fails to install them with "Validation Failed" (file locks, license issues, etc.).
        // Warn instead of failing - if the module is truly missing, Unity will error during the build step.
        def standaloneModules = ['windows-il2cpp', 'linux-il2cpp']
        if (autoInstallableModules.every { it in standaloneModules }) {
            echo "[WARN] Could not verify standalone IL2CPP modules: ${modulesCheck.message}"
            echo "[WARN] Build will continue - Unity will fail during build if the module is genuinely missing"
        } else {
            error "[ERROR] Failed to install required Unity modules: ${modulesCheck.message}"
        }
    }

    // Verify IL2CPP support for platforms that require it
    // The Pipeline*.cs scripts build IL2CPP for Debug and Release alike (Android Debug was Mono)
    def il2cppAlways = ['Switch', 'GameCoreXboxSeries', 'GameCoreXboxOne', 'PS5', 'Android', 'Amazon']
    def needsIl2cpp = env.PLATFORM in il2cppAlways

    if (needsIl2cpp) {
        verifyIl2cppSupport(playbackEngines)
    }

    // For Android IL2CPP builds, also verify NDK is present
    if (env.PLATFORM in ['Android', 'Amazon'] && needsIl2cpp) {
        verifyAndroidNdk(playbackEngines)
    }

    // Verify Android OpenJDK is present — Hub's -cm flag should install it with the
    // 'android' module, but Unity 6 renamed the child module ID (e.g. android-open-jdk-17.0.9+9)
    // so we can't install it by the old name. If missing, query Hub for the real ID and retry.
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
 * The IL2CPP compiler lives in Editor\Data\il2cpp\ and is needed by all
 * platforms that use IL2CPP scripting backend (every Debug and Release player build).
 */
def verifyIl2cppSupport(String playbackEngines) {
    echo "[INFO] Verifying IL2CPP support for ${env.PLATFORM} (BUILD_TYPE: ${env.BUILD_TYPE ?: 'unset'})..."

    def basePath = "C:\\UnityEditors\\${env.UNITY_VERSION}"
    def il2cppPaths = [
        "${basePath}\\Editor\\Data\\il2cpp",
        "${basePath}\\il2cpp"
    ]

    // Also check platform-specific IL2CPP locations
    def platformIl2cppDirs = [
        'StandaloneWindows64': "${playbackEngines}\\WindowsStandaloneSupport\\il2cpp",
        'StandaloneLinux64': "${playbackEngines}\\LinuxStandaloneSupport\\il2cpp",
        'Android': "${playbackEngines}\\AndroidPlayer\\il2cpp",
        'Amazon': "${playbackEngines}\\AndroidPlayer\\il2cpp",
        'Switch': "${playbackEngines}\\Switch\\il2cpp",
        'GameCoreXboxSeries': "${playbackEngines}\\GameCoreXboxSeries\\il2cpp",
        'GameCoreXboxOne': "${playbackEngines}\\GameCoreXboxOne\\il2cpp",
        'PS5': "${playbackEngines}\\PS5\\il2cpp"
    ]
    def platformIl2cpp = platformIl2cppDirs[env.PLATFORM]
    if (platformIl2cpp) {
        il2cppPaths << platformIl2cpp
    }

    def foundPath = null
    il2cppPaths.each { path ->
        if (!foundPath) {
            def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
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
        def il2cppModules = [
            'StandaloneWindows64': 'windows-il2cpp',
            'StandaloneLinux64': 'linux-il2cpp'
        ]
        def il2cppModule = il2cppModules[env.PLATFORM]
        if (il2cppModule) {
            echo "[INFO] Attempting to install ${il2cppModule} module..."
            try {
                installUnityModules(env.UNITY_VERSION, [il2cppModule])
            } catch (Exception e) {
                echo "[WARN] ${il2cppModule} install failed: ${e.message}"
            }

            // Re-check
            def recheck = il2cppPaths.any { path ->
                bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim() == 'found'
            }
            if (recheck) {
                echo "[OK] IL2CPP installed successfully"
                return
            }
        }

        def suggestedModule = il2cppModule ?: 'windows-il2cpp'
        error "[ERROR] IL2CPP is not available for ${env.PLATFORM}. " +
              "Reinstall Unity ${env.UNITY_VERSION} with IL2CPP support via Unity Hub:\n" +
              "\"Unity Hub.exe\" -- --headless install-modules -v ${env.UNITY_VERSION} -m ${suggestedModule} -cm"
    }
}

/**
 * Verify Android NDK is available (required for Android IL2CPP builds).
 * Unity 6+ installs the NDK at AndroidPlayer\NDK\, older versions use AndroidPlayer\SDK\ndk\.
 */
def verifyAndroidNdk(String playbackEngines) {
    def ndkPaths = [
        "${playbackEngines}\\AndroidPlayer\\NDK",           // Unity 6+ layout
        "${playbackEngines}\\AndroidPlayer\\SDK\\ndk"       // Legacy layout
    ]

    def foundPath = null
    ndkPaths.each { path ->
        if (!foundPath) {
            def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
            if (exists == 'found') {
                foundPath = path
            }
        }
    }

    if (foundPath) {
        // List NDK versions/contents present
        try {
            def ndkContents = bat(script: """
                @echo off
                dir /b "${foundPath}" 2>&1
            """, returnStdout: true).trim()
            echo "[OK] Android NDK found at: ${foundPath}\n${ndkContents}"
        } catch (Exception e) {
            echo "[OK] Android NDK found at: ${foundPath}"
        }
    } else {
        echo "[WARN] Android NDK not found in expected locations:"
        ndkPaths.each { echo "  - ${it}" }
        echo "[INFO] NDK is required for IL2CPP Android builds. It should be installed with the android-sdk-ndk-tools module."
        echo "[INFO] Attempting to install android-sdk-ndk-tools..."
        try {
            installUnityModules(env.UNITY_VERSION, ['android-sdk-ndk-tools'])
        } catch (Exception e) {
            echo "[WARN] android-sdk-ndk-tools install failed: ${e.message}"
        }

        // Re-check all paths
        ndkPaths.each { path ->
            if (!foundPath) {
                def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
                if (exists == 'found') {
                    foundPath = path
                }
            }
        }
        if (foundPath) {
            echo "[OK] Android NDK installed successfully at: ${foundPath}"
        } else {
            error "[ERROR] Android NDK is required for IL2CPP builds but is not installed.\n" +
                  "Install via Unity Hub: \"Unity Hub.exe\" -- --headless install-modules -v ${env.UNITY_VERSION} -m android-sdk-ndk-tools -cm"
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
    def jdkPath = "${playbackEngines}\\AndroidPlayer\\OpenJDK"
    def exists = bat(script: "@if exist \"${jdkPath}\\bin\\java.exe\" echo found", returnStdout: true).trim()

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
    def hubExe = env.UNITY_HUB_PATH
    def hubProcess = hubExe.split('\\\\').last()
    bat script: "@taskkill /f /im \"${hubProcess}\" >nul 2>&1 || exit /b 0"

    def cmd = "cmd /c \"\"${hubExe}\" -- --headless install-modules --version ${env.UNITY_VERSION} -m android-open-jdk -cm\""
    echo "[INFO] Running: ${cmd}"
    def output = ''
    try {
        timeout(time: 15, unit: 'MINUTES') {
            output = bat(script: "@${cmd} 2>&1 || exit /b 0", returnStdout: true).trim()
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
    exists = bat(script: "@if exist \"${jdkPath}\\bin\\java.exe\" echo found", returnStdout: true).trim()
    if (exists == 'found') {
        echo "[OK] Android OpenJDK installed successfully"
    } else {
        error "[ERROR] Android OpenJDK is not installed and could not be auto-installed.\n" +
              "The Unity bundled JDK is required — system JDKs are incompatible.\n" +
              "Install manually on the build agent:\n" +
              "  \"Unity Hub.exe\" -- --headless install-modules -v ${env.UNITY_VERSION} -m android-open-jdk -cm\n" +
              "If Hub says 'Couldn't find module', use the versioned name it suggests (e.g. android-open-jdk-17.0.9+9)."
    }
}

def validateLinuxBuildSupport() {
    echo "[INFO] Verifying Linux Build Support (IL2CPP) module installation..."
    def linuxModuleCheck = checkUnityModules(env.UNITY_VERSION, ['linux-il2cpp'], true)

    if (!linuxModuleCheck.available) {
        error "[ERROR] Linux Build Support (IL2CPP) is not installed for Unity ${env.UNITY_VERSION}\n" +
              "Without this module, Unity silently falls back to building Windows executables.\n" +
              "Install via Unity Hub GUI: Installs > ${env.UNITY_VERSION} > Add Modules > Linux Build Support (IL2CPP)\n" +
              "Or via Unity Hub CLI: \"Unity Hub.exe\" -- --headless install-modules -v ${env.UNITY_VERSION} -m linux-il2cpp -cm"
    }

    echo "[OK] Linux Build Support (IL2CPP) module verified"
}

def validateWindowsIl2CppSupport() {
    echo "[INFO] Verifying Windows IL2CPP module installation..."
    def moduleCheck = checkUnityModules(env.UNITY_VERSION, ['windows-il2cpp'], true)

    if (!moduleCheck.available) {
        error "[ERROR] Windows IL2CPP module is not installed for Unity ${env.UNITY_VERSION}\n" +
              "Without this module, Unity cannot build with the IL2CPP scripting backend.\n" +
              "Install via Unity Hub GUI: Installs > ${env.UNITY_VERSION} > Add Modules > Windows Build Support (IL2CPP)\n" +
              "Or via Unity Hub CLI: \"Unity Hub.exe\" -- --headless install-modules -v ${env.UNITY_VERSION} -m windows-il2cpp -cm"
    }

    echo "[OK] Windows IL2CPP module verified"
}

def validateNintendoSwitchSupport() {
    echo "[INFO] Verifying Nintendo Switch module installation..."
    def switchModuleCheck = checkUnityModules(env.UNITY_VERSION, ['nintendo-switch'], false)

    if (!switchModuleCheck.available) {
        error "[ERROR] Nintendo Switch module is not installed for Unity ${env.UNITY_VERSION}\n" +
              "The Nintendo Switch module must be installed manually from Nintendo Developer Portal.\n" +
              "Download Unity ${env.UNITY_VERSION} with Nintendo Switch support from: https://developer.nintendo.com/\n" +
              "Install the Nintendo Switch module, then retry this build."
    }

    echo "[OK] Nintendo Switch module verified"
}

/**
 * Verify a licensed console playback engine is installed for the active Unity version.
 *
 * Console modules (Xbox/GameCore, PS5) are not distributed through Unity Hub, so there is no
 * module ID to query or auto-install — the only reliable signal is the platform folder inside
 * PlaybackEngines. Folder naming has changed across Unity versions and SDK generations, so each
 * caller passes the names it accepts and the error lists every path that was probed.
 *
 * Sets env.CONSOLE_PLAYBACK_ENGINE to the folder that matched.
 */
private def validateConsolePlaybackEngine(String label, List candidates, String fixText) {
    echo "[INFO] Verifying ${label} module installation..."
    def playbackEngines = getPlaybackEnginesPath(env.UNITY_VERSION)

    def found = null
    candidates.each { name ->
        if (!found) {
            def path = "${playbackEngines}\\${name}"
            def exists = bat(script: "@if exist \"${path}\" echo found", returnStdout: true).trim()
            if (exists == 'found') found = path
        }
    }

    if (!found) {
        def probed = candidates.collect { "  - ${playbackEngines}\\${it}" }.join('\n')
        error "[ERROR] ${label} build support is not installed for Unity ${env.UNITY_VERSION}\n" +
              "Without it Unity cannot build this target (and may silently fall back to another platform).\n" +
              "Probed:\n${probed}\n\n" +
              "[FIX] ${fixText}"
    }

    env.CONSOLE_PLAYBACK_ENGINE = found
    echo "[OK] ${label} playback engine: ${found}"
}

def validateXboxSupport() {
    // GameCoreXboxSeries (Xbox Series X|S) and GameCoreXboxOne (Xbox One, GDK) install as separate
    // playback engines — validate the one this job is actually building.
    def target = env.PLATFORM ?: 'GameCoreXboxSeries'
    def candidates = (target == 'GameCoreXboxOne')
        ? ['GameCoreXboxOne', 'XboxOne']
        : ['GameCoreXboxSeries', 'GameCoreScarlett']
    validateConsolePlaybackEngine(
        "Xbox (${target})",
        candidates,
        "Install Unity ${env.UNITY_VERSION} ${target} build support from the Xbox developer portal " +
        "(Unity console downloads are distributed there, not through Unity Hub), then retry this build."
    )
}

def validatePS5Support() {
    validateConsolePlaybackEngine(
        'PlayStation 5',
        ['PS5', 'PS5Player', 'PlayStation5'],
        "Install Unity ${env.UNITY_VERSION} PS5 build support from the PlayStation Partners developer " +
        "portal (Unity console downloads are distributed there, not through Unity Hub), then retry this build."
    )
}

/**
 * Locate the Microsoft GDK and export its roots for the Unity build.
 *
 * The GDK installer sets GameDK / GameDKLatest / GRDKLatest / GXDKLatest as machine environment
 * variables, but a Jenkins agent service started before the install won't see them, so this also
 * falls back to scanning the default install root. GXDK (the console GDK) is what GameCore player
 * builds need; GRDK alone (PC GDK) is not enough.
 */
def preflightXboxGDK() {
    def result = bat(
        script: '''
            @echo off
            setlocal enabledelayedexpansion

            set "GDK_BASE="
            if defined GameDK set "GDK_BASE=%GameDK%"
            if not defined GDK_BASE if exist "C:\\Program Files (x86)\\Microsoft GDK" set "GDK_BASE=C:\\Program Files (x86)\\Microsoft GDK"

            set "GXDK_DIR="
            if defined GXDKLatest if exist "%GXDKLatest%" set "GXDK_DIR=%GXDKLatest%"

            REM Fall back to the last (highest-numbered) version folder that contains a GXDK.
            REM Every failure path exits 0 and reports through stdout: with returnStdout a non-zero
            REM exit throws and Jenkins discards the captured output, hiding the diagnostics.
            if not defined GXDK_DIR (
                if not defined GDK_BASE (
                    echo [ERROR] Microsoft GDK not found: GameDK is not set and the default install root is missing
                    goto :eof
                )
                for /d %%D in ("%GDK_BASE%\\*") do (
                    if exist "%%D\\GXDK" set "GXDK_DIR=%%D\\GXDK"
                )
            )

            if not defined GXDK_DIR (
                echo [ERROR] No GXDK found under %GDK_BASE%
                echo [INFO] Installed GDK versions:
                for /d %%D in ("%GDK_BASE%\\*") do echo   - %%~nxD
                goto :eof
            )

            REM Strip any trailing backslash so downstream path joins stay clean
            if "!GXDK_DIR:~-1!"=="\\" set "GXDK_DIR=!GXDK_DIR:~0,-1!"

            if defined GDK_BASE echo GAMEDK_ROOT=!GDK_BASE!
            echo GXDK_ROOT=!GXDK_DIR!

            if exist "!GXDK_DIR!\\bin\\makepkg.exe" (
                echo MAKEPKG_PATH=!GXDK_DIR!\\bin\\makepkg.exe
            ) else (
                echo [INFO] makepkg.exe not found under !GXDK_DIR!\\bin - packaging tools unavailable on this agent
            )
        ''',
        returnStdout: true
    ).trim()

    def gxdkMatch = result =~ /GXDK_ROOT=(.+)/
    if (!gxdkMatch) {
        error "${result}\n\n[FIX] Install the Microsoft GDK (with Xbox console support) on this agent, " +
              "then restart the Jenkins agent service so it picks up the GDK environment variables."
    }
    env.GXDK_ROOT = gxdkMatch[0][1].trim()
    // Re-export in the form the GDK tooling expects (installer sets it with a trailing separator).
    // Derived from GXDKLatest when the agent already had it, so this never picks a different SDK.
    env.GXDKLatest = "${env.GXDK_ROOT}\\"
    echo "[OK] Microsoft GDK (GXDK): ${env.GXDK_ROOT}"

    def baseMatch = result =~ /GAMEDK_ROOT=(.+)/
    if (baseMatch) {
        env.GameDK = baseMatch[0][1].trim()
        echo "[OK] GDK root: ${env.GameDK}"
    }

    def makepkgMatch = result =~ /MAKEPKG_PATH=(.+)/
    if (makepkgMatch) {
        env.MAKEPKG_PATH = makepkgMatch[0][1].trim()
        echo "[INFO] makepkg available: ${env.MAKEPKG_PATH} (packaging is not run by this pipeline)"
    }
}

/**
 * Locate the PS5 (Prospero) SDK and export its roots for the Unity build.
 *
 * The SDK installer sets SCE_ROOT_DIR and SCE_PROSPERO_SDK_DIR; as with the GDK, an agent service
 * started before the install won't see them, so the default install root is scanned as a fallback.
 */
def preflightPS5SDK() {
    def result = bat(
        script: '''
            @echo off
            setlocal enabledelayedexpansion

            set "SCE_BASE="
            if defined SCE_ROOT_DIR set "SCE_BASE=%SCE_ROOT_DIR%"
            if not defined SCE_BASE if exist "C:\\Program Files (x86)\\SCE" set "SCE_BASE=C:\\Program Files (x86)\\SCE"

            set "PROSPERO_DIR="
            if defined SCE_PROSPERO_SDK_DIR if exist "%SCE_PROSPERO_SDK_DIR%" set "PROSPERO_DIR=%SCE_PROSPERO_SDK_DIR%"

            REM Fall back to the last (highest-numbered) installed Prospero SDK. As above, failure
            REM paths exit 0 and report through stdout so the diagnostics survive returnStdout.
            if not defined PROSPERO_DIR (
                if not defined SCE_BASE (
                    echo [ERROR] PS5 SDK not found: SCE_ROOT_DIR is not set and the default install root is missing
                    goto :eof
                )
                for /d %%D in ("%SCE_BASE%\\Prospero SDKs\\*") do (
                    set "PROSPERO_DIR=%%D"
                )
            )

            if not defined PROSPERO_DIR (
                echo [ERROR] No Prospero SDK found under %SCE_BASE%
                echo [INFO] Contents of %SCE_BASE%:
                for /d %%D in ("%SCE_BASE%\\*") do echo   - %%~nxD
                goto :eof
            )

            if "!PROSPERO_DIR:~-1!"=="\\" set "PROSPERO_DIR=!PROSPERO_DIR:~0,-1!"

            if defined SCE_BASE echo SCE_ROOT=!SCE_BASE!
            echo PROSPERO_SDK=!PROSPERO_DIR!
        ''',
        returnStdout: true
    ).trim()

    def sdkMatch = result =~ /PROSPERO_SDK=(.+)/
    if (!sdkMatch) {
        error "${result}\n\n[FIX] Install the PS5 (Prospero) SDK on this agent, then restart the Jenkins " +
              "agent service so it picks up the SCE environment variables."
    }
    env.SCE_PROSPERO_SDK_DIR = sdkMatch[0][1].trim()
    echo "[OK] PS5 SDK: ${env.SCE_PROSPERO_SDK_DIR}"

    def rootMatch = result =~ /SCE_ROOT=(.+)/
    if (rootMatch) {
        env.SCE_ROOT_DIR = rootMatch[0][1].trim()
        echo "[OK] SCE root: ${env.SCE_ROOT_DIR}"
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
    def userProfile = bat(script: '@echo %USERPROFILE%', returnStdout: true).trim()
    def markerFile = "${userProfile}\\.buildtools\\.lastbuild_${jobName}"
    def currentVersion = env.UNITY_VERSION ?: ''
    def currentBranch = env.PLASTICSCM_BRANCH ?: env.BRANCH ?: ''

    if (!currentVersion) {
        echo "[Cache Integrity] No Unity version set, skipping check"
        return
    }

    // Read previous build info
    def previousVersion = ''
    def previousBranch = ''
    def markerContent = bat(script: "@if exist \"${markerFile}\" (type \"${markerFile}\") else (echo __NONE__)", returnStdout: true).trim()
    if (markerContent != '__NONE__') {
        def lines = markerContent.split('\n')
        lines.each { line ->
            def l = line.trim()
            if (l.startsWith('unity_version=')) previousVersion = l.replace('unity_version=', '').trim()
            if (l.startsWith('branch=')) previousBranch = l.replace('branch=', '').trim()
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
    // Branch changes no longer trigger a wipe — per-branch cache junctions (setupBranchCaches,
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
        bat """
            @echo off
            @RD /S /Q "%USERPROFILE%\\.buildtools\\unitycache\\${jobName}" 2>nul
            echo [Cache Integrity] Cleared per-branch cache store for ${jobName}
            exit /b 0
        """
    } else {
        echo "[Cache Integrity] No version change, incremental caches valid"
    }

    // Always (re)establish the per-branch cache junctions for the current branch
    setupBranchCaches(unityProjectPath)
}

/**
 * Saves current build info (Unity version + branch) to a persistent marker file.
 * Called on successful builds so the next build can detect configuration changes.
 */
def saveBuildInfo() {
    def jobName = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    def userProfile = bat(script: '@echo %USERPROFILE%', returnStdout: true).trim()
    def markerFile = "${userProfile}\\.buildtools\\.lastbuild_${jobName}"

    def currentVersion = env.UNITY_VERSION ?: ''
    def currentBranch = env.PLASTICSCM_BRANCH ?: env.BRANCH ?: ''

    bat(script: """@echo off
        if not exist "${userProfile}\\.buildtools" mkdir "${userProfile}\\.buildtools"
        (
            echo unity_version=${currentVersion}
            echo branch=${currentBranch}
        ) > "${markerFile}"
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
    bat """
        @echo on
        for /D %%d in ("${env.WORKSPACE}\\*") do @RD /S /Q "%%d"
        del /F /Q "${env.WORKSPACE}\\*"
        @RD /S /Q "%USERPROFILE%\\.buildtools\\unitycache\\${jobKey}"
        echo [OK] Clear Workspace complete - workspace and per-branch cache store purged
        exit /b 0
    """
    return true
}

/**
 * Library cache dirs that get per-branch isolation via NTFS junctions into a persistent
 * store under %USERPROFILE%/.buildtools/unitycache/<job>/<branch>. These are the expensive
 * content-addressed build caches — keeping one copy per branch means switching branches just
 * repoints the junction instead of thrashing (and rebuilding) the other branch's cache.
 * The map key is the CLEAN_CACHE token; the value is the actual Library subfolder name.
 */
def branchLinkedCaches() {
    return [BuildCache: 'BuildCache', Bee: 'Bee', IL2CPP: 'il2cpp_cache', Addressables: 'com.unity.addressables']
}

def branchCacheStore() {
    def jobKey = env.JOB_NAME?.replaceAll('[^a-zA-Z0-9_-]', '_') ?: 'unknown'
    def branch = env.PLASTICSCM_BRANCH ?: env.BRANCH
    if (!branch) return null
    def branchKey = branch.replaceAll('[^a-zA-Z0-9_-]', '_')
    return "%USERPROFILE%\\.buildtools\\unitycache\\${jobKey}\\${branchKey}"
}

/**
 * (Re)point per-branch cache junctions for the current branch. Idempotent — safe to call
 * every build. Creates the store on first use, so a brand-new branch starts with an empty
 * (cold) cache and is fully incremental on subsequent builds of that branch.
 */
def setupBranchCaches(String unityProjectPath) {
    def store = branchCacheStore()
    if (!store) {
        echo "[Branch Cache] Branch unknown — skipping per-branch cache links"
        return
    }
    def library = "${unityProjectPath}/Library".replace('/', '\\')
    def cmds = []
    cmds << "if not exist \"${library}\" mkdir \"${library}\""
    cmds << "if not exist \"${store}\" mkdir \"${store}\""
    branchLinkedCaches().each { token, dir ->
        def target = "${store}\\${dir}"
        def link = "${library}\\${dir}"
        cmds << "if not exist \"${target}\" mkdir \"${target}\""
        // Drop any existing junction (rmdir removes the link only) or real dir (rmdir /s /q)
        cmds << "if exist \"${link}\" ( fsutil reparsepoint query \"${link}\" >nul 2>&1 && rmdir \"${link}\" || rmdir /s /q \"${link}\" )"
        cmds << "mklink /J \"${link}\" \"${target}\" >nul && echo [Branch Cache] linked ${dir}"
    }
    bat """
        @echo off
        ${cmds.join('\n        ')}
        exit /b 0
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

    def libraryPath = "${unityProjectPath}/Library".replace('/', '\\')
    def tempPath = "${unityProjectPath}/Temp".replace('/', '\\')
    def store = branchCacheStore()
    def linked = branchLinkedCaches()

    // Clear the real data behind a per-branch cache junction (NOT just the junction, which
    // would orphan the store) and re-establish an empty link. Falls back to a plain delete
    // if the branch (and therefore the store path) isn't known.
    def clearLinked = { String token ->
        def dir = linked[token]
        if (!store) return ["@RD /S /Q \"${libraryPath}\\${dir}\""]
        def target = "${store}\\${dir}"
        def link = "${libraryPath}\\${dir}"
        return [
            "if exist \"${link}\" ( fsutil reparsepoint query \"${link}\" >nul 2>&1 && rmdir \"${link}\" || rmdir /s /q \"${link}\" )",
            "@RD /S /Q \"${target}\"",
            "mkdir \"${target}\"",
            "mklink /J \"${link}\" \"${target}\" >nul"
        ]
    }

    // Clear Library - delete entire Library folder (and the whole per-branch cache store, so
    // the linked caches are cleared too rather than surviving outside the workspace)
    if (cacheTypes.contains('Clear Library')) {
        bat """
            @echo on
            @RD /S /Q "${libraryPath}"
            ${store ? "@RD /S /Q \"${store}\"" : "echo [Branch Cache] store unknown, skipped"}
            echo [OK] Clear Library: Deleted entire Library folder and per-branch cache store
            exit /b 0
        """
        // Re-create the per-branch cache junctions so this build stays linked after the wipe
        setupBranchCaches(unityProjectPath)
        return
    }

    // Build batch commands for each cache type
    def commands = []

    cacheTypes.each { cacheType ->
        switch (cacheType) {
            case 'All':
                commands << "@RD /S /Q \"${libraryPath}\\ShaderCache\""
                commands.addAll(clearLinked('BuildCache'))
                commands << "@RD /S /Q \"${libraryPath}\\ArtifactDB\""
                commands << "@RD /S /Q \"${libraryPath}\\SourceAssetDB\""
                commands << "@RD /S /Q \"${libraryPath}\\ScriptAssemblies\""
                commands << "@RD /S /Q \"${libraryPath}\\PackageCache\""
                commands.addAll(clearLinked('Bee'))
                commands.addAll(clearLinked('IL2CPP'))
                commands.addAll(clearLinked('Addressables'))
                commands << "@RD /S /Q \"${tempPath}\""
                commands << "del /f /q \"${unityProjectPath}\\Packages\\packages-lock.json\""
                commands << "@RD /S /Q \"%LOCALAPPDATA%\\Unity\\cache\\packages\""
                commands << "@RD /S /Q \"%LOCALAPPDATA%\\Unity\\cache\\upm\""
                break
            case 'ShaderCache':
                commands << "@RD /S /Q \"${libraryPath}\\ShaderCache\""
                break
            case 'BuildCache':
                commands.addAll(clearLinked('BuildCache'))
                break
            case 'ArtifactDB':
                commands << "@RD /S /Q \"${libraryPath}\\ArtifactDB\""
                commands << "@RD /S /Q \"${libraryPath}\\SourceAssetDB\""
                break
            case 'Temp':
                commands << "@RD /S /Q \"${tempPath}\""
                break
            case 'ScriptAssemblies':
                commands << "@RD /S /Q \"${libraryPath}\\ScriptAssemblies\""
                break
            case 'PackageCache':
                commands << "@RD /S /Q \"${libraryPath}\\PackageCache\""
                commands << "del /f /q \"${unityProjectPath}\\Packages\\packages-lock.json\""
                commands << "@RD /S /Q \"%LOCALAPPDATA%\\Unity\\cache\\packages\""
                commands << "@RD /S /Q \"%LOCALAPPDATA%\\Unity\\cache\\upm\""
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
            case 'None':
            case '----------':
                // Skip these
                break
            default:
                echo "[WARN] Unknown cache type: ${cacheType}"
        }
    }

    if (commands) {
        bat """
            @echo on
            ${commands.join('\n            ')}
            echo [OK] Cleaned cache types: ${cacheTypes.join(', ')}
            exit /b 0
        """
    }

    echo "Unity cache cleanup complete"
}

/**
 * Run a one-off pre-build PowerShell script supplied via the PREBUILD_SCRIPT
 * job parameter. Runs on the agent in the workspace after checkout but before
 * Unity opens — the place to clear stray agent-only state that a clean repo
 * can't fix (e.g. an orphaned Assets/Plugins/Android/*.androidlib left by an
 * old EDM4U resolve, or a stale Library/Bee/Android Gradle output).
 *
 * Best-effort by default: a non-zero exit is logged as a warning and the build
 * continues (cleanup commands like Remove-Item on a missing path return errors).
 * Set PREBUILD_FAIL_ON_ERROR=true (env) to make a failure abort the build.
 */
def runPrebuildScript(String script) {
    if (!script?.trim()) {
        echo "[Pre-Build] No PREBUILD_SCRIPT provided, skipping"
        return
    }

    echo "========== PRE-BUILD SCRIPT (PowerShell) =========="
    echo script
    echo "==================================================="

    def scriptFile = "${env.WORKSPACE}\\prebuild_script.ps1"
    writeFile file: scriptFile, text: script
    try {
        def exitCode = bat(script: """
            @echo on
            powershell -NoProfile -ExecutionPolicy Bypass -File "${scriptFile}"
            exit /b %errorlevel%
        """, returnStatus: true)

        if (exitCode != 0) {
            if (env.PREBUILD_FAIL_ON_ERROR == 'true') {
                error "[Pre-Build] Script failed with exit code ${exitCode} (PREBUILD_FAIL_ON_ERROR=true)"
            }
            echo "[Pre-Build] [WARN] Script exited with code ${exitCode} — continuing (set PREBUILD_FAIL_ON_ERROR=true to abort on failure)"
        } else {
            echo "[Pre-Build] Script completed successfully"
        }
    } finally {
        bat(script: "del /f /q \"${scriptFile}\" 2>nul & exit /b 0", returnStatus: true)
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

    def importWorkers = config.importWorkers ?: 8

    def quitFlag = quit ? '-quit' : ''
    def importWorkersFlag = importWorkers > 0 ? "-desiredWorkerCount ${importWorkers}" : ''
    def prepareDirs = ''
    if (buildPath && artifactPath) {
        prepareDirs = """
            @RD /S /Q "${buildPath}"
            @RD /S /Q "${artifactPath}"
            mkdir "${buildPath}"
            mkdir "${artifactPath}"
        """
    }

    // Nintendo Switch requires NINTENDO_SDK_ROOT environment variable
    // NINTENDO_SDK_HTC_GENERATION=2 forces the new NintendoDebugCore path in Nintendo.Tm.dll.
    // Without it, GetHtcGeneration() checks HKCU registry keys that may not exist under the
    // Jenkins service account, causing Unity to take the legacy path (port 17103) and fail with:
    // "TargetManager.Connect() failed!"
    def unityExePath = "C:/UnityEditors/%UNITY_VERSION%/Editor/Unity.exe"

    def nintendoEnv = ''
    if (platform == 'Switch') {
        nintendoEnv = """
            if not defined NINTENDO_SDK_ROOT (
                echo [ERROR] NINTENDO_SDK_ROOT not set. Run preflightNintendoSDK first.
                exit /b 1
            )
            set "NINTENDO_SDK_HTC_GENERATION=2"
            echo [INFO] NINTENDO_SDK_ROOT=%NINTENDO_SDK_ROOT%
            echo [INFO] NINTENDO_SDK_HTC_GENERATION=2
            if defined DOTNET_ROOT (
                echo [INFO] DOTNET_ROOT=%DOTNET_ROOT%
            ) else (
                if exist "C:\\Program Files\\dotnet\\dotnet.exe" (
                    set "DOTNET_ROOT=C:\\Program Files\\dotnet"
                    echo [INFO] DOTNET_ROOT set to C:\\Program Files\\dotnet
                ) else (
                    echo [INFO] .NET SDK not found - installing via winget...
                    winget install --id Microsoft.DotNet.SDK.8 --scope machine --silent --accept-source-agreements --accept-package-agreements
                    if exist "C:\\Program Files\\dotnet\\dotnet.exe" (
                        set "DOTNET_ROOT=C:\\Program Files\\dotnet"
                        echo [OK] .NET SDK 8 installed, DOTNET_ROOT set
                    ) else (
                        echo [ERROR] .NET SDK installation failed - Switch IL2CPP build will likely fail
                    )
                )
            )
        """
    }

    // Xbox (GameCore) and PS5 need their SDK roots in the build environment. preflightXboxGDK() /
    // preflightPS5SDK() set them on env so they're already inherited here — this block fails fast
    // with a pointed message if the preflight stage was skipped, rather than letting Unity fall
    // over deep inside IL2CPP with an unrelated error.
    def consoleEnv = ''
    if (platform in ['GameCoreXboxSeries', 'GameCoreXboxOne']) {
        consoleEnv = """
            if not defined GXDKLatest (
                echo [ERROR] GXDKLatest not set. Run preflightXboxGDK first.
                exit /b 1
            )
            echo [INFO] GXDKLatest=%GXDKLatest%
            if defined GameDK echo [INFO] GameDK=%GameDK%
        """
    } else if (platform == 'PS5') {
        consoleEnv = """
            if not defined SCE_PROSPERO_SDK_DIR (
                echo [ERROR] SCE_PROSPERO_SDK_DIR not set. Run preflightPS5SDK first.
                exit /b 1
            )
            echo [INFO] SCE_PROSPERO_SDK_DIR=%SCE_PROSPERO_SDK_DIR%
            if defined SCE_ROOT_DIR echo [INFO] SCE_ROOT_DIR=%SCE_ROOT_DIR%
        """
    }

    def cacheServerFlags = env.CACHE_SERVER_ENDPOINT ? "-EnableCacheServer -cacheServerEndpoint ${env.CACHE_SERVER_ENDPOINT}" : ''

    // CPU affinity mask limits cores visible to Unity/bee_backend, reducing parallel IL2CPP
    // compiler threads and peak memory usage. Without this, bee_backend uses all cores which
    // can cause "LLVM ERROR: out of memory" on machines with many cores.
    // Set IL2CPP_MAX_CORES in the Jenkins job environment config (e.g. 4).
    def affinitySetter = ''
    if (env.IL2CPP_MAX_CORES) {
        def affinityMap = ['1': '1', '2': '3', '4': 'F', '6': '3F', '8': 'FF', '12': 'FFF', '16': 'FFFF']
        def mask = affinityMap[env.IL2CPP_MAX_CORES]
        if (mask) {
            // Launch background PowerShell that waits for Unity to start, then limits its CPU affinity.
            // Unity runs normally so -logFile - stdout keeps streaming to Jenkins.
            affinitySetter = "start /B powershell -NoProfile -Command \"Start-Sleep 15; Get-Process Unity -EA SilentlyContinue | ForEach-Object { \\$_.ProcessorAffinity = 0x${mask}; Write-Host '[INFO] Set Unity CPU affinity to 0x${mask} (${env.IL2CPP_MAX_CORES} cores)' }\""
            echo "[INFO] Will limit IL2CPP compilation to ${env.IL2CPP_MAX_CORES} cores (affinity mask: 0x${mask})"
        } else {
            echo "[WARN] IL2CPP_MAX_CORES=${env.IL2CPP_MAX_CORES} not recognized. Valid values: 1, 2, 4, 6, 8, 12, 16"
        }
    }

    def unityCmd = "\"${unityExePath}\" -projectPath \"${unityProjectPath}\" ${cacheServerFlags} -batchmode -username \"%UNITY_USERNAME%\" -password \"%UNITY_PASSWORD%\" -buildTarget ${platform} ${quitFlag} ${importWorkersFlag} -executeMethod ${executeMethod} -logFile - -skipMissingProjectID -skipMissingUPID -accept-apiupdate -disable-assembly-updater 2>&1"

    def exitCode = bat(script: """
        @echo off
        ${prepareDirs}
        ${nintendoEnv}
        ${consoleEnv}
        echo [INFO] Running Unity: ${executeMethod}
        echo [INFO] Unity path: ${unityExePath}
        ${affinitySetter}
        ${unityCmd}
        exit /b %errorlevel%
    """, returnStatus: true)

    if (exitCode != 0) {
        // Check if this is a UPM (Package Manager) crash — exit code 101 or IPC failures.
        // Clearing UPM caches and retrying usually fixes it.
        def logSnippet = currentBuild.rawBuild.getLog(5000).join('\n')
        def isUpmCrash = logSnippet.contains('Server process stopped with exit code') ||
                         logSnippet.contains('Failed to resolve packages') ||
                         logSnippet.contains('An error occurred while resolving packages') ||
                         logSnippet.contains('IPC stream failed to read')

        if (isUpmCrash) {
            echo "========================================"
            echo "[RETRY] UPM crash detected — clearing package caches and retrying..."
            echo "========================================"
            bat """
                @echo off
                @RD /S /Q "${unityProjectPath}\\Library\\PackageCache" 2>nul
                @RD /S /Q "${unityProjectPath}\\Library\\upm" 2>nul
                del /f /q "${unityProjectPath}\\Packages\\packages-lock.json" 2>nul
                @RD /S /Q "%LOCALAPPDATA%\\Unity\\cache\\packages" 2>nul
                @RD /S /Q "%LOCALAPPDATA%\\Unity\\cache\\upm" 2>nul
                echo [OK] UPM caches cleared
            """

            bat """
                @echo off
                ${prepareDirs}
                ${nintendoEnv}
                echo [INFO] Retrying Unity: ${executeMethod}
                ${affinitySetter}
                ${unityCmd}
                if errorlevel 1 (
                    echo [ERROR] Unity command failed on retry with exit code %errorlevel%
                    exit /b 1
                )
            """
        } else {
            printUnityErrors()
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
    def buildTarget = config.buildTarget ?: 'StandaloneWindows64'
    def testResults = config.testResults ?: "${env.ARTIFACT_PATH}/TestResults.xml"
    def testFilter = config.testFilter ?: ''
    def testCategory = config.testCategory ?: ''

    def filterArg = testFilter ? "-testFilter \"${testFilter}\"" : ''
    def categoryArg = testCategory ? "-testCategory \"${testCategory}\"" : ''

    bat """
        @echo off
        if not exist "${env.ARTIFACT_PATH}" mkdir "${env.ARTIFACT_PATH}"
        echo [INFO] Running Unity ${testPlatform} tests (target: ${buildTarget})
        "C:/UnityEditors/%UNITY_VERSION%/Editor/Unity.exe" ^
            -projectPath "${unityProjectPath}" ^
            -batchmode ^
            -buildTarget ${buildTarget} ^
            -runTests ^
            -testPlatform ${testPlatform} ^
            -testResults "${testResults}" ^
            ${filterArg} ^
            ${categoryArg} ^
            -logFile - ^
            -skipMissingProjectID -skipMissingUPID
        if errorlevel 1 (
            echo [WARN] Unity tests exited with error - check test results XML
            exit /b 1
        )
        echo [OK] Unity tests completed
    """
}

def getBuildJobWorkspace(String platformSuffix) {
    def base = env.BUILD_JOB_BASE
    if (!base) error "BUILD_JOB_BASE env var not set - configure in Jenkins job environment"
    def jobName = "${base}_${platformSuffix}"
    def wsRoot = "${env.WORKSPACE}/.."
    return "${wsRoot}/${jobName}"
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
        // Step 1: Discover analyzable data (archives for Android, sharedassets for standalone, or Addressables)
        // Android builds: check for AAB/APK first — sharedassets0.assets in the build dir is the raw Gradle output
        // which UnityDataTool can't parse. The AAB must be extracted first.
        def isAndroid = env.PLATFORM?.toLowerCase()?.contains('android') || env.PLATFORM?.toLowerCase() == 'amazon'
        def discoveryResult = bat(script: """@echo off
setlocal EnableDelayedExpansion
echo Searching for Unity data in build output... >&2
${isAndroid ? 'goto :checkarchive' : ''}
set "FOUND="
for /r "${buildPath}" %%f in (sharedassets0.assets) do (
    if not defined FOUND (
        set "FOUND=%%~dpf"
        set "FOUND=!FOUND:~0,-1!"
    )
)
if defined FOUND (
    echo   Found sharedassets: !FOUND! >&2
    echo SHAREDASSETS
    echo !FOUND!
    goto :checkbr
)
:checkarchive
echo   Checking for archives... >&2
set "ARCHIVE="
for /r "${buildPath}" %%f in (*.aab *.apk) do (
    if not defined ARCHIVE set "ARCHIVE=%%~ff"
)
if defined ARCHIVE (
    echo   Found archive: !ARCHIVE! >&2
    echo ARCHIVE
    echo !ARCHIVE!
    goto :checkbr
)
echo   No archives found, checking Addressables... >&2
set "BUNDLE="
for /r "${addressablesBase}" %%f in (*.bundle) do (
    if not defined BUNDLE (
        set "BUNDLE=%%~dpf"
        set "BUNDLE=!BUNDLE:~0,-1!"
    )
)
if defined BUNDLE (
    echo   Found Addressables: !BUNDLE! >&2
    echo ADDRESSABLES
    echo !BUNDLE!
    goto :checkbr
)
echo   No analyzable data found >&2
echo NONE
exit /b 0
:checkbr
if exist "${buildReportPath}" (echo HAS_BUILDREPORT) else (echo NO_BUILDREPORT)""", returnStdout: true).trim()

        echo "  runUnityDataTool discovery stdout: ${discoveryResult}"
        def lines = discoveryResult.readLines().collect { it.trim() }.findAll { it }
        def discoveryType = lines[0]

        if (discoveryType == 'NONE') {
            echo "[INFO] No analyzable Unity data found - skipping (no build output, AAB/APK, or Addressables)"
            return
        }

        def analyzeDir = lines[1]

        // Step 2: If archive found, extract and find Unity data inside
        if (discoveryType == 'ARCHIVE') {
            def archiveFile = analyzeDir
            echo "[INFO] Android archive detected - extracting for analysis: ${archiveFile}"
            tempExtractDir = "${env.WORKSPACE}\\temp_analyze_extract"

            def extractResult = bat(script: """@echo off
setlocal EnableDelayedExpansion
echo Extracting archive for analysis... >&2
if exist "${tempExtractDir}" rmdir /s /q "${tempExtractDir}"
mkdir "${tempExtractDir}"
cd /d "${tempExtractDir}" && "%JAVA_HOME%\\bin\\jar" xf "${archiveFile}"
echo   Searching extracted contents... >&2
set "FOUND="
for /r "${tempExtractDir}" %%f in (sharedassets0.assets) do (
    if not defined FOUND (
        set "FOUND=%%~dpf"
        set "FOUND=!FOUND:~0,-1!"
    )
)
if defined FOUND (
    echo   Found Unity data: !FOUND! >&2
    echo !FOUND!
) else (
    echo   No Unity data in archive >&2
    echo NOT_FOUND
)
exit /b 0""", returnStdout: true).trim()

            def extractedDir = extractResult.readLines().collect { it.trim() }.findAll { it }.last()
            if (extractedDir == 'NOT_FOUND') {
                echo "[INFO] No Unity data found in extracted archive - skipping"
                return
            }
            analyzeDir = extractedDir
            echo "[INFO] Found Unity data in extracted archive: ${analyzeDir}"
        }

        // Step 3: Stage tool with correct API DLL, copy BuildReport, and run analysis
        def toolDir = toolPath.replace('\\UnityDataTool.exe', '')
        def stagedDir = "${env.WORKSPACE}\\temp_unity_data_tool"
        def stagedToolPath = "${stagedDir}\\UnityDataTool.exe"

        echo "[INFO] Analyzing: ${analyzeDir}"
        echo "[INFO] Unity version: ${env.UNITY_VERSION}"
        // Search multiple paths for UnityFileSystemApi.dll matching the project's Unity version.
        // The DLL MUST match the Unity version that produced the build, or analysis will crash.
        bat """@echo off
setlocal EnableDelayedExpansion
echo Staging UnityDataTool... >&2
if exist "${stagedDir}" rmdir /s /q "${stagedDir}"
xcopy /s /e /i /q "${toolDir}" "${stagedDir}" >nul

REM Copy UnityFileSystemApi.dll from the Unity editor into the staged tool directory.
REM The bundled DLL is NOT backwards compatible (github.com/Unity-Technologies/UnityDataTools/issues/26)
REM so we MUST overwrite it with the DLL from the editor that built this project.
set "EDITOR_DLL="
for %%p in (
    "C:\\UnityEditors\\${env.UNITY_VERSION}\\Editor\\Data\\Tools\\UnityFileSystemApi.dll"
    "C:\\Program Files\\Unity\\Hub\\Editor\\${env.UNITY_VERSION}\\Editor\\Data\\Tools\\UnityFileSystemApi.dll"
) do (
    if not defined EDITOR_DLL if exist %%p set "EDITOR_DLL=%%~p"
)
if not defined EDITOR_DLL (
    echo [ERROR] UnityFileSystemApi.dll not found for Unity ${env.UNITY_VERSION} >&2
    echo   Checked: C:\\UnityEditors\\${env.UNITY_VERSION}\\Editor\\Data\\Tools\\ >&2
    echo   Checked: C:\\Program Files\\Unity\\Hub\\Editor\\${env.UNITY_VERSION}\\Editor\\Data\\Tools\\ >&2
    exit /b 1
)
echo   Editor DLL: !EDITOR_DLL! >&2

REM Overwrite the bundled DLL at the tool root (where UnityDataTool.exe loads it from)
copy /y "!EDITOR_DLL!" "${stagedDir}\\UnityFileSystemApi.dll"
if errorlevel 1 (
    echo [ERROR] Failed to copy UnityFileSystemApi.dll to staged tool directory >&2
    exit /b 1
)
echo   Overwrote: ${stagedDir}\\UnityFileSystemApi.dll >&2
if exist "${buildReportPath}" (
    copy /y "${buildReportPath}" "${analyzeDir}\\LastBuild.buildreport" >nul 2>&1
    echo   BuildReport included >&2
)
echo Running analysis... >&2
if exist "${dbFile}" del "${dbFile}"
"${stagedToolPath}" analyze "${analyzeDir}" -o "${dbFile}"
REM Not "if errorlevel 1": a .NET crash exits negative (e.g. -532462766), which that test misses
if not "!ERRORLEVEL!"=="0" (
    echo [ERROR] Unity Data Tool analysis failed, exit code !ERRORLEVEL! >&2
    exit /b 1
)
echo [OK] Analysis complete >&2"""

        analyzeBuildReport(dbFile: dbFile)
    } catch (Exception e) {
        echo "[WARNING] Unity Data Tool failed: ${e.message}"
        common.setUnstable("Unity Data Tool analysis failed")
    } finally {
        // Clean up temp directories
        def cleanupScript = "@echo off"
        if (tempExtractDir) {
            cleanupScript += "\nif exist \"${tempExtractDir}\" rmdir /s /q \"${tempExtractDir}\""
        }
        cleanupScript += "\nif exist \"${env.WORKSPACE}\\temp_unity_data_tool\" rmdir /s /q \"${env.WORKSPACE}\\temp_unity_data_tool\""
        bat(script: cleanupScript, returnStatus: true)
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

    def exists = bat(script: "@if exist \"${dbFile}\" echo found", returnStdout: true).trim()
    if (exists != 'found') {
        echo "[INFO] No build report database found, skipping analysis"
        return
    }

    // Check for sqlite3 - use returnStatus to avoid throwing on non-zero exit
    def sqlite3Path
    def sqlite3InPath = bat(script: "@where sqlite3 >nul 2>&1", returnStatus: true) == 0
    if (sqlite3InPath) {
        sqlite3Path = bat(script: "@where sqlite3", returnStdout: true).trim().split('\n')[0].trim()
    } else {
        // Auto-install sqlite3 to buildtools
        def toolsDir = getToolsDir()
        sqlite3Path = "${toolsDir}\\sqlite3.exe"
        def sqliteExists = bat(script: "@if exist \"${sqlite3Path}\" echo found", returnStdout: true).trim()
        if (sqliteExists != 'found') {
            echo "[INFO] Installing sqlite3..."
            bat """
                @echo off
                powershell -Command "\$ErrorActionPreference='Stop'; try { Invoke-WebRequest -Uri 'https://www.sqlite.org/2024/sqlite-tools-win-x64-3470200.zip' -OutFile '%TEMP%\\sqlite3.zip'; Expand-Archive -Path '%TEMP%\\sqlite3.zip' -DestinationPath '%TEMP%\\sqlite3_tmp' -Force; \$exe = Get-ChildItem -Path '%TEMP%\\sqlite3_tmp' -Filter 'sqlite3.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1; if (\$exe) { Copy-Item \$exe.FullName '${toolsDir}\\sqlite3.exe' -Force; Write-Host '[OK] sqlite3 installed from:' \$exe.FullName } else { Write-Host '[ERROR] sqlite3.exe not found in zip'; Get-ChildItem -Path '%TEMP%\\sqlite3_tmp' -Recurse | ForEach-Object { Write-Host \$_.FullName } } } catch { Write-Host '[ERROR] sqlite3 install failed:' \$_.Exception.Message }"
            """
            sqliteExists = bat(script: "@if exist \"${sqlite3Path}\" echo found", returnStdout: true).trim()
            if (sqliteExists != 'found') {
                echo "[WARNING] Could not install sqlite3, skipping build report analysis"
                return
            }
        }
    }

    echo "========================================"
    echo "BUILD REPORT ANALYSIS"
    echo "========================================"

    // Discover which views exist in the DB — schema varies by build type (standalone vs Android vs Addressables)
    def availableViews = bat(script: "@\"${sqlite3Path}\" \"${dbFile}\" \"SELECT name FROM sqlite_master WHERE type='view';\"", returnStdout: true).trim()
    def hasView = { String name -> availableViews.contains(name) }
    echo "[INFO] Available views: ${availableViews.replaceAll('\n', ', ') ?: 'none'}"

    try {
        // Query 1: Large shaders (top 3 + total count)
        if (hasView('shader_view')) {
        def shaderCountQuery = """SELECT count(*) FROM (SELECT name FROM shader_view GROUP BY name HAVING sum(size) > ${shaderThresholdMB * 1024 * 1024});"""
        def shaderTotal = (bat(script: "@\"${sqlite3Path}\" \"${dbFile}\" \"${shaderCountQuery}\"", returnStdout: true).trim() ?: '0') as Integer
        def shaderQuery = """SELECT name, count(*) as instances, sum(size) as total_size FROM shader_view GROUP BY name HAVING total_size > ${shaderThresholdMB * 1024 * 1024} ORDER BY total_size DESC LIMIT 3;"""
        def shaderOutput = bat(script: "@\"${sqlite3Path}\" -separator \"|\" \"${dbFile}\" \"${shaderQuery}\"", returnStdout: true).trim()
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
        }

        // Query 2: Large textures (top 3 + total count)
        if (hasView('texture_view')) {
        def texCountQuery = """SELECT count(*) FROM texture_view WHERE size > ${textureThresholdMB * 1024 * 1024};"""
        def texTotal = (bat(script: "@\"${sqlite3Path}\" \"${dbFile}\" \"${texCountQuery}\"", returnStdout: true).trim() ?: '0') as Integer
        def textureQuery = """SELECT name, width, height, size FROM texture_view WHERE size > ${textureThresholdMB * 1024 * 1024} ORDER BY size DESC LIMIT 3;"""
        def textureOutput = bat(script: "@\"${sqlite3Path}\" -separator \"|\" \"${dbFile}\" \"${textureQuery}\"", returnStdout: true).trim()
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
        }

        // Query 3: Duplicate assets wasting significant space
        if (hasView('view_potential_duplicates')) {
        def dupeQuery = """SELECT name, type, instances, total_size, size FROM view_potential_duplicates WHERE size > ${duplicateThresholdKB * 1024} ORDER BY total_size DESC LIMIT 10;"""
        def dupeOutput = bat(script: "@\"${sqlite3Path}\" -separator \"|\" \"${dbFile}\" \"${dupeQuery}\"", returnStdout: true).trim()
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
        }

        // Overall size breakdown by type (object_view is always present)
        if (!hasView('object_view')) {
            echo "[INFO] No object_view in DB — skipping breakdown"
        } else {
        def breakdownQuery = "SELECT type, count(*) as cnt, sum(size) as total FROM object_view GROUP BY type ORDER BY total DESC LIMIT 8;"
        def breakdownOutput = bat(script: "@\"${sqlite3Path}\" -separator \"|\" \"${dbFile}\" \"${breakdownQuery}\"", returnStdout: true).trim()
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
        }

        if (!env.BUILD_REPORT_WARNINGS) {
            echo "[OK] No asset size warnings found"
        }

    } catch (Exception e) {
        echo "[WARNING] Build report analysis failed: ${e.message}"
    }
}

// ============================================================================
// SCM FUNCTIONS
// ============================================================================

/**
 * Clean Plastic SCM workspace by undoing local changes and removing private files.
 * Call this BEFORE checkout to ensure a clean workspace state.
 *
 * @param workspacePath Path to the Plastic workspace (defaults to WORKSPACE/plastic)
 */
/**
 * Delete Windows reserved-device-name files (nul, con, aux, prn, com1-9, lpt1-9 — with or
 * without an extension) anywhere under the workspace. These are legal filenames on
 * macOS/Linux and can be committed to Plastic from those agents, but Windows can't
 * materialise them: `cm update --forced` fails with "Stream does not support seeking" and a
 * plain `del` can't remove them (the name resolves to the device). PowerShell with the
 * \\?\ extended-length path prefix can. Best-effort — never fails the build.
 *
 * Note: if the file is *controlled* in Plastic, cm will try to re-fetch it on the next
 * update (and warn again); the permanent fix is to remove it from the Plastic repo.
 */
def deleteReservedNameFiles(String wsPath) {
    if (!wsPath) return
    def psFile = "${env.WORKSPACE}\\_reserved_name_cleanup.ps1"
    // Single-quoted Groovy heredoc: no Groovy interpolation, and the \\?\ prefix is built
    // from char codes ([char]92='\', 63='?') so there are no backslashes to escape.
    writeFile file: psFile, text: '''param([string]$Ws)
if (-not (Test-Path -LiteralPath $Ws)) { return }
$prefix = ([char]92, [char]92, [char]63, [char]92) -join ''
$found = 0
Get-ChildItem -LiteralPath $Ws -Recurse -Force -ErrorAction SilentlyContinue |
  Where-Object { -not $_.PSIsContainer -and $_.BaseName -match '^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])$' } |
  ForEach-Object {
    $found++
    try {
      Remove-Item -LiteralPath ($prefix + $_.FullName) -Force -ErrorAction Stop
      Write-Host ('  Deleted reserved-name file: ' + $_.FullName)
    } catch {
      Write-Host ('  WARNING: could not delete ' + $_.FullName + ' : ' + $_.Exception.Message)
    }
  }
if ($found -eq 0) { Write-Host '  No reserved-name files found' }
'''
    try {
        echo "  Scanning for Windows reserved-name files (nul, con, aux, ...) under ${wsPath}..."
        bat(script: "@powershell -NoProfile -ExecutionPolicy Bypass -File \"${psFile}\" \"${wsPath}\"", returnStatus: true)
    } finally {
        bat(script: "@if exist \"${psFile}\" del /f /q \"${psFile}\" 2>nul & exit /b 0", returnStatus: true)
    }
}

/**
 * Clean Plastic SCM workspace by removing private (untracked) files.
 * Call this BEFORE checkout to ensure a clean workspace state.
 * The checkout itself will overwrite any modified tracked files, so we only
 * need to remove private files that the checkout won't touch.
 *
 * @param workspacePath Path to the Plastic workspace (defaults to WORKSPACE/plastic)
 */
def cleanPlasticWorkspace(String cleanCache = null, String workspacePath = null) {
    def wsPath = workspacePath ?: "${env.WORKSPACE}\\plastic"
    def cacheTypes = cleanCache?.split(',')?.collect { it.trim() } ?: []
    def doVerify = cacheTypes.contains('Verify Workspace')

    // Cloak reserved names so the cm calls below (undo / update --forced) skip them, then
    // remove any reserved-name file the normal `del` and cm can't handle.
    _writeCloakedConf(wsPath)
    deleteReservedNameFiles(wsPath)

    // Single consolidated bat call: check status, undo changes, remove private files, optional verify
    def result = bat(script: """@echo off
setlocal EnableDelayedExpansion
if not exist "${wsPath}\\.plastic" (
    echo   No Plastic workspace found at ${wsPath} >&2
    echo RESULT_STATUS=NO_WORKSPACE
    exit /b 0
)
echo Cleaning Plastic workspace: ${wsPath} >&2
cd /d "${wsPath}"
REM Count changed files
set "CHANGED=0"
for /f "delims=" %%f in ('cm status --changed --short 2^>nul') do set /a CHANGED+=1
echo   Changed files: !CHANGED! >&2
REM Count and list locally deleted files
set "DELETED=0"
for /f "delims=" %%f in ('cm status --localdeleted --short 2^>nul') do (
    set /a DELETED+=1
    echo   Locally deleted: %%f >&2
)
if !DELETED! GTR 0 echo   Locally deleted total: !DELETED! >&2
REM Undo changes if any
set "NEED_UNDO=0"
if !CHANGED! GTR 0 set "NEED_UNDO=1"
if !DELETED! GTR 0 set "NEED_UNDO=1"
if "!NEED_UNDO!"=="1" (
    echo   Running cm undo . -r ... >&2
    cm undo . -r
    if errorlevel 1 (
        echo RESULT_STATUS=UNDO_FAILED
        exit /b 0
    )
    echo   Reverted !CHANGED! changed + !DELETED! locally deleted file^(s^) >&2
) else (
    echo   No changes to undo >&2
)
REM Remove private files (files only, skip directories to avoid nuking tracked content)
set "PRIVATE=0"
for /f "delims=" %%f in ('cm status --private --short --cutignored 2^>nul') do (
    set /a PRIVATE+=1
    if /i "%%~nxf"=="cloaked.conf" (
        echo   Kept: cloaked.conf >&2
    ) else if exist "%%f\\*" (
        echo   Skipped dir: %%~nxf >&2
    ) else if exist "%%f" (
        del /f /q "%%f"
        echo   Deleted: %%~nxf >&2
    )
)
if !PRIVATE! GTR 0 (
    echo   Removed !PRIVATE! private items >&2
) else (
    echo   No private files >&2
)
${doVerify ? """REM Verify workspace file integrity. Best-effort: a reserved-name file (e.g. 'nul')
REM or any single bad file must NOT fail the build. cm output -> stderr, exit code ignored.
echo   Verifying workspace file integrity ^(cm update --forced^)... >&2
cm update --forced --silent 1>&2
if errorlevel 1 (
    echo   WARNING: cm update --forced reported issues ^(e.g. a reserved-name file like 'nul'^) - continuing >&2
) else (
    echo   Workspace file integrity verified >&2
)""" : ''}
echo Plastic workspace cleanup complete >&2
echo RESULT_STATUS=OK
echo RESULT_CHANGED=!CHANGED!
echo RESULT_DELETED=!DELETED!
echo RESULT_PRIVATE=!PRIVATE!
exit /b 0""", returnStdout: true).trim()

    echo "  plasticCleanup stdout: ${result}"
    // Parse by prefixed markers (RESULT_*) so output from cm update — which prints
    // "<file> unchecked out correctly" to stdout — can't shift positional fields.
    def lines = result.readLines().collect { it.trim() }.findAll { it }
    def field = { String key ->
        def line = lines.find { it.startsWith("${key}=") }
        return line ? line.substring(key.length() + 1) : null
    }
    def status = field('RESULT_STATUS')

    if (status == 'NO_WORKSPACE') {
        echo "[INFO] No Plastic workspace found at ${wsPath}, skipping cleanup"
        return
    }

    if (status == 'UNDO_FAILED') {
        error("[ERROR] Failed to undo workspace changes. Workspace may be corrupted - check Plastic SCM status on this agent.")
    }

    def changed = field('RESULT_CHANGED') ?: '0'
    def deleted = field('RESULT_DELETED') ?: '0'
    def privates = field('RESULT_PRIVATE') ?: '0'

    echo "[OK] Plastic workspace cleanup complete (changed: ${changed}, deleted: ${deleted}, private: ${privates})"
}

/**
 * Deregister a Plastic SCM workspace if it exists but points to a stale path
 * (e.g. an @2 directory from a previous customWorkspace collision).
 * This allows cm workspace create to succeed with the same name at the correct path.
 */
def _deregisterStalePlasticWorkspace(String wsName) {
    try {
        // Check if a workspace with this name exists
        def wsList = bat(script: "@cm workspace list --format={wkname}#{path}", returnStdout: true).trim()
        for (line in wsList.split('\n')) {
            line = line.trim()
            if (!line.contains('#')) continue
            def parts = line.split('#', 2)
            def name = parts[0].trim()
            if (name == wsName) {
                def path = parts[1].trim()
                echo "[Checkout] Found stale workspace '${wsName}' at ${path} — deregistering"
                bat(script: "@cm workspace delete \"${wsName}\"", returnStatus: true)
                break
            }
        }
    } catch (Exception e) {
        echo "[DEBUG] Could not check for stale workspace '${wsName}': ${e.message}"
    }
}

/**
 * Write (merge into) <workspace>/cloaked.conf so Plastic never tries to download items whose
 * leaf name is a Windows reserved device name: nul, con, prn, aux, com1-9, lpt1-9.
 *
 * Those names are legal on macOS/Linux and do get checked in from there (e.g.
 * Back4App/cloud_code/nul), but Windows cannot create them, so every checkout reports
 *   Error updating '.../cloud_code/nul': Stream does not support seeking.
 * and cm exits 1. Deleting the file locally does not help - it is a *controlled* item, so the
 * next switch/update fetches it again. Cloaked items are skipped by update/switch (cm update
 * --cloaked is the documented opt-in to include them), so cloaking removes the error at the
 * source instead of tolerating it.
 *
 * Extra job-specific rules: PLASTIC_CLOAK_PATHS env var, ";"-separated, workspace-relative
 * paths starting with "/" (e.g. "/UnityProj_MTD/Back4App/cloud_code/nul;/ArtSource").
 *
 * Best-effort: never fails the build. The permanent fix is deleting the offending file from
 * the Plastic repo (do it from a macOS agent, where the name is legal).
 */
private def _writeCloakedConf(String wsDir) {
    if (!wsDir) return
    def confPath = "${wsDir}\\cloaked.conf"
    try {
        // Leaf-name rules: match an item with this name in any directory.
        def rules = ['nul', 'con', 'prn', 'aux',
                     'com1', 'com2', 'com3', 'com4', 'com5', 'com6', 'com7', 'com8', 'com9',
                     'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5', 'lpt6', 'lpt7', 'lpt8', 'lpt9']
        def extraPaths = env.PLASTIC_CLOAK_PATHS ?: ''
        extraPaths.split(';').each { entry ->
            def rule = entry.trim()
            if (rule) rules << rule
        }
        def existing = fileExists(confPath) ? readFile(file: confPath).readLines() : []
        def have = existing.collect { it.trim().toLowerCase() }
        def missing = rules.findAll { !have.contains(it.toLowerCase()) }
        if (!missing) return
        def marker = '# Windows reserved device names - managed by tool_jenkins_build_system'
        def lines = []
        lines.addAll(existing)
        if (!have.contains(marker.toLowerCase())) lines << marker
        lines.addAll(missing)
        writeFile file: confPath, text: lines.join('\r\n') + '\r\n'
        echo "  Cloaked ${missing.size()} rule(s) in cloaked.conf: ${missing.join(', ')}"
    } catch (Exception e) {
        echo "[DEBUG] Could not write ${confPath}: ${e.message}"
    }
}

/**
 * Scan a workspace directory for Windows reserved filenames (nul, con, prn, aux, com1-9, lpt1-9)
 * and delete them using Win32 API via the \\?\ prefix which bypasses Windows name restrictions.
 * These files can end up in the workspace when checked in from macOS/Linux.
 * Plastic SCM errors on update when it encounters them but continues, so this is preventative cleanup.
 * Uses kernel32 FindFirstFileW/DeleteFileW since .NET and cmd.exe cannot even see reserved names.
 */
private def _removeReservedFilenames(String wsDir) {
    try {
        def psScript = libraryResource('scripts/clean_reserved_filenames.ps1')
        writeFile file: 'clean_reserved.ps1', text: psScript
        bat script: "@powershell -NoProfile -ExecutionPolicy Bypass -File clean_reserved.ps1 \"${wsDir}\"", returnStatus: true
    } catch (Exception e) {
        echo "[DEBUG] Reserved filename cleanup failed: ${e.message}"
    }
}

/**
 * List repository items whose leaf name is a Windows reserved device name.
 *
 * Uses `cm ls --tree` (verified in `cm help ls`), which lists the *server* tree of a
 * changeset - so it finds these items even though Windows can never materialise them in the
 * workspace, and even when they are cloaked. Filtered by findstr on the agent so only the
 * offending paths (not the whole tree) come back to the controller.
 *
 * @return List of server paths, e.g. ['/UnityProj_MTD/Back4App/cloud_code/nul']
 */
def findReservedNamesInRepo(String wsDir, String csId, String repSpec) {
    def names = ['nul', 'con', 'prn', 'aux',
                 'com1', 'com2', 'com3', 'com4', 'com5', 'com6', 'com7', 'com8', 'com9',
                 'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5', 'lpt6', 'lpt7', 'lpt8', 'lpt9']
    // /e matches at end of line, so "/nul" only matches a leaf named nul (not nullable.cs)
    def patterns = names.collect { "/c:\"/${it}\"" }.join(' ')
    def out = bat(
        script: "@cd /d \"${wsDir}\" && (cm ls \"/\" \"--tree=${csId}@${repSpec}\" -R --format={path} 2>nul | findstr /i /e ${patterns}) & exit /b 0",
        returnStdout: true
    )
    return out.readLines().collect { it.trim() }.findAll { it.startsWith('/') }
}

/**
 * Report repo items Windows can never check out, with the exact commands to delete them.
 *
 * Cloaking (see _writeCloakedConf) stops the checkout error, but the item stays in the repo
 * and every Windows workspace keeps skipping it - so surface it once per build with a fix.
 * Set SCAN_RESERVED_NAMES=false on the job to skip the scan.
 */
private def _reportReservedRepoItems(String wsDir, String csId, String repSpec) {
    if (env.SCAN_RESERVED_NAMES?.toLowerCase() == 'false') return
    def items = []
    try {
        echo "[Checkout] Scanning the repo tree for Windows-reserved names (set SCAN_RESERVED_NAMES=false to skip)"
        items = findReservedNamesInRepo(wsDir, csId, repSpec)
    } catch (Exception e) {
        echo "[DEBUG] Reserved-name repo scan failed: ${e.message}"
        return
    }
    if (!items) return
    env.PLASTIC_RESERVED_ITEMS = items.join(';')
    def relPath = items[0].replaceFirst('^/', '')
    def lines = []
    lines << '=============================================================================='
    lines << "[RESERVED] ${items.size()} repo item(s) have a Windows reserved device name (nul/con/aux/...)."
    lines << "[RESERVED] Windows cannot create these, so cm reports \"Stream does not support seeking\""
    lines << "[RESERVED] on every checkout. They are cloaked, so this build is fine - but the only"
    lines << "[RESERVED] permanent fix is deleting them from the repo:"
    lines << '[RESERVED]'
    items.each { lines << "[RESERVED]   ${it}" }
    lines << '[RESERVED]'
    lines << "[RESERVED] This CANNOT be done from Windows (verified): .NET normalises any path"
    lines << "[RESERVED] ending in a device name to the device itself, so cm answers 'not in a"
    lines << "[RESERVED] workspace'. And because the item never materialises, it is not in the"
    lines << "[RESERVED] workspace tree either - nothing to cm remove, nothing locally deleted."
    lines << "[RESERVED] Do it on a macOS/Linux client - the iOS build agents have cm installed:"
    lines << "[RESERVED]   cd <a workspace on this branch>"
    lines << "[RESERVED]   cm remove \"${relPath}\""
    lines << "[RESERVED]   cm checkin \"${relPath}\" -c \"Remove Windows-reserved filename\""
    lines << "[RESERVED] Set SCAN_RESERVED_NAMES=false to skip this scan."
    lines << '=============================================================================='
    echo lines.join('\n')
}

/**
 * Checkout from Plastic SCM using cm switch instead of the Jenkins plugin checkout.
 * This avoids the plugin trying to delete/recreate the workspace directory, which
 * fails on Windows when file locks exist (antivirus, leftover processes, etc.).
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
    def wsDir = "${env.WORKSPACE}\\plastic"

    // 1. Ensure Plastic workspace exists and is registered
    def hasWorkspace = bat(script: "@if exist \"${wsDir}\\.plastic\" (cd /d \"${wsDir}\" && cm status >nul 2>&1 && echo true || echo false) else (echo false)", returnStdout: true).trim()

    if (hasWorkspace != 'true') {
        bat "@if not exist \"${wsDir}\" mkdir \"${wsDir}\""
        // cm workspace create downloads content, so cloak reserved names before it runs
        _writeCloakedConf(wsDir)
        def safeName = env.JOB_NAME.replaceAll('[^a-zA-Z0-9_-]', '_')
        def wsName = "ci_${env.NODE_NAME}_${safeName}"

        // Deregister any stale workspace with the same name (e.g. pointing to an old @2 path)
        _deregisterStalePlasticWorkspace(wsName)

        def createResult = bat(script: "@cm workspace create \"${wsName}\" \"${wsDir}\" \"${repSpec}\"", returnStatus: true)
        if (createResult != 0) {
            // .plastic may exist but be unregistered — wipe it and retry with same name
            echo "[Checkout] workspace create failed (orphaned .plastic?) — removing and retrying"
            bat "@if exist \"${wsDir}\\.plastic\" rmdir /s /q \"${wsDir}\\.plastic\""
            createResult = bat(script: "@cm workspace create \"${wsName}\" \"${wsDir}\" \"${repSpec}\"", returnStatus: true)
        }
        if (createResult != 0) {
            // Name may already be registered from a previous agent - retry with executor suffix
            wsName = "${wsName}_${env.EXECUTOR_NUMBER ?: '0'}"
            _deregisterStalePlasticWorkspace(wsName)
            def retryResult = bat(script: "@cm workspace create \"${wsName}\" \"${wsDir}\" \"${repSpec}\"", returnStatus: true)
            if (retryResult != 0) {
                // Log diagnostics before failing
                echo "[ERROR] Failed to create Plastic workspace '${wsName}' at ${wsDir}"
                echo "[DEBUG] repSpec: ${repSpec}"
                bat(script: "@cm version", returnStatus: true)
                bat(script: "@cm whoami", returnStatus: true)
                bat(script: "@cm workspace list", returnStatus: true)
                error("[ERROR] cm workspace create failed (exit code ${retryResult}). Check that Plastic SCM is authenticated on this agent (${env.NODE_NAME}). Run 'cm login' or configure via Plastic GUI.")
            }
        }
    }

    // 2. Cloak Windows reserved names so cm stops trying to download them, then delete any
    //    that already made it into the workspace
    _writeCloakedConf(wsDir)
    _removeReservedFilenames(wsDir)

    // 3. Undo any pending changes left from a previous build (prevents switch failure)
    bat "@cd /d \"${wsDir}\" && cm undo . -r"

    // 4. Switch to desired changeset or branch
    //    cm switch may return non-zero if it encounters files with Windows reserved names
    //    (nul, con, prn, etc.) — these are harmless 0-byte sync errors, not real failures.
    //    We capture the exit code and verify the switch succeeded via cm status instead.
    def switchResult
    if (changeset) {
        echo "[Checkout] Switching to changeset ${changeset}"
        switchResult = bat(script: "@cd /d \"${wsDir}\" && cm switch cs:${changeset} --noinput", returnStatus: true)
    } else if (branch) {
        echo "[Checkout] Switching to branch ${branch}"
        switchResult = bat(script: "@cd /d \"${wsDir}\" && cm switch \"br:${branch}\" --noinput", returnStatus: true)
    } else {
        error "[Checkout] Either 'branch' or 'changeset' must be specified"
    }
    if (switchResult != 0) {
        echo "[WARN] cm switch exited with code ${switchResult}"
        echo "[WARN] If the log shows: Error updating '<path>': Stream does not support seeking - that path is a Windows reserved device name (nul/con/aux/...). Those are cloaked by leaf name in cloaked.conf; if one keeps coming back, add its exact workspace-relative path to the PLASTIC_CLOAK_PATHS job env var, or delete it from the Plastic repo (from a macOS agent, where the name is legal)."
    }

    // 5. Get loaded changeset ID from workspace status
    //    cm status --header --machinereadable returns: STATUS <csId> <repo> <server>
    def statusOutput = bat(
        script: "@cd /d \"${wsDir}\" && cm status --header --machinereadable",
        returnStdout: true
    ).trim()
    def statusParts = statusOutput.split(/\s+/)
    def csId = statusParts.length > 1 ? statusParts[1] : null
    if (!csId) error "[Checkout] Could not determine loaded changeset from: ${statusOutput}"

    // 6. Query changeset details (branch, author, GUID)
    def csInfo = bat(
        script: """@cm find changeset "where changesetid=${csId}" --format="{changesetid}#{branch}#{owner}#{guid}" --nototal on repository "'${repSpec}'" """,
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

    // 7. Report repo items Windows can never check out (cloaked above), with the fix commands
    _reportReservedRepoItems(wsDir, csId, repSpec)

    return result
}

/**
 * Resolve branch and latest changeset from user input BEFORE checkout.
 * Uses cm find against the repo server — no workspace needed.
 * @param branch Branch name (e.g. /main) — queries for latest changeset
 * @param changeset Specific changeset ID — queries for its branch
 * @return Map with 'branch' and 'changeset' (both may be null on failure)
 */
def resolveTargetChangeset(String branch, String changeset) {
    def repo = env.PLASTIC_REPSPEC
    if (!repo) return [branch: branch, changeset: changeset]
    try {
        if (changeset) {
            // User specified a changeset — look up its branch
            def info = bat(
                script: """@cm find changeset "where changesetid=${changeset}" --format="{branch}#{changesetid}" --nototal on repository "'${repo}'" """,
                returnStdout: true
            ).trim()
            def parts = info.split('#')
            return [branch: parts[0]?.trim() ?: branch, changeset: parts.length > 1 ? parts[1]?.trim() : changeset]
        } else if (branch) {
            // User specified a branch — find the latest changeset on it
            def info = bat(
                script: """@cm find changeset "where branch='${branch}' order by changesetid desc limit 1" --format="{branch}#{changesetid}" --nototal on repository "'${repo}'" """,
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

/**
 * Get branch name from a changeset ID using cm find
 */
def getBranchFromChangeset(String changeset, String workspacePath = null) {
    try {
        def repo = workspacePath ?: env.PLASTIC_REPSPEC
        def branch = bat(
            script: """@cm find changeset "where changesetid=${changeset}" --format="{branch}" --nototal on repository "'${repo}'" """,
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
        def historyOutput = bat(
            script: """@cd /d "%WORKSPACE%\\plastic" && cm find changesets "${whereClause} order by changesetid desc limit ${count}" --format="{changesetid}|{owner}|{comment}" --nototal""",
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
// AAB → APK CONVERSION
// ============================================================================

/**
 * Convert an Android App Bundle (.aab) to a universal APK using bundletool.
 * Downloads bundletool on first use. Requires Java on the agent.
 *
 * @param config Map with keys:
 *   - buildPath: Directory containing the .aab file
 *   - keystorePath: Absolute path to keystore file (optional - uses debug signing if omitted)
 *   - keystorePassVar: Name of env var holding the keystore password (e.g. 'ANDROID_KEYSTORE_PASS')
 *   - keyAlias: Key alias name
 *   - keyAliasPassVar: Name of env var holding the key alias password (e.g. 'ANDROID_KEYALIAS_PASS')
 */
def convertAabToApk(Map config) {
    def buildPath = config.buildPath
    def keystorePath = config.keystorePath
    def keystorePassVar = config.keystorePassVar  // env var name (e.g. 'ANDROID_KEYSTORE_PASS'), not the value
    def keyAlias = config.keyAlias
    def keyAliasPassVar = config.keyAliasPassVar  // env var name (e.g. 'ANDROID_KEYALIAS_PASS'), not the value

    // Find AAB file
    def aabFile = bat(
        script: """@echo off
            cd /d "${buildPath}"
            for %%f in (*.aab) do (echo %%f& goto :eof)""",
        returnStdout: true
    ).trim()
    if (!aabFile) {
        echo "[WARN] No AAB file found in ${buildPath}, skipping APK conversion"
        return
    }

    // Ensure bundletool is installed
    def bundletoolPath = ensureBundletool()

    def aabPath = "${buildPath}\\${aabFile}"
    def apksPath = "${buildPath}\\temp_universal.apks"
    def apkName = aabFile.replace('.aab', '.apk')
    def apkPath = "${buildPath}\\${apkName}"

    echo "[INFO] Converting ${aabFile} → ${apkName}"

    // Ensure Java is available (bundletool requires it)
    def javaResult = checkJava()
    if (!javaResult.available) {
        error "[ERROR] Java not found - bundletool requires Java. Install Unity with Android Build Support module (includes OpenJDK)."
    }
    def javaExe = env.JAVA_HOME ? "\"${env.JAVA_HOME}\\bin\\java.exe\"" : 'java'

    // Build signing args if keystore is provided.
    // Uses %VAR% env var references to avoid Groovy string interpolation of secret values.
    def signingArgs = ''
    if (keystorePath && keystorePassVar && keyAlias && keyAliasPassVar) {
        signingArgs = "--ks=\"${keystorePath}\" --ks-pass=pass:\"%${keystorePassVar}%\" --ks-key-alias=${keyAlias} --key-pass=pass:\"%${keyAliasPassVar}%\""
    }

    bat """
        @echo off
        ${javaExe} -jar "${bundletoolPath}" build-apks ^
            --bundle="${aabPath}" ^
            --output="${apksPath}" ^
            --mode=universal ^
            ${signingArgs} ^
            --overwrite
        if errorlevel 1 (
            echo [ERROR] bundletool conversion failed
            exit /b 1
        )

        REM Extract universal.apk from the .apks zip (rename to .zip - Expand-Archive doesn't recognize .apks)
        copy /Y "${apksPath}" "${apksPath}.zip" >nul
        powershell -Command "Expand-Archive -Path '${apksPath}.zip' -DestinationPath '${buildPath}\\temp_apks' -Force"
        del /f /q "${apksPath}.zip"
        if exist "${buildPath}\\temp_apks\\universal.apk" (
            move /Y "${buildPath}\\temp_apks\\universal.apk" "${apkPath}" >nul
        ) else (
            echo [ERROR] universal.apk not found in bundletool output
            exit /b 1
        )

        REM Cleanup
        del /f /q "${apksPath}"
        rd /s /q "${buildPath}\\temp_apks"
        echo [OK] Created ${apkName}
    """

    echo "[OK] APK created: ${apkName}"
}

/**
 * Ensure bundletool JAR is available, download if needed.
 * @return Path to bundletool JAR
 */
def ensureBundletool() {
    def toolsDir = getToolsDir()
    def bundletoolDir = "${toolsDir}\\bundletool"
    def bundletoolJar = "${bundletoolDir}\\bundletool.jar"

    def exists = bat(script: "@if exist \"${bundletoolJar}\" echo found", returnStdout: true).trim()
    if (exists == 'found') {
        return bundletoolJar
    }

    echo "[INFO] Installing bundletool..."
    bat """
        @echo off
        if not exist "${bundletoolDir}" mkdir "${bundletoolDir}"

        REM Download latest bundletool release from GitHub
        powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \$ProgressPreference = 'SilentlyContinue'; \$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/google/bundletool/releases/latest'; \$asset = \$release.assets | Where-Object { \$_.name -match 'bundletool-all.*\\.jar\$' } | Select-Object -First 1; Invoke-WebRequest -Uri \$asset.browser_download_url -OutFile '${bundletoolJar}'; echo Downloaded: \$asset.name"
        if errorlevel 1 (
            echo [ERROR] Failed to download bundletool
            exit /b 1
        )
        echo [OK] bundletool installed
    """

    return bundletoolJar
}

// ============================================================================
// UPLOAD FUNCTIONS
// ============================================================================

/**
 * Run `rclone link`, retrying once after 5s if Google Drive hasn't indexed the upload yet.
 * Uses a temp file instead of for/f echo so & in the URL isn't parsed as a command separator.
 */
private def _rcloneLink(String rclonePath, String remotePath) {
    def extractLink = { String raw ->
        if (!raw) return ''
        // Find the HTTP URL line (rclone may also output stats/warnings)
        def httpLine = raw.split('\r?\n').find { it.trim().startsWith('http') }?.trim()
        return httpLine ?: ''
    }
    def runLink = {
        bat(script: """@echo off
set LINK_TMP=%TEMP%\\rclone_link_%RANDOM%.txt
"${rclonePath}" --config "%RCLONE_CONFIG%" link "%RCLONE_REMOTE%/${remotePath}" > "%LINK_TMP%" 2>&1
type "%LINK_TMP%"
del "%LINK_TMP%"
exit /b 0""", returnStdout: true).trim()
    }
    def raw = runLink()
    def link = extractLink(raw)
    if (!link) {
        if (raw) echo "[WARN] rclone link output (no URL found):\n${raw.take(500)}"
        echo "[INFO] rclone link retry in 5s for: ${remotePath}"
        sleep(5)
        raw = runLink()
        link = extractLink(raw)
        if (!link) echo "[WARN] rclone link retry failed for: ${remotePath}\nOutput: ${raw.take(500)}"
    }
    return link
}

/**
 * Create the destination folder on the remote and return its share link. Called BEFORE the upload
 * so that afterwards only the file link stands between rclone exiting and the Slack update.
 */
private def _rcloneFolderLink(String rclonePath, String destFolder) {
    bat(script: """@echo off
"${rclonePath}" --config "%RCLONE_CONFIG%" mkdir "%RCLONE_REMOTE%/${destFolder}" 2>&1
exit /b 0""", returnStatus: true)
    def link = _rcloneLink(rclonePath, destFolder)
    if (link) {
        echo "[OK] GDrive folder link: ${link}"
    } else {
        echo "[WARN] No GDrive folder link generated - sidebar folder link will be missing"
    }
    env.GDRIVE_FOLDER_LINK = link ?: ''
    return link
}

def uploadToGoogleDrive(Map config) {
    def buildPath = config.buildPath
    def destFolder = config.destFolder
    def buildType = config.buildType

    // Ensure rclone is available
    def rclonePath = env.RCLONE_PATH
    if (!rclonePath) {
        def rcloneCheck = checkRclone(true)
        rclonePath = rcloneCheck.path ?: error("[ERROR] rclone not available")
    }

    def gdriveFolderLink = _rcloneFolderLink(rclonePath, destFolder)

    // rclone re-sends the WHOLE file on every low-level retry (operations.Copy), not just the failed
    // chunk: the defaults (3 passes x 10) turned one ~630 MiB IPA into 19 GiB of uploads against a
    // quota that retrying cannot free. 3 x 3 with a minute between passes still covers a real
    // transient and lets a per-minute quota window reset. Errors go to the log file (--progress
    // keeps the console live); on failure failRcloneUpload() prints and explains them.
    def rcloneLog = "${env.ARTIFACT_PATH ?: env.WORKSPACE}\\rclone_upload.log"  // under artifacts/ so post{always} archives it
    def status = bat(returnStatus: true, script: """
        @echo off
        set DEST_PATH=%RCLONE_REMOTE%/${destFolder}
        echo Uploading to: %DEST_PATH%
        for %%d in ("${rcloneLog}") do if not exist "%%~dpd" mkdir "%%~dpd"
        if exist "${rcloneLog}" del /q "${rcloneLog}"

        cd /d "${buildPath}"
        for %%f in (*.apk *.aab *.ipa *.nsp) do (
            set FILE=%%f
            goto :found
        )
        :found

        if not defined FILE (
            REM Switch builds produce .nspd directories, not single .nsp files
            set NSPD_FOUND=
            for /d %%d in (*.nspd) do set NSPD_FOUND=1
            if defined NSPD_FOUND (
                echo [INFO] No single build file found, but .nspd directory detected
                goto :skip_file_upload
            )
            echo [ERROR] No build file found in output directory: ${buildPath}
            echo [ERROR] Expected: *.apk, *.aab, *.ipa, *.nsp, or *.nspd directory
            echo [ERROR] Directory contents:
            dir /s /b "${buildPath}" || echo [ERROR] Directory does not exist or is empty
            exit /b 3
        )

        echo Uploading: %FILE%
        "${rclonePath}" copy "%FILE%" "%DEST_PATH%/" ^
            --config "%RCLONE_CONFIG%" ^
            --progress ^
            --transfers=46 ^
            --buffer-size=256M ^
            --drive-chunk-size=256M ^
            --drive-upload-cutoff=256M ^
            --use-mmap ^
            --retries=3 ^
            --retries-sleep=60s ^
            --low-level-retries=3 ^
            --stats=10s ^
            --stats-one-line ^
            --log-file "${rcloneLog}" ^
            -v
        if errorlevel 1 exit /b 1

        :skip_file_upload

        echo [INFO] Upload complete
    """)
    if (status == 3) error("No build file found in output directory: ${buildPath}")
    if (status != 0) common.failRcloneUpload(rcloneLog)

    // Multi-line bat script to get first matching build file - goto :eof doesn't work in single-line for loops
    // Order must match the rclone upload loop above (*.apk first)
    def fileName = bat(script: """@echo off
cd /d "${buildPath}"
for %%f in (*.apk *.aab *.ipa *.nsp) do (
    echo %%f
    goto :eof
)""", returnStdout: true).trim()
    def rcloneLink = { String remotePath -> _rcloneLink(rclonePath, remotePath) }

    def fileLink = ""
    if (fileName) {
        echo "[INFO] Generating GDrive link for: ${destFolder}/${fileName}"
        fileLink = rcloneLink("${destFolder}/${fileName}")
        // Capture human-readable file size for Slack
        def sizeBytes = bat(script: "@for %%A in (\"${buildPath}\\${fileName}\") do @echo %%~zA", returnStdout: true).trim()
        if (sizeBytes?.isNumber()) {
            env.ARTIFACT_SIZE = common.formatFileSize(sizeBytes as Long)
            echo "[INFO] Artifact size: ${env.ARTIFACT_SIZE}"
        }
    }
    if (fileLink) {
        echo "[OK] GDrive file link: ${fileLink}"
        def badgeText = fileName.substring(fileName.lastIndexOf('.') + 1)
        common.addDriveArtifactBadge(badgeText, fileLink)
        env.GDRIVE_FILE_LINK = fileLink
    } else {
        echo "[WARN] No GDrive file link generated - sidebar download link will be missing"
    }

    // Switch builds may only have .nspd directories (no single .nsp file)
    if (!fileName && env.PLATFORM == 'Switch' && gdriveFolderLink) {
        common.addDriveArtifactBadge('nspd', gdriveFolderLink)
    }

    // Tell Slack now - the message only needs the links, and both are in env at this point.
    common.updateUploadStatus('gdrive', 'done')

    def fileType = fileName ? fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase() : 'APK'
    def fileIcon = null
    if (fileType == 'NSP') {
        fileIcon = 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/nintendo-switch.png'
    }

    common.addGoogleDriveLinks(gdriveFolderLink, fileLink, fileType, fileIcon)

    return [folderLink: gdriveFolderLink, fileLink: fileLink, fileName: fileName]
}

/**
 * Upload an entire build output directory to Google Drive.
 *
 * Console builds (Xbox GameCore, PS5) ship a folder layout, not a single installable file, so
 * uploadToGoogleDrive()'s "find the one artifact and upload it" path doesn't apply. This copies
 * the whole tree and links the folder; if a submission package is sitting at the top level
 * (.xvc/.msixvc for Xbox, .pkg for PS5) it gets a direct download link too.
 */
def uploadFolderToGoogleDrive(Map config) {
    def buildPath = config.buildPath
    def destFolder = config.destFolder
    def packageGlobs = config.packageGlobs ?: '*.xvc *.msixvc *.pkg'

    def rclonePath = env.RCLONE_PATH
    if (!rclonePath) {
        def rcloneCheck = checkRclone(true)
        rclonePath = rcloneCheck.path ?: error("[ERROR] rclone not available")
    }

    def folderLink = _rcloneFolderLink(rclonePath, destFolder)

    // Same retry cap and log file as uploadToGoogleDrive() - see the comment there.
    def rcloneLog = "${env.ARTIFACT_PATH ?: env.WORKSPACE}\\rclone_upload.log"  // under artifacts/ so post{always} archives it
    def status = bat(returnStatus: true, script: """
        @echo off
        if not exist "${buildPath}" (
            echo [ERROR] Build output directory does not exist: ${buildPath}
            exit /b 3
        )

        set "HAS_CONTENT="
        for /f %%i in ('dir /b "${buildPath}" 2^>nul') do set "HAS_CONTENT=1"
        if not defined HAS_CONTENT (
            echo [ERROR] Build output directory is empty: ${buildPath}
            exit /b 3
        )
        for %%d in ("${rcloneLog}") do if not exist "%%~dpd" mkdir "%%~dpd"
        if exist "${rcloneLog}" del /q "${rcloneLog}"

        echo Uploading directory to: %RCLONE_REMOTE%/${destFolder}
        "${rclonePath}" copy "${buildPath}" "%RCLONE_REMOTE%/${destFolder}/" ^
            --config "%RCLONE_CONFIG%" ^
            --progress ^
            --transfers=46 ^
            --buffer-size=256M ^
            --drive-chunk-size=256M ^
            --drive-upload-cutoff=256M ^
            --use-mmap ^
            --retries=3 ^
            --retries-sleep=60s ^
            --low-level-retries=3 ^
            --stats=10s ^
            --stats-one-line ^
            --log-file "${rcloneLog}" ^
            -v
        if errorlevel 1 exit /b 1

        echo [INFO] Upload complete
    """)
    if (status == 3) error("Build output directory missing or empty: ${buildPath}")
    if (status != 0) common.failRcloneUpload(rcloneLog)

    // Look for a submission package at the top level (goto :eof so only the first match is used)
    def fileName = bat(script: """@echo off
cd /d "${buildPath}"
for %%f in (${packageGlobs}) do (
    echo %%f
    goto :eof
)""", returnStdout: true).trim()

    def fileLink = ''
    if (fileName) {
        echo "[INFO] Generating GDrive link for package: ${destFolder}/${fileName}"
        fileLink = _rcloneLink(rclonePath, "${destFolder}/${fileName}")
        def sizeBytes = bat(script: "@for %%A in (\"${buildPath}\\${fileName}\") do @echo %%~zA", returnStdout: true).trim()
        if (sizeBytes?.isNumber()) {
            env.ARTIFACT_SIZE = common.formatFileSize(sizeBytes as Long)
            echo "[INFO] Package size: ${env.ARTIFACT_SIZE}"
        }
    }

    def fileType = 'Folder'
    if (fileLink) {
        fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase()
        echo "[OK] GDrive package link: ${fileLink}"
        common.addDriveArtifactBadge(fileType.toLowerCase(), fileLink)
        env.GDRIVE_FILE_LINK = fileLink
    } else if (folderLink) {
        common.addDriveArtifactBadge('build', folderLink)
    }

    // Tell Slack now - the message only needs the links, and both are in env at this point.
    common.updateUploadStatus('gdrive', 'done')

    common.addGoogleDriveLinks(folderLink, fileLink, fileType, null)

    return [folderLink: folderLink, fileLink: fileLink, fileName: fileName]
}

def uploadToLocalShare(Map config) {
    def buildPath = config.buildPath
    def buildType = config.buildType ?: 'Release'

    def sharePath = env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds'
    def destPath = "${sharePath}\\${env.JOB_NAME}\\${buildType}\\${env.VERSION}"

    // Set path and sidebar link early so they're available during upload
    env.LOCAL_BUILD_PATH = destPath
    def fileUrl = common.localShareUrl(destPath)
    common.addSidebarLink(fileUrl, 'Local Build', 'https://img.icons8.com/fluency/48/folder-invoices--v1.png')

    echo "[INFO] Copying build to local share: ${destPath}"

    // Use robocopy for local/UNC copies -- rclone mangles UNC paths with \\?\UNC\ prefix
    // Robocopy: /MT:8 = 8 threads, /E = recurse, /NFL /NDL = no file/dir listing (quiet)
    // Exit codes: 0-7 = success, 8+ = error
    bat """
        @echo off
        setlocal EnableDelayedExpansion
        net use "${sharePath}" /user:BUILD build /persistent:no 2>nul
        if errorlevel 1 echo [WARNING] net use failed -- share may already be connected
        if not exist "${destPath}" mkdir "${destPath}"
        cd /d "${buildPath}"

        REM Check if specific artifact files exist (apk/aab/ipa/nsp/nspd)
        set FOUND=
        for %%f in (*.apk *.aab *.ipa *.nsp) do set FOUND=1
        for /d %%d in (*.nspd) do set FOUND=1

        if "!FOUND!"=="" (
            REM No single artifact -- copy entire build directory (Steam/standalone)
            echo [INFO] Copying entire build directory
            robocopy "%CD%" "${destPath}" /E /MT:8 /R:3 /W:5 /NJH /NJS /NFL /NDL
            if !ERRORLEVEL! GEQ 8 echo [ERROR] Failed to copy build directory
        ) else (
            REM Copy all artifact files + symbols in one pass each
            echo [INFO] Copying build artifacts
            robocopy "%CD%" "${destPath}" *.apk *.aab *.ipa *.nsp *.symbols.zip /R:3 /W:5 /NJH /NJS /NFL /NDL
            if !ERRORLEVEL! GEQ 8 echo [WARNING] Some artifact files failed to copy

            REM Copy directories in parallel: nspd, IL2CPP symbols, Burst debug
            for /d %%d in (*.nspd *_BackUpThisFolder_ButDontShipItWithYourGame *_BurstDebugInformation_DoNotShip) do (
                echo [INFO] Copying: %%d
                start /b robocopy "%CD%\\%%d" "${destPath}\\%%d" /E /MT:8 /R:3 /W:5 /NJH /NJS /NFL /NDL
            )
            REM Wait for background robocopy processes to finish
            :waitloop
            tasklist /fi "imagename eq robocopy.exe" 2>nul | find /i "robocopy" >nul
            if not errorlevel 1 (
                timeout /t 1 /nobreak >nul
                goto waitloop
            )
        )
        echo [INFO] Local share copy complete
        exit /b 0
    """

    // Orange artifact badge + "Download <TYPE> (Local)" link to the copy that just landed, so the
    // build is grabbable while the Drive upload (which turns the badge green) is still running.
    // Same first-match order as uploadToGoogleDrive() / uploadFolderToGoogleDrive(), so the badge
    // ids line up; a Switch .nspd folder or a loose folder (Steam) links the folder itself.
    def fileName = bat(script: """@echo off
cd /d "${buildPath}"
for %%f in (*.apk *.aab *.ipa *.nsp *.xvc *.msixvc *.pkg) do (
    echo %%f
    goto :eof
)
for /d %%d in (*.nspd) do (
    echo %%d
    goto :eof
)""", returnStdout: true).trim()
    common.addLocalArtifactLinks(destPath, fileName ?: null)

    common.updateUploadStatus('local', 'done')
}

// Fire-and-forget cleanup. Writes a .ps1 to the workspace and launches it
// detached so the build doesn't wait. Output goes to a log file under the
// share root so it can be inspected after the fact.
def cleanupLocalShare(String sharePath = null) {
    sharePath = sharePath ?: env.LOCAL_SHARE_PATH ?: '\\\\odd-jenkins\\builds'
    def maxBytes = 1099511627776L // 1 TB
    def logPath = "${sharePath}\\_cleanup.log"
    def scriptPath = "${env.WORKSPACE}\\_cleanup_local_share.ps1"

    def psScript = """
\$ErrorActionPreference = 'SilentlyContinue'
\$maxBytes = ${maxBytes}
\$sharePath = '${sharePath}'
\$logPath = '${logPath}'
function Log(\$msg) {
    \$ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -Path \$logPath -Value "[\$ts] \$msg"
}
Log "[INFO] Checking local share usage: \$sharePath"
\$size = (Get-ChildItem -Path \$sharePath -Recurse -File | Measure-Object -Property Length -Sum).Sum
if (\$size -eq \$null) { \$size = 0 }
\$gb = '{0:N1}' -f (\$size / 1073741824)
Log "[INFO] Usage: \$gb GB / 1024 GB"
if (\$size -le \$maxBytes) { Log '[INFO] Within limits, no cleanup needed'; return }
Log '[INFO] Share exceeds 1 TB, cleaning up oldest builds'
\$folders = Get-ChildItem -Path \$sharePath -Directory -Recurse -Depth 2 |
    Where-Object { \$_.GetFiles().Count -gt 0 -or \$_.GetDirectories().Count -gt 0 } |
    Sort-Object LastWriteTime
foreach (\$folder in \$folders) {
    \$cur = (Get-ChildItem -Path \$sharePath -Recurse -File | Measure-Object -Property Length -Sum).Sum
    if (\$cur -le \$maxBytes) { Log '[INFO] Share is now within limits'; break }
    Log "[INFO] Deleting: \$(\$folder.FullName)"
    Remove-Item -Path \$folder.FullName -Recurse -Force
}
""".stripIndent()

    try {
        writeFile file: '_cleanup_local_share.ps1', text: psScript
        echo "[INFO] Spawning detached local share cleanup (log: ${logPath})"
        bat """
            @echo off
            net use "${sharePath}" /user:BUILD build /persistent:no 2>nul
            powershell -NoProfile -Command "Start-Process powershell -ArgumentList '-NoProfile','-WindowStyle','Hidden','-File','${scriptPath}' -WindowStyle Hidden"
        """
    } catch (Exception e) {
        echo "[WARNING] Could not spawn local share cleanup: ${e.message}"
    }
}

def uploadToGCS(Map config) {
    def buildPath = config.buildPath
    def bundleIdentifier = config.bundleIdentifier
    def gcsBucket = config.gcsBucket ?: 'oddgames-builds'

    def rubyScript = '''
require "json"
require "net/http"
require "uri"
require "openssl"
require "base64"
require "time"

CHUNK_SIZE = 50 * 1024 * 1024

def log(level, msg)
  puts "[#{Time.now.strftime("%H:%M:%S")}] [#{level}] #{msg}"
  $stdout.flush
end

def get_access_token(json_key_path, scope)
  key_data = JSON.parse(File.read(json_key_path))
  now = Time.now.to_i
  header = { alg: "RS256", typ: "JWT" }
  claims = { iss: key_data["client_email"], scope: scope, aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }
  segments = [header, claims].map { |h| Base64.urlsafe_encode64(JSON.generate(h), padding: false) }
  signing_input = segments.join(".")
  key = OpenSSL::PKey::RSA.new(key_data["private_key"])
  signature = key.sign(OpenSSL::Digest::SHA256.new, signing_input)
  jwt = "#{signing_input}.#{Base64.urlsafe_encode64(signature, padding: false)}"
  uri = URI("https://oauth2.googleapis.com/token")
  res = Net::HTTP.post_form(uri, grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt)
  JSON.parse(res.body)["access_token"]
end

def gcs_object_exists?(token, bucket, object_name)
  uri = URI("https://storage.googleapis.com/storage/v1/b/#{bucket}/o/#{URI.encode_www_form_component(object_name)}")
  req = Net::HTTP::Get.new(uri)
  req["Authorization"] = "Bearer #{token}"
  http = Net::HTTP.new(uri.host, uri.port)
  http.use_ssl = true
  res = http.request(req)
  res.is_a?(Net::HTTPSuccess)
end

def gcs_get_object_size(token, bucket, object_name)
  uri = URI("https://storage.googleapis.com/storage/v1/b/#{bucket}/o/#{URI.encode_www_form_component(object_name)}")
  req = Net::HTTP::Get.new(uri)
  req["Authorization"] = "Bearer #{token}"
  http = Net::HTTP.new(uri.host, uri.port)
  http.use_ssl = true
  res = http.request(req)
  return nil unless res.is_a?(Net::HTTPSuccess)
  JSON.parse(res.body)["size"].to_i
end

def start_gcs_upload(token, bucket, object_name, file_size)
  uri = URI("https://storage.googleapis.com/upload/storage/v1/b/#{bucket}/o?uploadType=resumable&name=#{URI.encode_www_form_component(object_name)}")
  req = Net::HTTP::Post.new(uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Type"] = "application/octet-stream"
  req["X-Upload-Content-Type"] = "application/octet-stream"
  req["X-Upload-Content-Length"] = file_size.to_s
  req["Content-Length"] = "0"
  http = Net::HTTP.new(uri.host, uri.port)
  http.use_ssl = true
  res = http.request(req)
  raise "Failed to start GCS upload: #{res.code} #{res.body}" unless res["location"]
  res["location"]
end

def upload_chunks(upload_url, file_path, label)
  file_size = File.size(file_path)
  uploaded = 0
  result = nil
  File.open(file_path, "rb") do |file|
    while uploaded < file_size
      chunk = file.read(CHUNK_SIZE)
      chunk_end = uploaded + chunk.bytesize - 1
      uri = URI(upload_url)
      req = Net::HTTP::Put.new(uri)
      req["Content-Type"] = "application/octet-stream"
      req["Content-Length"] = chunk.bytesize.to_s
      req["Content-Range"] = "bytes #{uploaded}-#{chunk_end}/#{file_size}"
      req.body = chunk
      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl = true
      http.read_timeout = 600
      res = http.request(req)
      if res.is_a?(Net::HTTPSuccess)
        log "OK", "#{label} upload complete"
        result = JSON.parse(res.body)
        break
      elsif res.code == "308"
        range = res["Range"]
        uploaded = range ? range.split("-").last.to_i + 1 : uploaded + chunk.bytesize
        pct = (uploaded.to_f / file_size * 100).round(1)
        mb_done = (uploaded / 1024.0 / 1024.0).round(1)
        mb_total = (file_size / 1024.0 / 1024.0).round(1)
        log "PROGRESS", "#{label}: #{mb_done}/#{mb_total} MB (#{pct}%)"
      else
        raise "#{label} chunk failed: #{res.code} #{res.body}"
      end
    end
  end
  raise "#{label} upload completed but no result received" if result.nil?
  result
end

json_key = ENV["SUPPLY_JSON_KEY"] or raise "SUPPLY_JSON_KEY environment variable not set"
bucket = ARGV[0] or raise "Missing argument: bucket"
package = ARGV[1] or raise "Missing argument: package"
aab_file = ARGV[2] or raise "Missing argument: aab_file"

raise "AAB file not found: #{aab_file}" unless File.exist?(aab_file)

object_name = "builds/#{package}/#{File.basename(aab_file)}"

log "INFO", "=== GCS Upload ==="
log "INFO", "Package: #{package}"
log "INFO", "AAB: #{aab_file}"
log "INFO", "GCS: gs://#{bucket}/#{object_name}"

file_size = File.size(aab_file)
log "INFO", "File size: #{(file_size / 1024.0 / 1024.0).round(1)} MB"

gcs_token = get_access_token(json_key, "https://www.googleapis.com/auth/devstorage.read_write")
log "OK", "Got GCS access token"

if gcs_object_exists?(gcs_token, bucket, object_name)
  existing_size = gcs_get_object_size(gcs_token, bucket, object_name)
  if existing_size == file_size
    log "OK", "AAB already exists in GCS (#{(existing_size / 1024.0 / 1024.0).round(1)} MB) - skipping upload"
  else
    log "WARN", "AAB exists but size differs (#{existing_size} vs #{file_size}) - re-uploading"
    upload_url = start_gcs_upload(gcs_token, bucket, object_name, file_size)
    upload_chunks(upload_url, aab_file, "GCS")
  end
else
  log "INFO", "Uploading to GCS..."
  upload_url = start_gcs_upload(gcs_token, bucket, object_name, file_size)
  upload_chunks(upload_url, aab_file, "GCS")
end

log "SUCCESS", "AAB uploaded to gs://#{bucket}/#{object_name}"
'''

    writeFile file: "${buildPath}/gcs_upload.rb", text: rubyScript

    bat """
        @echo off
        cd /d "${buildPath}"
        set AAB_FILE=
        for %%f in (*.aab) do (
            set AAB_FILE=%%f
            goto :found
        )
        :found
        if not defined AAB_FILE (
            echo [ERROR] No AAB file found in output directory
            exit /b 1
        )

        for %%A in ("%AAB_FILE%") do echo [INFO] AAB size: %%~zA bytes

        echo [INFO] Starting GCS upload...
        ruby gcs_upload.rb "${gcsBucket}" "${bundleIdentifier}" "%AAB_FILE%"
        if errorlevel 1 exit /b 1

        del gcs_upload.rb
        echo [OK] GCS upload completed
    """
}

def uploadToGooglePlay(Map config) {
    def buildPath = config.buildPath
    def bundleIdentifier = config.bundleIdentifier
    def track = config.track ?: 'internal'
    def releaseStatus = config.releaseStatus ?: 'completed'
    def retryDelayMinutes = config.retryDelayMinutes ?: 5  // Retry interval in minutes (keeps retrying until cancelled)

    def rubyScript = libraryResource('scripts/play_upload.rb')

    writeFile file: "${buildPath}/play_upload.rb", text: rubyScript

    // Find AAB file first
    def aabFile = bat(
        script: """
            @echo off
            cd /d "${buildPath}"
            for %%f in (*.aab) do (
                echo %%f
                goto :eof
            )
        """,
        returnStdout: true
    ).trim()

    if (!aabFile) {
        error "[ERROR] No AAB file found in ${buildPath}"
    }

    echo "[INFO] Found AAB: ${aabFile}"

    // Lock per project+track to prevent concurrent uploads to the same Google Play listing
    // Different projects or different tracks can upload in parallel
    def lockName = "google-play-upload-${bundleIdentifier}-${track}"

    // Check if lock is held and notify Slack if we need to wait
    def lockAcquired = false
    lock(resource: lockName, skipIfLocked: true) {
        lockAcquired = true
    }

    if (!lockAcquired) {
        echo "[INFO] Google Play upload lock is held by another job for ${bundleIdentifier} (${track}), waiting..."
        try {
            def userMention = common.getSlackMention(env.BUILD_USER ?: 'Unknown', env.BUILD_USER_EMAIL)
            common.sendSlackMessage(
                channel: '#builds',
                message: ":hourglass: <${env.BUILD_URL}console|${env.JOB_NAME} #${env.BUILD_NUMBER}> waiting for Google Play upload lock (${bundleIdentifier} ${track}) ${userMention}"
            )
        } catch (Exception e) {
            echo "[WARN] Failed to send Slack notification: ${e.message}"
        }
    }

    // Retry loop for expired edit errors - keeps retrying locally until success or user cancels
    // 5 minute delay between retries, lock released between attempts so other jobs aren't blocked
    def attempt = 0
    def success = false
    def lastOutput = ""

    while (!success) {
        attempt++

        // Wait between retries BEFORE acquiring the lock
        if (attempt > 1) {
            echo "[INFO] Waiting ${retryDelayMinutes} minutes before retry..."
            try {
                timeout(time: retryDelayMinutes, unit: 'MINUTES') {
                    input message: "Google Play edit expired. Will retry in ${retryDelayMinutes} minutes...",
                          ok: "Retry Now",
                          submitter: ""
                }
                echo "[INFO] User requested immediate retry"
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                def cause = e.causes?.find { it instanceof org.jenkinsci.plugins.workflow.support.steps.input.Rejection }
                if (cause) {
                    error "[ABORTED] Google Play upload cancelled by user"
                }
                echo "[INFO] Timeout expired, proceeding with retry..."
            }
        }

        // Per-project+track lock - acquired for each attempt, released after
        lock(resource: lockName) {
            echo "[INFO] Acquired Google Play upload lock for ${bundleIdentifier} (${track})"

            echo "[INFO] Google Play upload attempt ${attempt}..."

            // Run Ruby script with live console output AND capture to log file for error parsing
            def logFile = "${buildPath}\\play_upload.log"
            // Ruby prints EXIT_CODE:N as its last line before exiting
            // Use tee if available (Git for Windows), otherwise redirect to file
            bat """
                @echo off
                cd /d "${buildPath}"
                set "TEE_PATH=C:\\Program Files\\Git\\usr\\bin\\tee.exe"
                if exist "%TEE_PATH%" (
                    ruby play_upload.rb "${bundleIdentifier}" "${aabFile}" "${track}" "${releaseStatus}" 2>&1 | "%TEE_PATH%" "${logFile}"
                ) else (
                    ruby play_upload.rb "${bundleIdentifier}" "${aabFile}" "${track}" "${releaseStatus}" > "${logFile}" 2>&1
                )
                exit /b 0
            """
            lastOutput = readFile(file: logFile).trim()
        } // lock released - other jobs can proceed while we parse/wait

        // Parse result from log - check EXIT_CODE first (Ruby may log edit-expired
        // messages from internal retries even when the upload ultimately succeeded)
        def hasExitCode0 = lastOutput.contains("EXIT_CODE:0")
        def hasSuccess = lastOutput.contains("[SUCCESS]")

        if (hasExitCode0 || hasSuccess) {
            success = true
            echo "[OK] Google Play upload completed"
            common.updateUploadStatus('store', 'done')
        } else {
            // Only check edit expiration when the upload actually failed
            def hasEditExpired = lastOutput.contains("edit has expired") || lastOutput.contains("Edit has been deleted")
            echo "[DEBUG] EXIT_CODE:0=${hasExitCode0}, edit_expired=${hasEditExpired}"

            if (hasEditExpired) {
                echo "[WARN] Edit expired on attempt ${attempt}, will retry..."
            } else {
                common.updateUploadStatus('store', 'failed')
                error "[ERROR] Google Play upload failed:\n${lastOutput}"
            }
        }
    }

    // Cleanup
    bat """
        @echo off
        del "${buildPath}\\play_upload.rb"        del "${buildPath}\\play_upload.log"    """
}

// ============================================================================
// AMAZON APP STORE UPLOAD
// ============================================================================

/**
 * Upload APK to Amazon Appstore via App Submission API.
 * Creates an edit, replaces the APK, does NOT commit (manual review on console).
 *
 * Requires environment variables: AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, AMAZON_APP_ID
 * (first two injected via withCredentials in the Jenkinsfile)
 *
 * @param config.buildPath  Path to build output directory containing the APK
 */
/**
 * Upload APK to Amazon Appstore via the App Submission API.
 * Creates an edit, replaces the APK, and stops WITHOUT committing.
 * Uses PowerShell on the agent for all HTTP calls (file is local to agent, not controller).
 *
 * Requires env: AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, AMAZON_APP_ID
 * Ref: https://developer.amazon.com/api/appstore/v1
 */
def amazonUpload(Map config) {
    def buildPath = config.buildPath ?: env.BUILD_PATH

    if (!env.AMAZON_APP_ID?.trim()) {
        error "[Amazon] AMAZON_APP_ID not set — configure it in the job environment variables"
    }
    if (!env.AMAZON_CLIENT_ID?.trim() || !env.AMAZON_CLIENT_SECRET?.trim()) {
        error "[Amazon] AMAZON_CLIENT_ID and AMAZON_CLIENT_SECRET must be set"
    }

    // Find the APK in the build path
    def apkFile = bat(script: """@echo off
for %%f in ("${buildPath}\\*.apk") do (
    echo %%f
    goto :eof
)""", returnStdout: true).trim()

    if (!apkFile) {
        error "[Amazon] No APK found in ${buildPath}"
    }
    echo "[Amazon] Found APK: ${apkFile}"

    // Write the upload script to a temp file and run it on the agent via PowerShell.
    // This avoids the controller/agent split problem with Groovy File/HTTP APIs.
    def psScript = libraryResource('scripts/amazon_upload.ps1')
    def scriptPath = "${buildPath}\\amazon_upload.ps1"
    writeFile file: scriptPath, text: psScript

    def status = bat(script: """@powershell -NoProfile -ExecutionPolicy Bypass -File "${scriptPath}" "${apkFile}" """, returnStatus: true)

    // Cleanup
    bat script: "@del \"${scriptPath}\" 2>nul", returnStatus: true

    if (status != 0) {
        common.updateUploadStatus('store', 'failed')
        error "[Amazon] Upload failed (exit code ${status})"
    }

    common.updateUploadStatus('store', 'done')
    echo "[Amazon] Upload complete — check Amazon Developer Console to review and commit"
}

// ============================================================================
// CONSOLE LOG COLLECTION & FILTERING
// ============================================================================

/**
 * Collect, filter, and archive the console log for failed builds.
 * Extracts the failure exception, stage logs, and external tool logs,
 * then filters noise and deduplicates lines via a Ruby script.
 * The filtered result is saved to ARTIFACT_PATH/console_log.txt.
 *
 * @return The filtered log content as a String, or null on failure
 */
def collectFilteredConsoleLog() {
    try {
        // Extract the actual exception that caused the build failure
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

        // Extract failed stage logs from raw console log
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

        // Append external tool logs from artifact path (e.g. play_upload.log)
        try {
            if (env.ARTIFACT_PATH) {
                bat """
                    for %%f in ("${env.ARTIFACT_PATH}\\*.log") do (
                        echo. >> console_log.txt
                        echo === EXTERNAL LOG: %%~nxf === >> console_log.txt
                        type "%%f" >> console_log.txt
                    )
                """
            }
        } catch (Exception ex) {
            echo "[DEBUG] Could not append teed logs: ${ex.message}"
        }

        // Filter noise and deduplicate via Ruby script
        def filterScript = '''
require "json"

MAX_LOG_LINES = 1000

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
  /^Shader warning in /,
  /^file: Assets/,
  /^Current files:$/,
  /: editor enabled (?:True|False), build targets/,
  /^Collecting assets\\.\\.\\.$/,
  /^Packing sprites\\.\\.\\.$/,
  /^(?:Loading|Unloading) (?:native|managed) assembly/,
  /^\\s*\\d+ assets? (?:added|changed|removed|unchanged)/,
  /^Refreshing native plugins/,
  /^Preloading \\d+ native plugins/,
  /^Native extension for /,
  /^\\[Licensing\\]/,
  /^Using pre-set license/,
  /^Successfully changed project path/,
  /^\\s*\\[\\d+\\/\\d+\\] /,
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
        bat script: '@ruby filter_log.rb 2>&1', returnStatus: true

        // Copy filtered log to artifact path for archival
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
                bat script: "@copy console_log.txt \"${env.ARTIFACT_PATH}\\console_log.txt\" >nul 2>&1", returnStatus: true
            }
        }

        return filteredLog
    } catch (Exception e) {
        echo "[WARN] Console log collection failed: ${e.message}"
        return null
    }
}

// ============================================================================
// FIREBASE / CRASHLYTICS
// ============================================================================

/**
 * Check Firebase CLI installation (installed via npm)
 * @return Map with 'available' boolean and 'message' string
 */
def checkFirebaseCLI() {
    // Use a single bat script that checks all locations and writes results to a file
    // This avoids all Groovy string interpolation with Windows paths
    // NOTE: setlocal is NOT used because it prevents PATH changes from affecting subprocess calls
    // Use NODEJS_HOME if set by checkNodeJS, otherwise fall back to common path
    def nodejsDir = (env.NODEJS_HOME ?: 'C:\\Program Files\\nodejs').replace('/', '\\')
    def exitCode = bat(script: """@echo off

REM Ensure Node.js is in PATH (required for firebase.cmd subprocess calls to bare "node")
if exist "${nodejsDir}\\node.exe" (
    set "PATH=${nodejsDir};%PATH%"
    echo [DEBUG] Added Node.js to PATH: ${nodejsDir}
)

echo [DEBUG] Checking for Firebase CLI...

REM 1. Check Node.js directory first — firebase.cmd co-located with node.exe always works
REM    (firebase.cmd uses %%~dp0\\node.exe which resolves to the same directory)
set "CHECK_PATH=${nodejsDir}\\firebase.cmd"
echo [DEBUG] Checking: %CHECK_PATH%
if not exist "%CHECK_PATH%" (
    echo [DEBUG] NOT FOUND: %CHECK_PATH%
    goto :check_npm_global
)
echo [DEBUG] FOUND: %CHECK_PATH%
call "%CHECK_PATH%" --version > "%TEMP%\\firebase_ver_test.txt" 2>&1
if errorlevel 1 (
    echo [DEBUG] firebase --version failed at %CHECK_PATH%
    type "%TEMP%\\firebase_ver_test.txt"
    goto :check_npm_global
)
type "%TEMP%\\firebase_ver_test.txt"
for /f "usebackq tokens=*" %%v in ("%TEMP%\\firebase_ver_test.txt") do (
    echo %%v | findstr /r "^[0-9]" >nul
    if not errorlevel 1 (
        echo FOUND_AT=%CHECK_PATH% > "%TEMP%\\firebase_check.txt"
        echo VERSION=%%v >> "%TEMP%\\firebase_check.txt"
        exit /b 0
    ) else (
        echo [DEBUG] firebase --version output is not a version number: %%v
    )
)

:check_npm_global
REM 2. Check user npm global location (fallback — requires node in PATH to work)
set "CHECK_PATH=%USERPROFILE%\\AppData\\Roaming\\npm\\firebase.cmd"
echo [DEBUG] Checking: %CHECK_PATH%
if not exist "%CHECK_PATH%" (
    echo [DEBUG] NOT FOUND: %CHECK_PATH%
    goto :firebase_not_found
)
echo [DEBUG] FOUND: %CHECK_PATH%
call "%CHECK_PATH%" --version > "%TEMP%\\firebase_ver_test.txt" 2>&1
if errorlevel 1 (
    echo [DEBUG] firebase --version failed at %CHECK_PATH%
    type "%TEMP%\\firebase_ver_test.txt"
    goto :firebase_not_found
)
type "%TEMP%\\firebase_ver_test.txt"
for /f "usebackq tokens=*" %%v in ("%TEMP%\\firebase_ver_test.txt") do (
    echo %%v | findstr /r "^[0-9]" >nul
    if not errorlevel 1 (
        echo FOUND_AT=%CHECK_PATH% > "%TEMP%\\firebase_check.txt"
        echo VERSION=%%v >> "%TEMP%\\firebase_check.txt"
        exit /b 0
    ) else (
        echo [DEBUG] firebase --version output is not a version number: %%v
    )
)

:firebase_not_found

echo [DEBUG] Firebase CLI not found or not working
echo NOT_FOUND > "%TEMP%\\firebase_check.txt"
exit /b 1
""", returnStatus: true)

    echo "[DEBUG] Firebase check bat script exit code: ${exitCode}"

    // Read the result file
    try {
        def result = readFile("${env.TEMP}/firebase_check.txt").trim()
        echo "[DEBUG] Firebase check result file contents: ${result}"

        if (result.startsWith('FOUND_AT=')) {
            def lines = result.split('\n')
            def path = lines[0].replace('FOUND_AT=', '').trim()
            def version = lines.size() > 1 ? lines[1].replace('VERSION=', '').trim() : 'unknown'
            // Store the path using forward slashes to avoid regex issues
            env.FIREBASE_CMD = path.replace('\\', '/')
            echo "[OK] Firebase CLI ${version} at ${path}"
            return [available: true, message: "Firebase CLI ${version} (found at ${path})"]
        }
    } catch (Exception e) {
        echo "[DEBUG] Error reading firebase check result: ${e.message}"
    }

    return [
        available: false,
        message: 'Firebase CLI not installed'
    ]
}

/**
 * Upload native symbols to Firebase Crashlytics
 *
 * Requires both environment variables to be set in the Jenkins job:
 *   - UPLOAD_CRASHLYTICS_SYMBOLS=true (opt-in flag)
 *   - FIREBASE_APP_ID (the Firebase app ID)
 *
 * If either is not set, this function will skip silently.
 *
 * @param config Map containing:
 *   - buildPath: Path to the build output directory
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
    def platform = config.platform ?: env.PLATFORM ?: 'Android'

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

    // Find symbol files based on platform
    if (platform == 'Android') {
        uploadAndroidCrashlyticsSymbols(buildPath, firebaseAppId)
    } else if (platform == 'iOS') {
        uploadiOSCrashlyticsSymbols(buildPath, firebaseAppId)
    } else {
        echo "[WARN] Crashlytics symbol upload not supported for platform: ${platform}"
    }
}

/**
 * Upload Android native symbols (.so files) to Crashlytics
 */
def uploadAndroidCrashlyticsSymbols(String buildPath, String appId) {
    // Look for symbols in Unity's output locations
    // Prefer *.symbols.zip (from DebugSymbolFormat.Zip setting) as it's the correct format for Crashlytics
    // The BackUpThisFolder contains IL2CPP intermediate files, not the format Crashlytics expects

    def symbolsPath = null
    def hasSymbols = false

    // FIRST: Check for *.symbols.zip (Unity's naming convention when DebugSymbolFormat.Zip is set)
    // This is the preferred format for Crashlytics
    def symbolsZipSearch = bat(
        script: """@echo off
            cd /d "${buildPath}"
            for %%f in (*.symbols.zip) do (
                echo %%~ff
                goto :eof
            )
            if exist "symbols.zip" echo %CD%\\symbols.zip
        """,
        returnStdout: true
    ).trim()

    if (symbolsZipSearch) {
        // Validate the zip contains symbol files
        // Unity generates .sym.so/.dbg.so (with Full level) or .so.sym files
        def zipValidation = bat(
            script: """@echo off
                powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; try { \$zip = [System.IO.Compression.ZipFile]::OpenRead('${symbolsZipSearch.replace('\\', '\\\\')}'); \$symFiles = \$zip.Entries | Where-Object { \$_.Name -like '*.so' -or \$_.Name -like '*.so.*' }; \$count = \$symFiles.Count; \$zip.Dispose(); if (\$count -gt 0) { Write-Host 'valid' } else { Write-Host 'empty' } } catch { Write-Host 'error' }"
            """,
            returnStdout: true
        ).trim()

        if (zipValidation == 'valid') {
            symbolsPath = symbolsZipSearch
            hasSymbols = true
            echo "[INFO] Found symbols zip with native symbol files"
        } else {
            echo "[WARN] Found ${symbolsZipSearch} but it contains no symbol files (.so, .sym.so, .dbg.so)"
            // List zip contents for debugging
            bat """@echo off
                echo [DEBUG] Symbols zip contents:
                powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; \$zip = [System.IO.Compression.ZipFile]::OpenRead('${symbolsZipSearch.replace('\\', '\\\\')}'); \$zip.Entries | Select-Object -First 20 | ForEach-Object { Write-Host ('  ' + \$_.FullName) }; if (\$zip.Entries.Count -gt 20) { Write-Host ('  ... and ' + (\$zip.Entries.Count - 20) + ' more files') }; \$zip.Dispose()"
                echo.
                echo [INFO] Unity must be configured to include debug symbols:
                echo [INFO]   1. Build Settings ^> Create symbols.zip = Public or Debugging
                echo [INFO]   2. Player Settings ^> Other Settings ^> Configuration ^> Scripting Backend = IL2CPP
                echo [INFO]   3. Rebuild the project with these settings enabled
            """
        }
    }

    // SECOND: Check standard locations (folders with .so files)
    if (!hasSymbols) {
        def locations = ["${buildPath}\\symbols", "${buildPath}\\unstripped"]
        for (loc in locations) {
            def found = bat(script: "@if exist \"${loc}\" echo found", returnStdout: true).trim()
            if (found == 'found') {
                symbolsPath = loc
                hasSymbols = true
                echo "[INFO] Found symbols directory"
                break
            }
        }
    }

    // NOTE: We intentionally do NOT use the BackUpThisFolder as a fallback anymore
    // That folder contains IL2CPP intermediate files, not the .so format Firebase needs
    // If no symbols.zip is found, it means Unity wasn't configured to generate debug symbols

    if (!hasSymbols) {
        echo "[WARN] No symbols.zip found in ${buildPath}, skipping Crashlytics upload"
        echo "[INFO] Ensure Unity is configured with:"
        echo "[INFO]   DebugSymbols.level = Full"
        echo "[INFO]   DebugSymbols.format = Zip"
        return
    }

    echo "[INFO] Found symbols at: ${symbolsPath}"

    // Unity generates .sym.so or .so.sym files (Breakpad format), but Firebase CLI expects .so files
    // Extract zip, rename symbol files, and upload the folder
    // See: https://github.com/firebase/firebase-unity-sdk/issues/1142
    def uploadPath = symbolsPath
    if (symbolsPath.endsWith('.zip')) {
        def extractDir = symbolsPath.replace('.zip', '_extracted')
        def psScript = symbolsPath.replace('.zip', '_prepare.ps1')
        echo "[INFO] Extracting and preparing symbols for Crashlytics upload"

        // Write PowerShell script to file to avoid escaping issues
        writeFile file: psScript, text: """
Add-Type -AssemblyName System.IO.Compression.FileSystem
\$extractDir = '${extractDir.replace('\\', '/')}'
\$symbolsZip = '${symbolsPath.replace('\\', '/')}'

if (Test-Path \$extractDir) { Remove-Item -Recurse -Force \$extractDir }
[System.IO.Compression.ZipFile]::ExtractToDirectory(\$symbolsZip, \$extractDir)
Write-Host '[INFO] Extracted symbols zip'

# Rename .sym.so -> .so and .dbg.so -> .so (Firebase expects .so files)
\$renamed = 0
Get-ChildItem -Path \$extractDir -Recurse -File | Where-Object { \$_.Name -match '\\.(sym\\.so|dbg\\.so|so\\.sym|so\\.dbg)\$' } | ForEach-Object {
    \$newName = \$_.Name -replace '\\.(sym|dbg)\\.so\$','.so' -replace '\\.so\\.(sym|dbg)\$','.so'
    \$newPath = Join-Path \$_.DirectoryName \$newName
    if (-not (Test-Path \$newPath)) {
        Rename-Item -Path \$_.FullName -NewName \$newName
        \$renamed++
    }
}
Write-Host "[INFO] Renamed \$renamed symbol files to .so format"

# Remove Firebase libraries that cause issues
\$removed = 0
Get-ChildItem -Path \$extractDir -Recurse -File | Where-Object { \$_.Name -like 'libFirebaseCpp*' } | ForEach-Object {
    Remove-Item -Force \$_.FullName
    \$removed++
}
if (\$removed -gt 0) { Write-Host "[INFO] Removed \$removed Firebase library files" }

# List what we're uploading
Write-Host '[INFO] Symbol files to upload:'
Get-ChildItem -Path \$extractDir -Recurse -Filter '*.so' | Select-Object -First 10 | ForEach-Object {
    Write-Host ('  ' + \$_.FullName.Replace(\$extractDir, ''))
}
"""

        bat """
            @echo off
            powershell -ExecutionPolicy Bypass -File "${psScript}"
        """
        uploadPath = extractDir
    }

    // Use FIREBASE_CMD if set (for Jenkins service accounts without PATH)
    def firebaseCmd = env.FIREBASE_CMD ?: 'firebase'
    def nodejsDirUpload = (env.NODEJS_HOME ?: 'C:\\Program Files\\nodejs').replace('/', '\\')
    withCredentials([file(credentialsId: 'google-play-json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        bat """
            @echo off
            REM Ensure Node.js is in PATH (required for firebase.cmd)
            if exist "${nodejsDirUpload}\\node.exe" set "PATH=${nodejsDirUpload};%PATH%"

            REM Ensure Java is in PATH (required for Crashlytics buildtools)
            REM Check common Java locations: JAVA_HOME, Android Studio JBR, Program Files
            if defined JAVA_HOME (
                set "PATH=%JAVA_HOME%\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Android\\Android Studio\\jbr\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Java\\jdk-17\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Microsoft\\jdk-17*\\bin\\java.exe" (
                for /d %%j in ("C:\\Program Files\\Microsoft\\jdk-17*") do set "PATH=%%j\\bin;%PATH%"
            )

            echo [INFO] Uploading Android symbols to Crashlytics...
            echo [INFO] Symbols path: ${uploadPath}

            "${firebaseCmd}" crashlytics:symbols:upload --app="${appId}" "${uploadPath}"
            if errorlevel 1 (
                echo [WARN] Crashlytics symbol upload failed - this is non-fatal
                echo [INFO] Crash reports will still work but may not be fully symbolicated
                REM Don't fail the build for symbol upload issues
                exit /b 0
            )

            echo [OK] Crashlytics symbols uploaded successfully
        """
    }
}

/**
 * Upload iOS dSYM files to Crashlytics
 */
def uploadiOSCrashlyticsSymbols(String buildPath, String appId) {
    // Look for dSYM files
    def dsymPath = bat(
        script: """
            @echo off
            cd /d "${buildPath}"
            for /r %%f in (*.dSYM) do (
                echo %%f
                goto :eof
            )
        """,
        returnStdout: true
    ).trim()

    if (!dsymPath) {
        echo "[WARN] No dSYM files found in ${buildPath}, skipping Crashlytics upload"
        return
    }

    echo "[INFO] Found dSYM at: ${dsymPath}"

    // Use FIREBASE_CMD if set (for Jenkins service accounts without PATH)
    def firebaseCmd = env.FIREBASE_CMD ?: 'firebase'
    def nodejsDirUpload = (env.NODEJS_HOME ?: 'C:\\Program Files\\nodejs').replace('/', '\\')
    withCredentials([file(credentialsId: 'google-play-json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        bat """
            @echo off
            REM Ensure Node.js is in PATH (required for firebase.cmd)
            if exist "${nodejsDirUpload}\\node.exe" set "PATH=${nodejsDirUpload};%PATH%"

            REM Ensure Java is in PATH (required for Crashlytics buildtools)
            if defined JAVA_HOME (
                set "PATH=%JAVA_HOME%\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Android\\Android Studio\\jbr\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.13.11-hotspot\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe" (
                set "PATH=C:\\Program Files\\Java\\jdk-17\\bin;%PATH%"
            ) else if exist "C:\\Program Files\\Microsoft\\jdk-17*\\bin\\java.exe" (
                for /d %%j in ("C:\\Program Files\\Microsoft\\jdk-17*") do set "PATH=%%j\\bin;%PATH%"
            )

            echo [INFO] Uploading iOS dSYM to Crashlytics...

            "${firebaseCmd}" crashlytics:symbols:upload --app="${appId}" "${dsymPath}"
            if errorlevel 1 (
                echo [ERROR] Crashlytics dSYM upload failed
                exit /b 1
            )

            echo [OK] Crashlytics dSYM uploaded successfully
        """
    }
}

return this
