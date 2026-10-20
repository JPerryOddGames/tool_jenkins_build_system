using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.AddressableAssets;
using UnityEditor.AddressableAssets.Build;
using UnityEditor.AddressableAssets.Settings;
using UnityEditor.AddressableAssets.Settings.GroupSchemas;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.AddressableAssets.ResourceLocators;
using UnityEngine.ResourceManagement.AsyncOperations;
#if UNITY_ANDROID
using UnityEditor.Android;
#endif

namespace ODDFramework
{
    public static partial class Pipeline
    {
        public static string Branch { get; private set; }
        public static string BranchSanitized { get; private set; }
        public static int BuildNumber { get; private set; }
        public static int Changeset { get; private set; }
        public static bool IsMain { get; private set; }
        public static bool IsBatchMode { get; private set; }
        public static BuildTargetGroup CurrentBuildTargetGroup => EditorUserBuildSettings.selectedBuildTargetGroup;

        private const string ENV_KEY_BRANCH = "BRANCH";
        private const string ENV_KEY_BUILDNUMBER = "BUILD_NUMBER";
        private const string ENV_KEY_CHANGESET = "CHANGESET_ID";
        private const string ENV_KEY_BUILDPATH = "BUILD_PATH";
        private const string ENV_KEY_ARTIFACTPATH = "ARTIFACT_PATH";
        private const string ENV_KEY_PREBUILD_METHODS = "PRE_BUILD_METHODS";

        private static BuildReport report;
        private static readonly List<string> capturedErrors = new();

        [InitializeOnLoadMethod]
        private static void Initialize()
        {
            IsBatchMode = Application.isBatchMode;

            Branch = GetVariable(ENV_KEY_BRANCH, string.Empty);
            BranchSanitized = string.IsNullOrEmpty(Branch) ? string.Empty : Branch.Replace('/', '_').Replace('\\', '_');

            if (!int.TryParse(GetVariable(ENV_KEY_BUILDNUMBER, "-1"), out int buildNum))
                buildNum = -1;
            BuildNumber = buildNum;

            if (!int.TryParse(GetVariable(ENV_KEY_CHANGESET, "-1"), out int changeset))
                changeset = -1;
            Changeset = changeset;

            IsMain = !string.IsNullOrEmpty(Branch) && (Branch == "/main" || Branch.EndsWith("/main") || Branch.Contains("/main/"));
        }

        public static string GetVariable(string key, string @default = null)
        {
            string value = System.Environment.GetEnvironmentVariable(key);
            if (string.IsNullOrEmpty(value))
            {
                if (@default == null)
                    throw new Exception($"Required environment variable '{key}' is not set");
                return @default;
            }
            return value;
        }

        public static string BuildPath => GetVariable(ENV_KEY_BUILDPATH);
        public static string ArtifactPath => GetVariable(ENV_KEY_ARTIFACTPATH, string.Empty);
        public static string OutputPrefix => GetVariable("APP_NAME", "BUILD");

public static BuildOptions GetBuildOptions()
        {
            BuildOptions options = BuildOptions.CompressWithLz4HC;

            if (EditorUserBuildSettings.development)
                options |= BuildOptions.Development;

            return options;
        }

        public static void SetupBuildVersion()
        {
            string versionString = GetVariable("VERSION");
            if (string.IsNullOrEmpty(versionString))
                return;

            var version = new ODDFramework.Version(versionString);
            // Set bundle version without feature suffix to comply with iOS requirements (max 18 chars, numbers and dots only)
            PlayerSettings.bundleVersion = version.MajorMinorRevision;

            // Disable Unity splash screen logo
            PlayerSettings.SplashScreen.showUnityLogo = false;

            Log($"✓ bundleVersion: {PlayerSettings.bundleVersion}");
            Log($"✓ Unity splash logo disabled");
        }

        public static void Prepare(string additionalDefines = null, Action platformSetup = null)
        {
            Log("Starting build preparation");

            UseEmbeddedAndroidTools();

            Type.GetType("ODDFramework.PlatformSpecificBundlesConfig")?.GetMethod("Setup")?.Invoke(null, null);
            Type.GetType("ODDFramework.PipelineSetup")?.GetMethod("OnPrepare")?.Invoke(null, null);

            platformSetup?.Invoke();
            SetupBuildVersion();

            if (!string.IsNullOrEmpty(additionalDefines))
            {
                ApplyScriptingDefines(additionalDefines);
            }

            Log("Build preparation complete");
        }

