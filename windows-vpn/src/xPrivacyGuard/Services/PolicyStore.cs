using System.IO;
using System.Text.Json;

namespace xPrivacyGuard.Services;

public sealed class PolicyStore
{
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "xCONFLiCTiONx", "xPrivacyGuard");

    private string FilePath => Path.Combine(_directory, "application-policy.json");

    public HashSet<string> LoadIncludedApplications()
    {
        try
        {
            if (!File.Exists(FilePath))
                return new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            var paths = JsonSerializer.Deserialize<string[]>(File.ReadAllText(FilePath)) ?? [];
            return new HashSet<string>(paths, StringComparer.OrdinalIgnoreCase);
        }
        catch (IOException)
        {
            return new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }
        catch (JsonException)
        {
            return new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }
    }

    public void SaveIncludedApplications(IEnumerable<string> executablePaths)
    {
        Directory.CreateDirectory(_directory);
        var paths = executablePaths
            .Where(Path.IsPathFullyQualified)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var temporaryPath = FilePath + ".tmp";
        File.WriteAllText(temporaryPath, JsonSerializer.Serialize(paths, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temporaryPath, FilePath, overwrite: true);
    }
}
