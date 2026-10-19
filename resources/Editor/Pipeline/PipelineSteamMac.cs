using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEngine;

namespace ODDFramework
{
    public static class PipelineSteamMac
    {
        private static void ApplyDebugSettings()
        {
            EditorUserBuildSettings.allowDebugging = true;
            EditorUserBuildSettings.connectProfiler = true;
            PlayerSettings.enableInternalProfiler = true;

            PlayerSettings.stripEngineCode = false;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.Full);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.Full);

            // IL2CPP like Release, so stripping/AOT problems show up in dev builds too
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.Standalone, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.Standalone, Il2CppCodeGeneration.OptimizeSpeed);
        }

        private static void ApplyReleaseSettings()
        {
            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            PlayerSettings.stripEngineCode = true;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.ScriptOnly);

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.Standalone, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.Standalone, Il2CppCodeGeneration.OptimizeSpeed);
        }

        public static void PrepareDebug()
        {
            Pipeline.Prepare("DEVELOPMENT_BUILD,STEAM_BUILD");
        }

        public static void Debug()
        {
            ApplyDebugSettings();
            Pipeline.Build(GetOutputPath("Debug"), BuildOptions.CompressWithLz4HC | BuildOptions.Development);
        }

        public static void PrepareRelease()
        {
            Pipeline.Prepare("STEAM_BUILD");
        }

        public static void Release()
        {
            ApplyReleaseSettings();
            Pipeline.Build(GetOutputPath("Release"), BuildOptions.CompressWithLz4HC);
        }

        private static string GetOutputPath(string buildType)
        {
            string productName = PlayerSettings.productName.Replace(" ", "");
            // macOS builds produce a .app bundle
            string filename = $"output/{productName}.app";
            return Path.Combine(Pipeline.BuildPath, filename);
        }
    }
}