        /// <summary>
        /// Force Unity to use its bundled JDK, SDK, NDK, and Gradle instead of external installations.
        /// Without this, batchmode builds on fresh agents fail with "JDK not found" because Unity's
        /// editor preferences haven't been configured via the GUI.
        /// </summary>
        private static void UseEmbeddedAndroidTools()
        {
#if UNITY_ANDROID
            // Always force Unity to use its bundled JDK, SDK, NDK ("Installed with Unity").
            // Setting to null = embedded. If the module isn't installed, verifyAndroidJdk()
            // in the preflight stage will have already failed the build before we get here.
            // See: https://docs.unity3d.com/6000.0/Documentation/ScriptReference/Android.AndroidExternalToolsSettings-jdkRootPath.html
            AndroidExternalToolsSettings.jdkRootPath = null;
            AndroidExternalToolsSettings.sdkRootPath = null;
            AndroidExternalToolsSettings.ndkRootPath = null;
            Log("✓ Android external tools set to embedded (JDK, SDK, NDK)");
#endif
        }

        private static void ApplyScriptingDefines(string scriptingDefines)
        {
            Log($"Applying scripting defines: '{scriptingDefines}'");

            var targetGroup = EditorUserBuildSettings.selectedBuildTargetGroup;
            var defines = scriptingDefines.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries);

            foreach (var define in defines)
            {
                var trimmedDefine = define.Trim();
                if (!string.IsNullOrEmpty(trimmedDefine))
                {
                    ScriptingDefines.Append(targetGroup, trimmedDefine);
                }
            }

            Log($"Applied defines: {string.Join(", ", ScriptingDefines.GetList(targetGroup))}");
        }

        private static void UpdateVersionData()
        {
            const string VERSION_DATA_PATH = "Assets/Resources/VersionData.asset";

            // Ensure Resources folder exists
            string resourcesPath = "Assets/Resources";
            if (!Directory.Exists(resourcesPath))
            {
                Directory.CreateDirectory(resourcesPath);
            }

            // Load or create VersionData asset
            VersionScriptableObject versionData = AssetDatabase.LoadAssetAtPath<VersionScriptableObject>(VERSION_DATA_PATH);

            if (versionData == null)
            {
                versionData = ScriptableObject.CreateInstance<VersionScriptableObject>();
                AssetDatabase.CreateAsset(versionData, VERSION_DATA_PATH);
            }

            // Populate from Pipeline values
            versionData.bundleVersionString = PlayerSettings.bundleVersion;
            versionData.bundleIdentifier = PlayerSettings.applicationIdentifier;
            versionData.buildNumber = BuildNumber;
            versionData.revisionNumber = Changeset;
            versionData.changesetId = Changeset.ToString();
            versionData.branchName = Branch;
            versionData.buildDate = System.DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss UTC");

            // Extract feature name from version string
            string version = PlayerSettings.bundleVersion;
            int dashIndex = version.IndexOf('-');
            versionData.featureName = dashIndex > 0 ? version.Substring(dashIndex + 1) : "";

            // Save changes
            EditorUtility.SetDirty(versionData);
            AssetDatabase.SaveAssets();

            Log($"✓ VersionData updated: {versionData.GetFullVersion()} (Branch: {versionData.branchName}, Build: {versionData.buildNumber})");
        }

public static void Build(string outputPath, BuildOptions options)
        {
            int exitCode = 1;

            try
            {
                // Ensure embedded Android tools are set for every Unity invocation
                // (Prepare and Build run as separate processes — EditorPrefs may not persist in batchmode)
                UseEmbeddedAndroidTools();

                // Update version data before building so it's included in the build
                UpdateVersionData();

                // Project checks run before any content is built, so a failure costs no build time.
                RunPreBuildMethods();

                // Addressables content is built by the player build itself when the project's
                // "Build Addressables on Player Build" setting is on; only build it up-front when
                // that setting is off (otherwise nothing would build it). Self-skips if unused.
                EnsureAddressablesContentBuilt(clean: CleanAddressablesRequested());

                report = RunPlayerBuild(outputPath, options);

                // Verify the shipped catalog indexes every entry it should. Any missing entries are
                // surfaced (full list in the log AND an archived report file) and a marker is written
                // so the Jenkins pipeline flags the build UNSTABLE — the player build itself is NOT
                // failed and is NOT rebuilt. To rebuild from scratch, re-run with CLEAN_ADDRESSABLES=true.
                ValidateCatalog(report, outputPath);

                bool buildSuccess = report != null && report.summary.result == BuildResult.Succeeded;
                exitCode = buildSuccess ? 0 : 1;

                if (!buildSuccess && report != null)
                {
                    ReportBuildErrors(report);
                }
                else
                {
                    ListBuildOutput(outputPath);
                }
            }
            catch (Exception ex)
            {
                // Marked so the reason reaches build_errors.txt and unity_errors.log whatever the
                // message says; most messages match none of the pipeline's error patterns.
                LogError($"{ErrorMarker} Build failed: {ex.Message}");
                exitCode = 1;
            }
            finally
            {
                Log($"Exiting Unity with code: {exitCode}");
                EditorApplication.Exit(exitCode);
            }

        }

