using System.Diagnostics;
using System.Drawing;
using System.Windows;
using xPrivacyGuard.Services;
using Forms = System.Windows.Forms;
using MessageBox = System.Windows.MessageBox;

namespace xPrivacyGuard;

public partial class App : System.Windows.Application
{
    private Forms.NotifyIcon? _trayIcon;
    private MainWindow? _mainWindow;
    private Icon? _appIcon;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Enforce Administrator elevation even if launched from a non-elevated shell/debugger.
        if (!WindowsSecurityChecker.IsAdministrator())
        {
            var exe = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(exe))
            {
                try
                {
                    var args = string.Join(" ", e.Args.Select(a => $"\"{a}\""));
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = exe,
                        Arguments = args,
                        UseShellExecute = true,
                        Verb = "runas"
                    });
                }
                catch
                {
                    MessageBox.Show(
                        "xPrivacyGuard requires Administrator privileges to manage the Wintun adapter and Windows Filtering Platform (WFP).",
                        "xPrivacyGuard — Administrator Required",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                }
            }
            Shutdown();
            return;
        }

        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        LoadTrayIcons();

        _mainWindow = new MainWindow();
        _mainWindow.RequestExit += ExitApplication;
        _mainWindow.TrayStatusChanged += UpdateTrayState;
        _mainWindow.ShowInTaskbar = false;
        CreateTrayIcon();

        var startHidden = e.Args.Any(x => string.Equals(x, "--background", StringComparison.OrdinalIgnoreCase));
        if (!startHidden)
        {
            _mainWindow.Show();
            _mainWindow.ShowInTaskbar = true;
            _mainWindow.Activate();
        }
        else
        {
            _mainWindow.StartAutoConnectIfEnabled();
        }
    }

    private void LoadTrayIcons()
    {
        _appIcon = LoadResourceIcon("Assets/xprivacyguard.ico") ?? SystemIcons.Shield;
    }

    private static Icon? LoadResourceIcon(string relativeUri)
    {
        try
        {
            var info = GetResourceStream(new Uri($"pack://application:,,,/{relativeUri}", UriKind.Absolute));
            if (info?.Stream is null) return null;
            using var stream = info.Stream;
            return new Icon(stream);
        }
        catch
        {
            return null;
        }
    }

    private void CreateTrayIcon()
    {
        var menu = new Forms.ContextMenuStrip
        {
            ShowImageMargin = false,
            BackColor = Color.FromArgb(0x15, 0x15, 0x18),
            ForeColor = Color.FromArgb(0xF4, 0xF4, 0xF5),
            Font = new Font("Segoe UI", 9.5f),
            Padding = new Forms.Padding(4),
            Renderer = new DarkTrayMenuRenderer()
        };

        var openItem = new Forms.ToolStripMenuItem("Open xPrivacyGuard", null, (_, _) => ShowMainWindow())
        {
            ForeColor = Color.FromArgb(0xF4, 0xF4, 0xF5),
            Padding = new Forms.Padding(8, 4, 12, 4)
        };
        var toggleItem = new Forms.ToolStripMenuItem("Toggle Connection", null, (_, _) => _mainWindow?.TriggerToggleConnectionFromTray())
        {
            ForeColor = Color.FromArgb(0xF3, 0x80, 0x20),
            Padding = new Forms.Padding(8, 4, 12, 4)
        };
        var sep = new Forms.ToolStripSeparator();
        var exitItem = new Forms.ToolStripMenuItem("Exit", null, (_, _) => ExitApplication())
        {
            ForeColor = Color.FromArgb(0xA1, 0xA1, 0xAA),
            Padding = new Forms.Padding(8, 4, 12, 4)
        };

        menu.Items.Add(openItem);
        menu.Items.Add(toggleItem);
        menu.Items.Add(sep);
        menu.Items.Add(exitItem);

        _trayIcon = new Forms.NotifyIcon
        {
            Icon = _appIcon ?? SystemIcons.Shield,
            Text = "xPrivacyGuard — Disconnected",
            ContextMenuStrip = menu,
            Visible = true
        };
        _trayIcon.DoubleClick += (_, _) => ShowMainWindow();
    }

    private void UpdateTrayState(string status, bool isConnected)
    {
        if (_trayIcon is null) return;
        var text = $"xPrivacyGuard — {status}";
        _trayIcon.Text = text.Length > 63 ? text[..63] : text;
    }

    private void ShowMainWindow()
    {
        if (_mainWindow is null) return;
        _mainWindow.Show();
        _mainWindow.ShowInTaskbar = true;
        if (_mainWindow.WindowState == WindowState.Minimized)
            _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Activate();
    }

    private void ExitApplication()
    {
        if (_mainWindow is not null)
        {
            _mainWindow.AllowClose = true;
            _mainWindow.Close();
        }
        _trayIcon?.Dispose();
        _trayIcon = null;
        _appIcon?.Dispose();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _trayIcon?.Dispose();
        _appIcon?.Dispose();
        base.OnExit(e);
    }

    private sealed class DarkTrayMenuRenderer : Forms.ToolStripProfessionalRenderer
    {
        public DarkTrayMenuRenderer() : base(new DarkTrayColorTable()) { }

        protected override void OnRenderMenuItemBackground(Forms.ToolStripItemRenderEventArgs e)
        {
            var rect = new Rectangle(2, 1, e.Item.Width - 4, e.Item.Height - 2);
            if (e.Item.Selected)
            {
                using var fill = new SolidBrush(Color.FromArgb(0x2B, 0x1B, 0x0E));
                using var border = new Pen(Color.FromArgb(0xF3, 0x80, 0x20));
                e.Graphics.FillRectangle(fill, rect);
                e.Graphics.DrawRectangle(border, rect);
            }
            else
            {
                using var fill = new SolidBrush(Color.FromArgb(0x15, 0x15, 0x18));
                e.Graphics.FillRectangle(fill, new Rectangle(System.Drawing.Point.Empty, e.Item.Size));
            }
        }

        protected override void OnRenderSeparator(Forms.ToolStripSeparatorRenderEventArgs e)
        {
            var y = e.Item.Height / 2;
            using var pen = new Pen(Color.FromArgb(0x2C, 0x2C, 0x33));
            e.Graphics.DrawLine(pen, 8, y, e.Item.Width - 8, y);
        }

        protected override void OnRenderToolStripBorder(Forms.ToolStripRenderEventArgs e)
        {
            using var pen = new Pen(Color.FromArgb(0x2C, 0x2C, 0x33));
            e.Graphics.DrawRectangle(pen, new Rectangle(0, 0, e.AffectedBounds.Width - 1, e.AffectedBounds.Height - 1));
        }
    }

    private sealed class DarkTrayColorTable : Forms.ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Color.FromArgb(0x15, 0x15, 0x18);
        public override Color MenuBorder => Color.FromArgb(0x2C, 0x2C, 0x33);
        public override Color MenuItemBorder => Color.FromArgb(0xF3, 0x80, 0x20);
        public override Color MenuItemSelected => Color.FromArgb(0x2B, 0x1B, 0x0E);
        public override Color ImageMarginGradientBegin => Color.FromArgb(0x15, 0x15, 0x18);
        public override Color ImageMarginGradientMiddle => Color.FromArgb(0x15, 0x15, 0x18);
        public override Color ImageMarginGradientEnd => Color.FromArgb(0x15, 0x15, 0x18);
        public override Color SeparatorDark => Color.FromArgb(0x2C, 0x2C, 0x33);
        public override Color SeparatorLight => Color.FromArgb(0x2C, 0x2C, 0x33);
    }
}
