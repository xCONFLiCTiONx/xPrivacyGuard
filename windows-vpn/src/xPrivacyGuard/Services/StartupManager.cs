using System.Diagnostics;
using Microsoft.Win32;

namespace xPrivacyGuard.Services;

/// <summary>
/// Manages elevated startup-at-logon via Windows Task Scheduler (RunLevel Highest),
/// because Windows ignores HKCU\...\Run for requireAdministrator executables.
/// </summary>
public sealed class StartupManager
{
    private const string TaskName = "xPrivacyGuard";
    private const string LegacyRunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public bool IsEnabled()
    {
        var rc = RunSchtasks($"/Query /TN \"{TaskName}\"");
        return rc == 0;
    }

    public void SetEnabled(bool enabled)
    {
        // Clean up any legacy HKCU Run entry
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(LegacyRunKey, writable: true);
            key?.DeleteValue(TaskName, throwOnMissingValue: false);
        }
        catch { }

        if (enabled)
        {
            var executable = Environment.ProcessPath
                ?? throw new InvalidOperationException("Cannot determine the current executable path.");
            var tr = $"\\\"{executable}\\\" --background";
            var rc = RunSchtasks($"/Create /TN \"{TaskName}\" /TR \"{tr}\" /SC ONLOGON /RL HIGHEST /F");
            if (rc != 0)
                throw new InvalidOperationException($"Could not register elevated startup task (schtasks exit code {rc}).");
        }
        else
        {
            RunSchtasks($"/Delete /TN \"{TaskName}\" /F");
        }
    }

    private static int RunSchtasks(string arguments)
    {
        using var proc = Process.Start(new ProcessStartInfo
        {
            FileName = "schtasks.exe",
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        });
        if (proc is null) return -1;
        proc.WaitForExit(5000);
        return proc.ExitCode;
    }
}