        /// <summary>
        /// Run each static, parameterless method named in PRE_BUILD_METHODS, in order, before any content
        /// or player build. Entries are ';'-separated "Namespace.Type.Method" names, as for Unity's
        /// -executeMethod (a nested type is written "Outer+Inner"). A method that throws, or a name that
        /// does not resolve, fails the build. Unset or empty: no-op.
        /// </summary>
        private static void RunPreBuildMethods()
        {
            string spec = GetVariable(ENV_KEY_PREBUILD_METHODS, string.Empty);
            if (string.IsNullOrWhiteSpace(spec))
                return;

            foreach (string entry in spec.Split(';'))
            {
                string name = entry.Trim();
                if (name.Length == 0)
                    continue;

                System.Reflection.MethodInfo method = ResolvePreBuildMethod(name);

                Log($"Running pre-build method {name}...");
                var sw = System.Diagnostics.Stopwatch.StartNew();

                try
                {
                    method.Invoke(null, null);
                }
                catch (System.Reflection.TargetInvocationException ex)
                {
                    // The full chain is logged here: an exception built around an inner one (such as
                    // BuildFailedException) carries only a default message of its own.
                    Exception cause = ex.InnerException ?? ex;
                    LogError($"Pre-build method {name} failed after {sw.Elapsed.TotalSeconds:F1}s:\n{cause}");
                    throw new Exception($"Pre-build method '{name}' failed with {cause.GetType().Name} - see the log above");
                }

                Log($"✓ Pre-build method {name} completed in {sw.Elapsed.TotalSeconds:F1}s");
            }
        }

        private static System.Reflection.MethodInfo ResolvePreBuildMethod(string qualifiedName)
        {
            int dot = qualifiedName.LastIndexOf('.');
            if (dot <= 0 || dot == qualifiedName.Length - 1)
                throw new Exception($"{ENV_KEY_PREBUILD_METHODS} entry '{qualifiedName}' is not of the form Namespace.Type.Method");

            string typeName = qualifiedName.Substring(0, dot);
            string methodName = qualifiedName.Substring(dot + 1);

            Type type = AppDomain.CurrentDomain.GetAssemblies()
                .Select(assembly => assembly.GetType(typeName, throwOnError: false))
                .FirstOrDefault(t => t != null);

            if (type == null)
                throw new Exception($"{ENV_KEY_PREBUILD_METHODS}: no loaded assembly defines type '{typeName}'");

            const System.Reflection.BindingFlags flags =
                System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static;

            System.Reflection.MethodInfo method = type.GetMethod(methodName, flags, null, Type.EmptyTypes, null);
            if (method == null)
                throw new Exception($"{ENV_KEY_PREBUILD_METHODS}: '{typeName}' has no static parameterless method '{methodName}'");

            return method;
        }

