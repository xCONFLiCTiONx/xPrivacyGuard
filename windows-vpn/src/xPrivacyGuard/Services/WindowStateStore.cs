using System.IO;
using System.Text.Json;
using System.Windows;

namespace xPrivacyGuard.Services;

public sealed class WindowStateStore
{
    private const int LayoutVersion = 2; // Reset old oversized 1680x1050 state
    private const double DefaultWidth = 960;
    private const double DefaultHeight = 640;

    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "xCONFLiCTiONx", "xPrivacyGuard");

    private string FilePath => Path.Combine(_directory, "window-state.json");

    public void Restore(Window window)
    {
        try
        {
            var workArea = SystemParameters.WorkArea;
            var maxW = Math.Max(window.MinWidth, workArea.Width - 40);
            var maxH = Math.Max(window.MinHeight, workArea.Height - 40);

            if (!File.Exists(FilePath))
            {
                CenterWithTabletSize(window, workArea, maxW, maxH);
                return;
            }

            var saved = JsonSerializer.Deserialize<SavedWindowState>(File.ReadAllText(FilePath));
            if (saved is null || saved.Version != LayoutVersion)
            {
                CenterWithTabletSize(window, workArea, maxW, maxH);
                return;
            }

            window.WindowStartupLocation = WindowStartupLocation.Manual;
            window.Width = Math.Clamp(saved.Width, window.MinWidth, maxW);
            window.Height = Math.Clamp(saved.Height, window.MinHeight, maxH);
            window.Left = saved.Left;
            window.Top = saved.Top;
            ClampStrictlyInsideWorkArea(window, workArea);
            window.WindowState = saved.IsMaximized ? WindowState.Maximized : WindowState.Normal;
        }
        catch (Exception ex) when (ex is IOException or JsonException or ArgumentException)
        {
            var workArea = SystemParameters.WorkArea;
            CenterWithTabletSize(window, workArea, Math.Max(window.MinWidth, workArea.Width - 40), Math.Max(window.MinHeight, workArea.Height - 40));
        }
    }

    public void Save(Window window)
    {
        try
        {
            Directory.CreateDirectory(_directory);
            var bounds = window.WindowState == WindowState.Normal
                ? new Rect(window.Left, window.Top, window.Width, window.Height)
                : window.RestoreBounds;

            if (bounds.IsEmpty
                || !double.IsFinite(bounds.Left)
                || !double.IsFinite(bounds.Top)
                || !double.IsFinite(bounds.Width)
                || !double.IsFinite(bounds.Height)
                || bounds.Width <= 0
                || bounds.Height <= 0)
            {
                return;
            }
            var saved = new SavedWindowState
            {
                Version = LayoutVersion,
                Left = bounds.Left,
                Top = bounds.Top,
                Width = bounds.Width,
                Height = bounds.Height,
                IsMaximized = window.WindowState == WindowState.Maximized
            };
            var temporaryPath = FilePath + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(saved, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temporaryPath, FilePath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Best-effort persistence.
        }
    }

    private static void CenterWithTabletSize(Window window, Rect workArea, double maxW, double maxH)
    {
        window.WindowStartupLocation = WindowStartupLocation.Manual;
        window.Width = Math.Clamp(DefaultWidth, window.MinWidth, maxW);
        window.Height = Math.Clamp(DefaultHeight, window.MinHeight, maxH);
        window.Left = workArea.Left + (workArea.Width - window.Width) / 2;
        window.Top = workArea.Top + (workArea.Height - window.Height) / 2;
        window.WindowState = WindowState.Normal;
    }

    private static void ClampStrictlyInsideWorkArea(Window window, Rect workArea)
    {
        if (double.IsNaN(window.Left) || double.IsInfinity(window.Left)
            || window.Left < workArea.Left
            || window.Left + window.Width > workArea.Right)
        {
            window.Left = Math.Clamp(
                double.IsNaN(window.Left) ? workArea.Left + (workArea.Width - window.Width) / 2 : window.Left,
                workArea.Left,
                Math.Max(workArea.Left, workArea.Right - window.Width));
        }

        if (double.IsNaN(window.Top) || double.IsInfinity(window.Top)
            || window.Top < workArea.Top
            || window.Top + window.Height > workArea.Bottom)
        {
            window.Top = Math.Clamp(
                double.IsNaN(window.Top) ? workArea.Top + (workArea.Height - window.Height) / 2 : window.Top,
                workArea.Top,
                Math.Max(workArea.Top, workArea.Bottom - window.Height));
        }
    }

    private sealed class SavedWindowState
    {
        public int Version { get; init; }
        public double Left { get; init; }
        public double Top { get; init; }
        public double Width { get; init; } = DefaultWidth;
        public double Height { get; init; } = DefaultHeight;
        public bool IsMaximized { get; init; }
    }
}
