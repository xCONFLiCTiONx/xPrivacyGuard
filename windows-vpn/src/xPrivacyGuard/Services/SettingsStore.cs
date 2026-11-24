using System.IO;
using System.Text.Json;
using xPrivacyGuard.Models;

namespace xPrivacyGuard.Services;

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public string AppDataDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "xCONFLiCTiONx", "xPrivacyGuard");

    public string IdentityDirectory => Path.Combine(AppDataDirectory, "aether-identity");
    private string SettingsPath => Path.Combine(AppDataDirectory, "settings.json");

    public SettingsStore()
    {
        TryMigrateLegacyAppData();
    }

    private void TryMigrateLegacyAppData()
    {
        try
        {
            if (File.Exists(SettingsPath)) return;
            var legacyDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "xCONFLiCTiONx", "xPrivacyVPN");
            if (!Directory.Exists(legacyDir)) return;
            Directory.CreateDirectory(AppDataDirectory);
            foreach (var dir in Directory.GetDirectories(legacyDir, "*", SearchOption.AllDirectories))
            {
                Directory.CreateDirectory(dir.Replace(legacyDir, AppDataDirectory, StringComparison.OrdinalIgnoreCase));
            }
            foreach (var file in Directory.GetFiles(legacyDir, "*", SearchOption.AllDirectories))
            {
                var dest = file.Replace(legacyDir, AppDataDirectory, StringComparison.OrdinalIgnoreCase);
                if (!File.Exists(dest)) File.Copy(file, dest);
            }
        }
        catch { }
    }

    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return new AppSettings();
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsPath), JsonOptions)
                   ?? new AppSettings();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return new AppSettings();
        }
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(AppDataDirectory);
        var tmp = SettingsPath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(settings, JsonOptions));
        File.Move(tmp, SettingsPath, overwrite: true);
    }

    public void ExportBackup(string destinationFile, AppSettings settings, IEnumerable<string> excludedApps)
    {
        var backup = new SettingsBackupDocument
        {
            Version = 1,
            ExportedAtUtc = DateTimeOffset.UtcNow,
            Settings = settings,
            IncludedApplications = excludedApps.ToArray()
        };
        File.WriteAllText(destinationFile, JsonSerializer.Serialize(backup, JsonOptions));
    }

    public SettingsBackupDocument? ImportBackup(string sourceFile)
    {
        if (!File.Exists(sourceFile)) return null;
        return JsonSerializer.Deserialize<SettingsBackupDocument>(File.ReadAllText(sourceFile), JsonOptions);
    }
}

public sealed class SettingsBackupDocument
{
    public int Version { get; set; } = 1;
    public DateTimeOffset ExportedAtUtc { get; set; }
    public AppSettings Settings { get; set; } = new();
    public string[] IncludedApplications { get; set; } = [];
    public FilterSourceBackup[] FilterSources { get; set; } = [];
}

public sealed class FilterSourceBackup
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public FilterSourceType Type { get; set; }
    public string UrlOrPath { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public string Content { get; set; } = "";
}