        /// <summary>
        /// Build the Addressables catalog + bundles up-front, but ONLY when the player build won't do
        /// it itself (project setting "Build Addressables on Player Build" = off). When that setting
        /// is on, the player build owns the content build and this is a no-op — the pipeline validates
        /// that build's output afterwards instead of producing a second, redundant copy. Skips
        /// entirely when BUILD_ADDRESSABLES=false or no Addressables settings exist.
        /// </summary>
        private static void EnsureAddressablesContentBuilt(bool clean)
        {
            if (!AddressablesEnabledForBuild())
                return;

            if (SettingBuildsAddressablesWithPlayer())
            {
                Log("Addressables builds with the player build — deferring to it (its catalog is validated afterwards)");
                return;
            }

            var sw = System.Diagnostics.Stopwatch.StartNew();

            if (clean)
            {
                Log("Cleaning previous Addressables content (forces a full rebuild)");
                AddressableAssetSettings.CleanPlayerContent();
                // Also purge the SBP incremental cache — a stale cache is the usual cause of a catalog
                // that omits new entries, so a forced clean must clear it too to be effective.
                PurgeAddressablesBuildCache();
            }

            Log($"Building Addressables content for {EditorUserBuildSettings.activeBuildTarget} ({(clean ? "clean/full" : "incremental")})...");

            AddressableAssetSettings.BuildPlayerContent(out AddressablesPlayerBuildResult result);
            sw.Stop();

            if (result != null && !string.IsNullOrEmpty(result.Error))
            {
                LogError($"Addressables content build FAILED after {sw.Elapsed.TotalSeconds:F1}s: {result.Error}");
                throw new Exception($"Addressables content build failed: {result.Error}");
            }

            Log($"✓ Addressables content build complete in {sw.Elapsed.TotalSeconds:F1}s");
        }

        /// <summary>
        /// Build the player. Errors logged during the build are captured for replay (Unity's
        /// BuildReport often has empty error messages).
        /// </summary>
        private static BuildReport RunPlayerBuild(string outputPath, BuildOptions options)
        {
            // Capture errors/exceptions logged during the build so we can replay them at the end —
            // Unity's BuildReport often has empty error messages.
            capturedErrors.Clear();
            Application.logMessageReceived += CaptureLogErrors;

            string[] scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();

            Log($"Building with defines: {string.Join(",", ScriptingDefines.GetList(EditorUserBuildSettings.selectedBuildTargetGroup))}");

            BuildReport playerReport = null;
            try
            {
                playerReport = BuildPipeline.BuildPlayer
                (
                    new BuildPlayerOptions()
                    {
                        scenes = scenes,
                        target = EditorUserBuildSettings.activeBuildTarget,
                        locationPathName = outputPath,
                        options = options,
                        targetGroup = EditorUserBuildSettings.selectedBuildTargetGroup
                    }
                );
            }
            catch (Exception ex)
            {
                Debug.LogException(ex);
            }
            finally
            {
                Application.logMessageReceived -= CaptureLogErrors;
            }

            return playerReport;
        }

        /// <summary>
        /// Verify every shipped Addressable entry actually has a catalog location. Entries with none
        /// throw "No Location found for Key=&lt;guid&gt;" at runtime (blank preview icons, missing
        /// content). Any missing entries are surfaced — the full grouped list goes to the build log AND
        /// to an archived report file (AddressablesBrokenEntries.txt), whose existence is the marker the
        /// Jenkins pipeline uses to flag the build UNSTABLE. WARN-ONLY: the player build is never failed
        /// and never rebuilt here (a forced clean rebuild is opt-in via CLEAN_ADDRESSABLES=true, applied
        /// before the build). A stale report from a prior run is deleted first so the marker reflects
        /// only this build. Only runs on a succeeded player build that ships Addressables.
        /// </summary>
        private static void ValidateCatalog(BuildReport playerReport, string outputPath)
        {
            if (playerReport == null || playerReport.summary.result != BuildResult.Succeeded)
                return; // build failed for other reasons — leave the normal error path to report it

            // Remove any stale report so a clean build doesn't inherit a previous run's UNSTABLE marker.
            DeleteStaleBrokenReport(outputPath);

            if (!AddressablesEnabledForBuild())
                return;

            var missing = FindGuidsMissingFromCatalog(out int checkedCount);
            if (missing == null)
                return; // catalog couldn't be loaded — already warned

            if (missing.Count == 0)
            {
                Log($"✓ Catalog validation passed — all {checkedCount} indexed entries present");
                return;
            }

            // Warn-only: surface the full list AND write the report file (the UNSTABLE marker). The
            // build is NOT failed and NOT rebuilt — the report is the deliverable for a human to fix.
            ReportBrokenAddressables(missing, checkedCount, outputPath);
            LogWarning($"Addressables catalog is missing {missing.Count} of {checkedCount} shipped entries. " +
                       "Build NOT failed (warn-only); see AddressablesBrokenEntries.txt. Jenkins will mark this build " +
                       "UNSTABLE. If this is a stale incremental cache, re-run with CLEAN_ADDRESSABLES=true.");
        }

