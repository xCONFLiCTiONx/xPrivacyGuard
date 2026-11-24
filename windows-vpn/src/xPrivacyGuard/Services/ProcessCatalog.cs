using System.Diagnostics;
using System.IO;
using xPrivacyGuard.Models;

namespace xPrivacyGuard.Services;

public sealed class ProcessCatalog
{
    public IReadOnlyList<AppEntry> GetApplications(IEnumerable<string>? savedPaths = null)
    {
        var entries = new Dictionary<string, AppEntry>(StringComparer.OrdinalIgnoreCase);

        foreach (var process in Process.GetProcesses())
        {
            try
            {
                using (process)
                {
                    var path = process.MainModule?.FileName;
                    if (string.IsNullOrWhiteSpace(path) || !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                        continue;

                    var name = Path.GetFileNameWithoutExtension(path);
                    if (string.IsNullOrWhiteSpace(name) || entries.ContainsKey(path))
                        continue;

                    entries[path] = new AppEntry
                    {
                        Name = name,
                        ProcessName = process.ProcessName,
                        ExecutablePath = path,
                        IsRunning = true
                    };
                }
            }
            catch (Exception ex) when (ex is System.ComponentModel.Win32Exception
                                           or InvalidOperationException
                                           or System.Security.SecurityException
                                           or NotSupportedException
                                           or ArgumentException)
            {
                // Protected, elevated, and short-lived processes may not expose their image path.
            }
        }

        if (savedPaths is not null)
        {
            foreach (var saved in savedPaths)
            {
                if (string.IsNullOrWhiteSpace(saved) || entries.ContainsKey(saved))
                    continue;
                var name = Path.GetFileNameWithoutExtension(saved);
                if (string.IsNullOrWhiteSpace(name))
                    continue;
                entries[saved] = new AppEntry
                {
                    Name = name,
                    ProcessName = name,
                    ExecutablePath = saved,
                    IsRunning = false,
                    IncludedInWarp = true
                };
            }
        }

        return entries.Values
            .OrderByDescending(x => x.IncludedInWarp)
            .ThenBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToArray();
    }
}
