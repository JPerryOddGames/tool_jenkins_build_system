using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEngine;
using UnityEditor.Build.Reporting;

namespace ODDFramework
{

    public static class PipelineAmazon
    {
        private static void ConfigureAndroidKeystore()
        {
            string keystoreName = System.Environment.GetEnvironmentVariable("ANDROID_KEYSTORE_NAME");
            string keystorePass = System.Environment.GetEnvironmentVariable("ANDROID_KEYSTORE_PASS");
            string keyaliasName = System.Environment.GetEnvironmentVariable("ANDROID_KEYALIAS_NAME");
            string keyaliasPass = System.Environment.GetEnvironmentVariable("ANDROID_KEYALIAS_PASS");

            if (!string.IsNullOrEmpty(keystoreName))
            {
                Pipeline.Log($"Configuring Android keystore from environment variables");
                Pipeline.Log($"  Keystore path: {keystoreName}");
                Pipeline.Log($"  Key alias: {keyaliasName}");

                // Enable custom keystore signing
                PlayerSettings.Android.useCustomKeystore = true;

                // Set keystore file path
                PlayerSettings.Android.keystoreName = keystoreName;

                // Set keystore password
                if (!string.IsNullOrEmpty(keystorePass))
                    PlayerSettings.Android.keystorePass = keystorePass;

                // Set key alias name
                if (!string.IsNullOrEmpty(keyaliasName))
                    PlayerSettings.Android.keyaliasName = keyaliasName;

                // Set key alias password
                if (!string.IsNullOrEmpty(keyaliasPass))
                    PlayerSettings.Android.keyaliasPass = keyaliasPass;

                Pipeline.Log($"✓ Android keystore configured:");
                Pipeline.Log($"  useCustomKeystore: {PlayerSettings.Android.useCustomKeystore}");
                Pipeline.Log($"  keystoreName: {PlayerSettings.Android.keystoreName}");
                Pipeline.Log($"  keyaliasName: {PlayerSettings.Android.keyaliasName}");

                // Verify the keystore file exists
                if (!File.Exists(keystoreName))
                {
                    Pipeline.LogError($"Keystore file not found at: {keystoreName}");
                    throw new System.Exception($"Keystore file not found: {keystoreName}");
                }
                else
                {
                    Pipeline.Log($"✓ Keystore file verified at: {keystoreName}");
                }
            }
            else
            {
                Pipeline.LogWarning("No Android keystore environment variables provided - will use default signing");
            }
        }

        private static void ApplyReleaseSettings()
        {
            // Amazon App Store requires APK, not AAB
            EditorUserBuildSettings.buildAppBundle = false;
            PlayerSettings.Android.buildApkPerCpuArchitecture = false;

            // ARMv7 only — avoids mismatched native library warnings on Amazon and maximizes Fire device compatibility
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARMv7;

            // ASTC — all Fire devices support it, reduces APK size
            PlayerSettings.Android.textureCompressionFormats = new[] { TextureCompressionFormat.ASTC };

            // Disable split binary for Amazon builds
            PlayerSettings.Android.splitApplicationBinary = false;

            PlayerSettings.stripEngineCode = true;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.ScriptOnly);

            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Android, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.Android, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.Android, Il2CppCodeGeneration.OptimizeSize);
            PlayerSettings.SetIl2CppStacktraceInformation(NamedBuildTarget.Android, Il2CppStacktraceInformation.MethodFileLineNumber);

            PlayerSettings.Android.minifyRelease = true;
            PlayerSettings.Android.minifyDebug = false;

            UnityEditor.Android.UserBuildSettings.DebugSymbols.level = Unity.Android.Types.DebugSymbolLevel.SymbolTable;
        }

        public static void PrepareDebug()
        {
            ConfigureAndroidKeystore();
            SetAndroidVersionCode();
            Pipeline.Prepare("UNITY_AMAZON,DEVELOPMENT_BUILD,ODDGAMES_FORCE_ENABLE_ODD_LOGS");
        }

        public static void Debug()
        {
            ConfigureAndroidKeystore();
            ApplyDebugSettings();
            Pipeline.Build(GetOutputPath("Debug", ".apk"), BuildOptions.CompressWithLz4HC | BuildOptions.Development);
        }

        private static void ApplyDebugSettings()
        {
            // Amazon App Store requires APK, not AAB
            EditorUserBuildSettings.buildAppBundle = false;
            PlayerSettings.Android.buildApkPerCpuArchitecture = false;

            // ARMv7 only — avoids mismatched native library warnings on Amazon and maximizes Fire device compatibility
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARMv7;

            // ASTC — all Fire devices support it, reduces APK size
            PlayerSettings.Android.textureCompressionFormats = new[] { TextureCompressionFormat.ASTC };

            // Disable split binary for development builds for faster iteration
            PlayerSettings.Android.splitApplicationBinary = false;

            PlayerSettings.stripEngineCode = false;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.Full);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.Full);

            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            // IL2CPP like Release, so stripping/AOT problems show up in dev builds too.
            // OptimizeSpeed generates less aggressively than OptimizeSize, so the build is faster.
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Android, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.Android, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.Android, Il2CppCodeGeneration.OptimizeSpeed);
            PlayerSettings.SetIl2CppStacktraceInformation(NamedBuildTarget.Android, Il2CppStacktraceInformation.MethodFileLineNumber);

            PlayerSettings.Android.minifyRelease = false;
            PlayerSettings.Android.minifyDebug = false;

            UnityEditor.Android.UserBuildSettings.DebugSymbols.level =  Unity.Android.Types.DebugSymbolLevel.Full;
        }

        public static void PrepareRelease()
        {
            ConfigureAndroidKeystore();
            SetAndroidVersionCode();
            Pipeline.Prepare("UNITY_AMAZON,ODDGAMES_FORCE_ENABLE_ODD_LOGS");
        }

        public static void Release()
        {
            ConfigureAndroidKeystore();
            ApplyReleaseSettings();
            Pipeline.Build(GetOutputPath("Release", ".apk"), BuildOptions.CompressWithLz4HC);
        }

        private static string GetOutputPath(string buildType, string extension)
        {
            // Use full VERSION env var (includes branch suffix, e.g. 3.94.13640-main)
            string version = Pipeline.GetVariable("VERSION", PlayerSettings.bundleVersion);
            string filename = $"{Pipeline.OutputPrefix}_{version}_{buildType}_Amazon{extension}";
            return Path.Combine(Pipeline.BuildPath, filename);
        }

        private static void SetAndroidVersionCode()
        {
            string versionCodeString = Pipeline.GetVariable("ANDROID_VERSION_CODE");
            if (!string.IsNullOrEmpty(versionCodeString) && int.TryParse(versionCodeString, out int versionCode))
            {
                const int MAX_VERSION_CODE = 200000000;

                // Safety check: versionCode must not exceed 200 million
                if (versionCode > MAX_VERSION_CODE)
                {
                    string errorMsg = $"Android versionCode {versionCode:N0} exceeds the maximum allowed value of {MAX_VERSION_CODE:N0}.\n" +
                                    $"You may need to adjust VERSION_CODE_BASE in the Jenkinsfile.";
                    Pipeline.LogError(errorMsg);
                    throw new System.Exception(errorMsg);
                }

                PlayerSettings.Android.bundleVersionCode = versionCode;
                Pipeline.Log($"✓ versionCode: {versionCode:N0}");
            }
            else
            {
                Pipeline.LogWarning("ANDROID_VERSION_CODE environment variable not set or invalid");
            }
        }
    }
}