        /// <summary>
        /// Returns the shipped entries whose GUID has no location in the freshly-built catalog
        /// (group + address + guid for each), or null if the catalog couldn't be loaded (validation
        /// skipped). Loads the built catalog with the same runtime API that resolves AssetReferences
        /// (LoadContentCatalogAsync → IResourceLocator.Locate), so the check matches exactly how the
        /// game fails at runtime — no binary parsing. Only groups that both ship (IncludeInBuild) and
        /// index GUIDs (IncludeGUIDInCatalog) are checked — that is exactly the set an AssetReference
        /// resolves by GUID.
        /// </summary>
        private static List<BrokenAddressable> FindGuidsMissingFromCatalog(out int checkedCount)
        {
            checkedCount = 0;

            string catalogPath = LocateBuiltCatalog();
            if (catalogPath == null)
            {
                LogWarning("Could not locate the built Addressables catalog — skipping catalog validation.");
                return null;
            }

            AsyncOperationHandle<IResourceLocator> handle = default;
            IResourceLocator locator;
            try
            {
                handle = Addressables.LoadContentCatalogAsync(catalogPath, false);
                locator = handle.WaitForCompletion();
            }
            catch (Exception ex)
            {
                if (handle.IsValid())
                    Addressables.Release(handle);
                LogWarning($"Could not load the built catalog at '{catalogPath}' ({ex.Message}) — skipping catalog validation.");
                return null;
            }

            if (locator == null)
            {
                if (handle.IsValid())
                    Addressables.Release(handle);
                LogWarning($"Built catalog at '{catalogPath}' produced no locator — skipping catalog validation.");
                return null;
            }

            try
            {
                var settings = AddressableAssetSettingsDefaultObject.Settings;
                var missing = new List<BrokenAddressable>();
                var gathered = new List<AddressableAssetEntry>();

                foreach (var group in settings.groups)
                {
                    var schema = group != null ? group.GetSchema<BundledAssetGroupSchema>() : null;
                    if (schema == null || !schema.IncludeInBuild || !schema.IncludeGUIDInCatalog)
                        continue;

                    gathered.Clear();
                    foreach (var entry in group.entries)
                        entry.GatherAllAssets(gathered, includeSelf: true, recurseAll: true, includeSubObjects: false);

                    foreach (var e in gathered)
                    {
                        if (e == null || e.IsFolder || string.IsNullOrEmpty(e.guid) || string.IsNullOrEmpty(e.AssetPath))
                            continue;
                        if (!File.Exists(e.AssetPath))
                            continue; // no on-disk asset — legitimately not shipped

                        checkedCount++;
                        // type: null → pure key-existence check, mirroring how AssetReference resolves.
                        if (!locator.Locate(e.guid, null, out _))
                            missing.Add(new BrokenAddressable { Group = group.Name, Address = e.address, Guid = e.guid, AssetPath = e.AssetPath });
                    }
                }

                return missing;
            }
            finally
            {
                Addressables.Release(handle);
            }
        }

        /// <summary>A shipped Addressable entry that has no location in the built catalog.</summary>
        private sealed class BrokenAddressable
        {
            public string Group;
            public string Address;
            public string Guid;
            public string AssetPath;
        }

        private const string BrokenReportFileName = "AddressablesBrokenEntries.txt";

