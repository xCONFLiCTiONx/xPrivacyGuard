using System.IO;
using System.Text;

namespace xPrivacyGuard.Services;

/// <summary>
/// Thread-safe, size-rotated file logger for VPN lifecycle, authentication,
/// gateway probing, tunnel setup, and sleep/resume recovery diagnostics.
/// </summary>
public static class VpnLogger
{
    private const long MaxLogBytes = 2 * 1024 * 1024; // 2 MiB
    private static readonly object Sync = new();

    public static string LogDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "xCONFLiCTiONx", "xPrivacyGuard");

    public static string LogFilePath => Path.Combine(LogDirectory, "vpn.log");

    public static void Info(string category, string message) => Write("INFO", category, message);
    public static void Warn(string category, string message) => Write("WARN", category, message);
    public static void Error(string category, string message, Exception? ex = null)
    {
        var full = ex is null ? message : $"{message} | {ex.GetType().Name}: {ex.Message}";
        Write("ERROR", category, full);
    }

    private static void Write(string level, string category, string message)
    {
        var line = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} [{level}] [{category}] {message}{Environment.NewLine}";
        lock (Sync)
        {
            try
            {
                Directory.CreateDirectory(LogDirectory);
                RotateIfNeeded();
                File.AppendAllText(LogFilePath, line, Encoding.UTF8);
            }
            catch
            {
                // Logging must never crash the VPN engine.
            }
        }
    }

    private static void RotateIfNeeded()
    {
        if (!File.Exists(LogFilePath)) return;
        var info = new FileInfo(LogFilePath);
        if (info.Length < MaxLogBytes) return;

        var backup = LogFilePath + ".1";
        if (File.Exists(backup))
            File.Delete(backup);
        File.Move(LogFilePath, backup, overwrite: true);
    }
}