        /// <summary>
        /// Emit the FULL grouped list of broken Addressable entries to the build log AND to an archived
        /// report file (AddressablesBrokenEntries.txt). Each listed entry ships (IncludeInBuild) and is
        /// GUID-indexed (IncludeGUIDInCatalog) yet has no catalog location — i.e. loading it via
        /// AssetReference throws "No Location found for Key=&lt;guid&gt;" at runtime (blank icons /
        /// missing content). Warn-level, never fatal. The report file's existence is the marker the
        /// Jenkins pipeline uses to flag the build UNSTABLE and to build its Slack summary, so its shape
        /// is stable: line 2 is the count sentence, and a "PER-GROUP SUMMARY" block of "&lt;count&gt;
        /// &lt;group&gt;" lines follows (terminated by a blank line).
        /// </summary>
        private static void ReportBrokenAddressables(List<BrokenAddressable> broken, int checkedCount, string outputPath)
        {
            var byGroup = broken.GroupBy(b => b.Group).OrderByDescending(g => g.Count()).ToList();
            string countSentence = $"{broken.Count} of {checkedCount} shipped, GUID-indexed Addressable entries have no catalog location.";

            LogWarning($"========== ADDRESSABLES: {broken.Count} BROKEN ENTRIES ==========");
            LogWarning(countSentence);
            LogWarning("They will fail at runtime with \"No Location found for Key=<guid>\" (blank icons / missing content).");
            LogWarning("Broken entries per group:");
            foreach (var g in byGroup)
                LogWarning($"  {g.Count(),6}  {g.Key}");

            // Full list in the log itself (grouped), so the console has everything the artifact does.
            foreach (var g in byGroup)
            {
                LogWarning($"--- {g.Key}  ({g.Count()}) ---");
                foreach (var b in g.OrderBy(x => x.Address, StringComparer.Ordinal))
                    LogWarning($"  {b.Address}   [{b.AssetPath}]   {b.Guid}");
            }

            try
            {
                string reportPath = GetBrokenReportPath(outputPath);
                if (reportPath != null)
                {
                    var sb = new System.Text.StringBuilder();
                    // Line 1 = title, line 2 = count sentence (the pipeline reads line 2 verbatim).
                    sb.AppendLine("ADDRESSABLES BROKEN-ENTRY REPORT");
                    sb.AppendLine(countSentence);
                    sb.AppendLine();
                    sb.AppendLine("These entries fail to load at runtime (\"No Location found for Key=<guid>\") — blank preview");
                    sb.AppendLine("icons / missing content. The player build was NOT failed; the build is flagged UNSTABLE so");
                    sb.AppendLine("this is fixed before shipping. To rule out a stale cache, re-run with CLEAN_ADDRESSABLES=true.");
                    sb.AppendLine();
                    sb.AppendLine("PER-GROUP SUMMARY");
                    foreach (var g in byGroup)
                        sb.AppendLine($"  {g.Count(),6}  {g.Key}");
                    sb.AppendLine();
                    sb.AppendLine("DETAIL  (each entry: address / asset path / guid)");
                    foreach (var g in byGroup)
                    {
                        sb.AppendLine($"=== {g.Key}  ({g.Count()}) ===");
                        foreach (var b in g.OrderBy(x => x.Address, StringComparer.Ordinal))
                        {
                            sb.AppendLine($"  {b.Address}");
                            sb.AppendLine($"      asset: {b.AssetPath}");
                            sb.AppendLine($"      guid : {b.Guid}");
                        }
                        sb.AppendLine();
                    }
                    File.WriteAllText(reportPath, sb.ToString());
                    LogWarning($"Full broken-entry report written to: {reportPath}");
                }
                else
                {
                    LogWarning("No writable directory found — report file not written (the full list above is complete).");
                }
            }
            catch (Exception ex)
            {
                LogWarning($"Could not write broken-entry report file: {ex.Message}");
            }

            LogWarning("======================================================");
        }

        /// <summary>Delete a broken-entry report left by a previous build so a clean build doesn't
        /// inherit its UNSTABLE marker.</summary>
        private static void DeleteStaleBrokenReport(string outputPath)
        {
            try
            {
                string reportPath = GetBrokenReportPath(outputPath);
                if (reportPath != null && File.Exists(reportPath))
                    File.Delete(reportPath);
            }
            catch (Exception ex)
            {
                LogWarning($"Could not remove stale broken-entry report: {ex.Message}");
            }
        }

        /// <summary>
        /// Path for the broken-entry report. Prefers ARTIFACT_PATH so the report is auto-archived by
        /// the pipeline and sits at a stable location the Jenkins UNSTABLE check can find regardless
        /// of platform; falls back to the build output dir, then BUILD_PATH. Returns null if no
        /// writable directory can be resolved.
        /// </summary>
        private static string GetBrokenReportPath(string outputPath)
        {
            string artifacts = ArtifactPath; // "" when ARTIFACT_PATH is unset
            if (!string.IsNullOrEmpty(artifacts) && Directory.Exists(artifacts))
                return Path.Combine(artifacts, BrokenReportFileName);

            string dir = !string.IsNullOrEmpty(outputPath) ? Path.GetDirectoryName(outputPath) : null;
            if (string.IsNullOrEmpty(dir) || !Directory.Exists(dir))
                dir = BuildPathSafe();
            return (!string.IsNullOrEmpty(dir) && Directory.Exists(dir)) ? Path.Combine(dir, BrokenReportFileName) : null;
        }

        /// <summary>BuildPath, but returns null instead of throwing when BUILD_PATH isn't set.</summary>
        private static string BuildPathSafe()
        {
            try { return BuildPath; } catch { return null; }
        }

        private static string LocateBuiltCatalog()
        {
            string buildPath = Addressables.BuildPath;
            if (Directory.Exists(buildPath))
            {
                var found = Directory.GetFiles(buildPath, "catalog*.*", SearchOption.AllDirectories)
                                     .FirstOrDefault(IsCatalogFile);
                if (found != null)
                    return found;
            }

            return null;
        }

        private static bool IsCatalogFile(string path)
        {
            string f = Path.GetFileName(path);
            return f.StartsWith("catalog", StringComparison.OrdinalIgnoreCase)
                   && (f.EndsWith(".bin", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".json", StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>
        /// Purge the ScriptableBuildPipeline incremental cache so the next content build is fully
        /// from-scratch. It is this cache's staleness that produces a catalog omitting new entries.
        /// </summary>
        private static void PurgeAddressablesBuildCache()
        {
            try
            {
                UnityEditor.Build.Pipeline.Utilities.BuildCache.PurgeCache(false);
                Log("✓ Purged ScriptableBuildPipeline BuildCache");
            }
            catch (Exception ex)
            {
                LogWarning($"Could not purge SBP BuildCache (continuing with CleanPlayerContent only): {ex.Message}");
            }
        }

        private static bool CleanAddressablesRequested()
        {
            return string.Equals(GetVariable("CLEAN_ADDRESSABLES", "false"), "true", StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>
        /// True when Addressables should participate in this build — BUILD_ADDRESSABLES (default
        /// "true") is on and the project actually has Addressables settings.
        /// </summary>
        private static bool AddressablesEnabledForBuild()
        {
            if (!string.Equals(GetVariable("BUILD_ADDRESSABLES", "true"), "true", StringComparison.OrdinalIgnoreCase))
            {
                Log("BUILD_ADDRESSABLES=false — skipping Addressables content build/validation");
                return false;
            }

            if (!AddressableAssetSettingsDefaultObject.SettingsExists)
            {
                Log("No AddressableAssetSettings configured — skipping Addressables content build/validation");
                return false;
            }

            return true;
        }

        /// <summary>
        /// Whether the player build will build Addressables content itself (mirrors the Addressables
        /// package's own ShouldBuildAddressablesForPlayerBuild). When true the pipeline must not build
        /// a second copy; when false the pipeline owns the content build.
        /// </summary>
        private static bool SettingBuildsAddressablesWithPlayer()
        {
            var settings = AddressableAssetSettingsDefaultObject.Settings;
            switch (settings.BuildAddressablesWithPlayerBuild)
            {
                case AddressableAssetSettings.PlayerBuildOption.DoNotBuildWithPlayer:
                    return false;
                case AddressableAssetSettings.PlayerBuildOption.BuildWithPlayer:
                    return true;
                default: // PreferencesValue — mirror the package's EditorPref lookup
                    return EditorPrefs.GetBool("Addressables.BuildAddressablesWithPlayerBuild", true);
            }
        }

        private static void ListBuildOutput(string outputPath)
        {
            Log("========== BUILD OUTPUT ==========");

            // Get the directory containing the output
            string outputDir = Path.GetDirectoryName(outputPath);
            if (string.IsNullOrEmpty(outputDir) || !Directory.Exists(outputDir))
            {
                Log($"Output directory not found: {outputDir}");
                return;
            }

            // List all files
            var files = Directory.GetFiles(outputDir, "*", SearchOption.TopDirectoryOnly);
            foreach (var file in files)
            {
                var info = new FileInfo(file);
                Log($"  FILE: {info.Name} ({info.Length / 1024.0 / 1024.0:F2} MB)");
            }

            // List all directories
            var dirs = Directory.GetDirectories(outputDir, "*", SearchOption.TopDirectoryOnly);
            foreach (var dir in dirs)
            {
                var dirInfo = new DirectoryInfo(dir);
                long size = GetDirectorySize(dirInfo);
                Log($"  DIR:  {dirInfo.Name}/ ({size / 1024.0 / 1024.0:F2} MB)");
            }

            Log("==================================");
        }

        private static long GetDirectorySize(DirectoryInfo dir)
        {
            long size = 0;
            try
            {
                foreach (var file in dir.GetFiles("*", SearchOption.AllDirectories))
                {
                    size += file.Length;
                }
            }
            catch { }
            return size;
        }

        private static void ReportBuildErrors(BuildReport report)
        {
            int errorCount = 0;
            int exceptionCount = 0;

            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception)
                        exceptionCount++;
                    else if (msg.type == LogType.Error)
                        errorCount++;
                }
            }

            LogError("========== BUILD FAILED ==========");
            LogError($"Result: {report.summary.result} | Platform: {report.summary.platform} | Errors: {errorCount} | Exceptions: {exceptionCount}");

            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception || msg.type == LogType.Error)
                    {
                        // Gradle errors arrive as the entire gradle stdout in one message,
                        // where the real cause ("Manifest merger failed", etc.) is buried far
                        // below hundreds of harmless "Configure project" warnings. Surface the
                        // "FAILURE: Build failed" → "BUILD FAILED" block up front when present.
                        string content = ExtractGradleFailure(msg.content) ?? msg.content;
                        LogError($"[{step.name}] [{msg.type}] {content}");
                    }
                }
            }

            // Replay captured errors that aren't already in the build report
            var reportedMessages = new System.Collections.Generic.HashSet<string>();
            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception || msg.type == LogType.Error)
                    {
                        reportedMessages.Add(msg.content);
                    }
                }
            }

            var extraErrors = capturedErrors.Where(e => {
                // Strip the "[Error] " or "[Exception] " prefix for comparison
                int idx = e.IndexOf("] ");
                string content = idx >= 0 ? e.Substring(idx + 2) : e;
                return !reportedMessages.Contains(content);
            }).ToList();

            if (extraErrors.Count > 0)
            {
                LogError("---------- Captured Errors ----------");
                foreach (var error in extraErrors)
                {
                    LogError(error);
                }
            }

            if (errorCount == 0 && exceptionCount == 0 && extraErrors.Count == 0)
            {
                LogError("No error details found — check the console log above for the actual error.");
            }

            LogError("==================================");
        }

        /// <summary>
        /// If the message contains a Gradle failure banner, return just the failure block
        /// ("FAILURE: Build failed with an exception." through the "BUILD FAILED in <n>s"
        /// line) with a header. This is where the real cause is printed. Returns null when
        /// no banner is present so the caller falls back to the original content.
        /// </summary>
        private static string ExtractGradleFailure(string content)
        {
            if (string.IsNullOrEmpty(content)) return null;

            int start = content.IndexOf("FAILURE: Build failed with an exception", StringComparison.Ordinal);
            if (start < 0) return null;

            int end = content.IndexOf("BUILD FAILED", start, StringComparison.Ordinal);
            // Include the "BUILD FAILED in <n>s" line itself if we found it.
            if (end > start)
            {
                int eol = content.IndexOf('\n', end);
                end = eol > end ? eol : content.Length;
            }
            else
            {
                end = content.Length;
            }

            string block = content.Substring(start, end - start).Trim();
            return $"Gradle build failed — extracted cause (full output above/in console):\n{block}";
        }

        private static void CaptureLogErrors(string message, string stackTrace, LogType type)
        {
            if (type == LogType.Error || type == LogType.Exception)
            {
                var entry = $"[{type}] {message}";
                if (!string.IsNullOrEmpty(stackTrace))
                    entry += $"\n{stackTrace}";
                capturedErrors.Add(entry);
            }
        }

        #region Logging

        private static string GetCallerPrefix()
        {
            var stackTrace = new System.Diagnostics.StackTrace();
            var frame = stackTrace.GetFrame(2);
            var method = frame?.GetMethod();

            if (method != null)
            {
                var className = method.DeclaringType?.Name ?? "Unknown";
                var methodName = method.Name;
                return $"[{className}.{methodName}]";
            }

            return "[Pipeline]";
        }

        public static void Log(string message)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", $"{GetCallerPrefix()} {message}");
        }

        public static void LogError(string message)
        {
            Debug.LogFormat(LogType.Error, LogOption.NoStacktrace, null, "{0}", $"{GetCallerPrefix()} {message}");
        }

        public static void LogWarning(string message)
        {
            string formattedMessage = $"{GetCallerPrefix()} {message}";
            Debug.LogWarning(formattedMessage);
        }

        #endregion
    }

}
