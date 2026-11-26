using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using xPrivacyGuard.Models;
using xPrivacyGuard.Native;
using xPrivacyGuard.Services;
using Forms = System.Windows.Forms;
using MessageBox = System.Windows.MessageBox;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using SaveFileDialog = Microsoft.Win32.SaveFileDialog;

namespace xPrivacyGuard;

public partial class MainWindow : Window
{
    private const int DwmwaUseImmersiveDarkMode = 20;
    private const int DwmwaBorderColor = 34;
    private const int DwmwaCaptionColor = 35;
    private const int DwmwaTextColor = 36;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attr, ref int attrValue, int attrSize);

    private readonly SettingsStore _settingsStore = new();
    private readonly WindowStateStore _windowStateStore = new();
    private readonly StartupManager _startupManager = new();
    private readonly VpnEngineService _vpnEngine;

    private AppSettings _settings = new();
    private readonly ObservableCollection<TrafficLogEntry> _trafficLog = [];
    private readonly ConcurrentQueue<TrafficLogEntry> _pendingTrafficLogs = new();
    private AetherEmailSignIn? _activeOtpSession;
    private bool _isInitializing = true;
    private int _autoConnectStarted;
    private readonly DispatcherTimer _telemetryTimer;

    public event Action? RequestExit;
    public event Action<string, TunnelStatus>? TrayStatusChanged;
    public bool AllowClose { get; set; }

    private static readonly BitmapImage AppBrandConnectingPng =
        new(new Uri("pack://application:,,,/Assets/xprivacyguard.png"));
    private static readonly BitmapImage AppBrandConnectedPng =
        new(new Uri("pack://application:,,,/Assets/xprivacyguard-connected.png"));
    private static readonly BitmapImage AppBrandDisconnectedPng =
        new(new Uri("pack://application:,,,/Assets/xprivacyguard-disconnected.png"));

    public MainWindow()
    {
        InitializeComponent();
        Icon = AppBrandDisconnectedPng;
        BrandIconImage.Source = AppBrandDisconnectedPng;
        _vpnEngine = new VpnEngineService(_settingsStore);
        _vpnEngine.StateChanged += OnVpnStateChanged;
        _vpnEngine.TrafficLogged += OnTrafficLogged;
        _vpnEngine.IdentityEnrolled += OnIdentityEnrolled;

        TrafficLogList.ItemsSource = _trafficLog;

        _windowStateStore.Restore(this);
        LoadAllState();

        _telemetryTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _telemetryTimer.Tick += (_, _) => RefreshTelemetryMetrics();
        _telemetryTimer.Start();

        _isInitializing = false;
        Loaded += MainWindow_Loaded;
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        ApplyDarkWindowChrome();
    }

    private void ApplyDarkWindowChrome()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == 0) return;

            var dark = 1;
            DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref dark, sizeof(int));

            var captionColor = 0x00181515; // #151518
            var borderColor = 0x00332C2C;  // #2C2C33
            var textColor = 0x00F5F4F4;    // #F4F4F5
            DwmSetWindowAttribute(hwnd, DwmwaCaptionColor, ref captionColor, sizeof(int));
            DwmSetWindowAttribute(hwnd, DwmwaBorderColor, ref borderColor, sizeof(int));
            DwmSetWindowAttribute(hwnd, DwmwaTextColor, ref textColor, sizeof(int));
        }
        catch { }
    }

    private void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        StartAutoConnectIfEnabled();
    }

    public async void StartAutoConnectIfEnabled()
    {
        if (Interlocked.Exchange(ref _autoConnectStarted, 1) != 0) return;
        if (_settings.AlwaysOnVpn && _settings.CloudflareOne.CanConnect)
        {
            try
            {
                await Task.Run(() => _vpnEngine.ConnectAsync(_settings));
            }
            catch (Exception ex)
            {
                StatusText.Text = $"Auto-connect failed: {ex.Message}";
            }
        }
    }

    private void LoadAllState()
    {
        _settings = _settingsStore.Load();

        if (AetherRawTunnel.IsNativeLibraryAvailable(out var verOrErr))
            NativeEngineText.Text = $"Aether Core: v{verOrErr}";
        else
            NativeEngineText.Text = "Aether Core: DLL missing";

        AdminStatusText.Text = WindowsSecurityChecker.IsAdministrator() ? "Elevated (Administrator)" : "Standard User";

        // Dashboard toggles
        DashAlwaysOnCheck.IsChecked = _settings.AlwaysOnVpn;
        DashKillSwitchCheck.IsChecked = _settings.KillSwitchEnabled;

        // Cloudflare One fields
        var cf = _settings.CloudflareOne;
        CfTeamBox.Text = cf.TeamName;
        CfEmailBox.Text = cf.UserEmail;
        CfClientIdBox.Text = cf.ServiceClientId;
        CfClientSecretBox.Password = cf.ServiceClientSecret;
        CfCustomPeerBox.Text = cf.CustomPeerEndpoint;
        CfPreferHttp2Check.IsChecked = cf.PreferHttp2;
        CfEnableEchCheck.IsChecked = cf.EnableEch;
        UpdateEnrolledSummaryUi();

        // DNS fields
        var dns = _settings.ZeroTrustDns;
        DnsV4PrimaryBox.Text = dns.Ipv4Primary;
        DnsV4SecondaryBox.Text = dns.Ipv4Secondary;
        DnsDohBox.Text = dns.DohEndpoint;
        DnsDotBox.Text = dns.DotEndpoint;
        DnsPrivateDomainBox.Text = dns.PrivateDomain;
        DnsPrivateServerBox.Text = dns.PrivateDnsServer;

        // Split Tunnel Exclusions
        var split = _settings.SplitTunnel;
        SplitNetflixCheck.IsChecked = split.BypassNetflix;
        SplitHuluCheck.IsChecked = split.BypassHulu;
        SplitPrimeVideoCheck.IsChecked = split.BypassPrimeVideo;
        SplitDisneyPlusCheck.IsChecked = split.BypassDisneyPlus;
        SplitMaxCheck.IsChecked = split.BypassMax;
        SplitAppleTvCheck.IsChecked = split.BypassAppleTv;
        SplitAppleOneCheck.IsChecked = split.BypassAppleOne;
        SplitPeacockCheck.IsChecked = split.BypassPeacock;
        SplitYouTubeCheck.IsChecked = split.BypassYouTube;
        SplitYouTubeTvCheck.IsChecked = split.BypassYouTubeTv;
        SplitSlingTvCheck.IsChecked = split.BypassSlingTv;
        SplitPlutoCheck.IsChecked = split.BypassPlutoTv;
        SplitParamountPlusCheck.IsChecked = split.BypassParamountPlus;
        SplitEspnPlusCheck.IsChecked = split.BypassEspnPlus;
        SplitSpotifyCheck.IsChecked = split.BypassSpotify;
        SplitTubiCheck.IsChecked = split.BypassTubi;
        SplitPhoneLinkCheck.IsChecked = split.BypassPhoneLink;
        SplitGamingCheck.IsChecked = split.BypassGamingClients;
        SplitDomainsBox.Text = string.Join(Environment.NewLine, split.CustomExcludedDomains);
        RefreshSplitAppsList();

        // Security & Settings
        XSecDomainBox.Text = _settings.XSecurityDomain;
        XSecApiKeyBox.Password = _settings.XSecurityApiKey;
        StartWithWindowsCheck.IsChecked = _startupManager.IsEnabled();
        LanBypassCheck.IsChecked = _settings.LanBypassEnabled;

        RefreshLocalPosture();
        ApplySnapshotToUi(_vpnEngine.Snapshot);
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        if (e.Cancel) return;

        _windowStateStore.Save(this);
        if (!AllowClose)
        {
            e.Cancel = true;
            Hide();
            ShowInTaskbar = false;
            return;
        }

        _telemetryTimer.Stop();
        try { Task.Run(async () => await _vpnEngine.DisposeAsync().ConfigureAwait(false)).Wait(2000); } catch { }
    }

    public void TriggerToggleConnectionFromTray()
        => Dispatcher.Invoke(() => ConnectToggleButton_Click(this, new RoutedEventArgs()));

    private void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsInitialized || sender is not System.Windows.Controls.RadioButton rb || rb.Tag is not string tag) return;

        DashboardView.Visibility = tag == "Dashboard" ? Visibility.Visible : Visibility.Collapsed;
        StatsView.Visibility = tag == "Stats" ? Visibility.Visible : Visibility.Collapsed;
        CloudflareView.Visibility = tag == "Cloudflare" ? Visibility.Visible : Visibility.Collapsed;
        DnsView.Visibility = tag == "Dns" ? Visibility.Visible : Visibility.Collapsed;
        SecurityView.Visibility = tag == "Security" ? Visibility.Visible : Visibility.Collapsed;

        // Keep Home screen clean & uncluttered like Android HomeScreen
        TopHeaderArea.Visibility = tag == "Dashboard" ? Visibility.Collapsed : Visibility.Visible;

        (PageTitleText.Text, PageSubtitleText.Text) = tag switch
        {
            "Stats" => ("Stats & Activity", "Live MASQUE telemetry, tunnel address, and split-tunnel bypass log"),
            "Cloudflare" => ("Cloudflare One", "Zero Trust team sign-in, device enrollment, and MASQUE transport"),
            "Dns" => ("Zero Trust DNS", "Cloudflare Gateway DNS Location and Split/Private LAN DNS"),
            "Security" => ("Settings & Split Tunnel", "Exclude streaming services & games, sync xSecurity posture, and preferences"),
            _ => ("Home", "Always-on Cloudflare One MASQUE tunnel")
        };
    }

    private void ProtocolPill_Click(object sender, RoutedEventArgs e)
    {
        NavCloudflare.IsChecked = true;
    }

    // --- Connection Lifecycle ---

    private async void ConnectToggleButton_Click(object sender, RoutedEventArgs e)
    {
        var current = _vpnEngine.Snapshot.Status;
        if (current is TunnelStatus.Connected or TunnelStatus.Connecting or TunnelStatus.Reconnecting)
        {
            ConnectToggleButton.IsEnabled = false;
            try
            {
                await Task.Run(() => _vpnEngine.DisconnectAsync());
                StatusText.Text = "Tunnel disconnected";
            }
            finally
            {
                ConnectToggleButton.IsEnabled = true;
            }
            return;
        }

        ConnectToggleButton.IsEnabled = false;
        try
        {
            SaveSettingsSilent();
            await Task.Run(() => _vpnEngine.ConnectAsync(_settings));
            StatusText.Text = "Always-on MASQUE tunnel active";
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
            MessageBox.Show(this, ex.Message, "xPrivacyGuard — Connection Error", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            ConnectToggleButton.IsEnabled = true;
        }
    }

    private void OnVpnStateChanged(VpnRuntimeSnapshot snapshot)
        => Dispatcher.BeginInvoke(() => ApplySnapshotToUi(snapshot));

    private void ApplySnapshotToUi(VpnRuntimeSnapshot s)
    {
        var isConnected = s.Status == TunnelStatus.Connected;
        var isConnecting = s.Status is TunnelStatus.Connecting or TunnelStatus.Reconnecting;
        var isError = s.Status == TunnelStatus.Error;

        HeaderStatusText.Text = s.Status switch
        {
            TunnelStatus.Connected => "Connected",
            TunnelStatus.Connecting => "Connecting…",
            TunnelStatus.Reconnecting => "Reconnecting…",
            TunnelStatus.Error => "Attention Needed",
            _ => "Disconnected"
        };
        TrayStatusChanged?.Invoke(s.StatusMessage, s.Status);
        var stateIcon = s.Status switch
        {
            TunnelStatus.Connected => AppBrandConnectedPng,
            TunnelStatus.Connecting or TunnelStatus.Reconnecting => AppBrandConnectingPng,
            _ => AppBrandDisconnectedPng
        };
        Icon = stateIcon;
        BrandIconImage.Source = stateIcon;

        var accentColor = s.Status switch
        {
            TunnelStatus.Connected => System.Windows.Media.Color.FromRgb(0x22, 0xC5, 0x5E),
            TunnelStatus.Connecting or TunnelStatus.Reconnecting => System.Windows.Media.Color.FromRgb(0xFA, 0xAD, 0x3F),
            _ => System.Windows.Media.Color.FromRgb(0xEF, 0x44, 0x44)
        };
        var statusBrush = new SolidColorBrush(accentColor);
        var cfOrangeBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xF3, 0x80, 0x20));
        var textPrimaryBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xF4, 0xF4, 0xF5));
        var textMutedBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xA1, 0xA1, 0xAA));
        var subtleBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x71, 0x71, 0x7A));
        var borderColor = System.Windows.Media.Color.FromRgb(0x2C, 0x2C, 0x33);

        StatusDot.Fill = statusBrush;
        BrandIconBorder.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(0x8C, accentColor.R, accentColor.G, accentColor.B));

        // Update Futuristic Centerpiece Power Orb (exact match to Android HomeScreen)
        OrbCoreBorder.Stroke = statusBrush;
        OrbPowerIcon.Fill = statusBrush;
        OrbGlowStop0.Color = System.Windows.Media.Color.FromArgb(isConnected || isConnecting ? (byte)0x5C : (byte)0x38, accentColor.R, accentColor.G, accentColor.B);
        OrbGlowStop1.Color = System.Windows.Media.Color.FromArgb(isConnected || isConnecting ? (byte)0x1F : (byte)0x0E, accentColor.R, accentColor.G, accentColor.B);
        OrbGlowEllipse.Opacity = isConnected || isConnecting ? 0.95 : 0.55;
        OrbOuterRing.Stroke = new SolidColorBrush(isConnected || isConnecting
            ? System.Windows.Media.Color.FromArgb(0x38, accentColor.R, accentColor.G, accentColor.B)
            : System.Windows.Media.Color.FromArgb(0xB3, borderColor.R, borderColor.G, borderColor.B));
        OrbMidRing.Stroke = new SolidColorBrush(isConnected || isConnecting
            ? System.Windows.Media.Color.FromArgb(0x73, accentColor.R, accentColor.G, accentColor.B)
            : borderColor);
        OrbCoreCenterStop.Color = isConnected
            ? System.Windows.Media.Color.FromRgb(0x14, 0x29, 0x1D)
            : (isConnecting
                ? System.Windows.Media.Color.FromRgb(0x28, 0x1B, 0x11)
                : System.Windows.Media.Color.FromRgb(0x22, 0x22, 0x27));

        DashStateHeadline.Text = s.Status switch
        {
            TunnelStatus.Connected => "C O N N E C T E D",
            TunnelStatus.Connecting => "C O N N E C T I N G",
            TunnelStatus.Reconnecting => "R E C O N N E C T I N G",
            _ => "D I S C O N N E C T E D"
        };
        DashStateHeadline.Foreground = isConnected ? statusBrush : (isConnecting ? statusBrush : textPrimaryBrush);

        var carrierText = s.Carrier is not null
            ? $"Cloudflare MASQUE ({s.Carrier})"
            : (_settings.CloudflareOne.PreferHttp2 ? "Cloudflare MASQUE (H2)" : "Cloudflare MASQUE (H3)");
        DashStateDetail.Text = s.Status switch
        {
            TunnelStatus.Connected => $"{carrierText} • Encrypted",
            TunnelStatus.Connecting => string.IsNullOrWhiteSpace(s.StatusMessage) ? "Establishing tunnel…" : s.StatusMessage,
            TunnelStatus.Reconnecting => string.IsNullOrWhiteSpace(s.StatusMessage) ? "Reconnecting (Fail-Closed)…" : s.StatusMessage,
            TunnelStatus.Error => string.IsNullOrWhiteSpace(s.StatusMessage) ? "Connection error" : s.StatusMessage,
            _ => "Click to secure your connection"
        };
        DashStateDetail.Foreground = isError ? statusBrush : textMutedBrush;

        ProtocolDot.Fill = isConnected ? cfOrangeBrush : subtleBrush;
        ProtocolLabelText.Text = carrierText;
        AlwaysOnBadgeText.Visibility = _settings.AlwaysOnVpn ? Visibility.Visible : Visibility.Collapsed;

        MetricPeerText.Text = s.ActivePeer ?? _settings.CloudflareOne.DisplayEndpoint;
        MetricCarrierText.Text = s.Carrier is not null ? $"MASQUE {s.Carrier}" : (_settings.CloudflareOne.PreferHttp2 ? "MASQUE H2" : "MASQUE H3");
        MetricIpText.Text = s.AssignedIpv4 ?? _settings.CloudflareOne.DisplayAddresses;
        MetricMtuText.Text = $"MTU {s.Mtu}";

        RefreshTelemetryMetrics();
    }

    private void RefreshTelemetryMetrics()
    {
        if (Visibility != Visibility.Visible || WindowState == WindowState.Minimized)
            return;

        var s = _vpnEngine.Snapshot;
        MetricBypassText.Text = $"{s.BypassCount:N0} bypassed";
        MetricBytesText.Text = $"↑ {FormatBytes(s.TxBytes)}  ↓ {FormatBytes(s.RxBytes)}";
        MetricWfpText.Text = _settings.KillSwitchEnabled ? "WFP Kill Switch ON" : "WFP Kill Switch OFF";

        var drained = 0;
        while (drained < 10 && _pendingTrafficLogs.TryDequeue(out var entry))
        {
            _trafficLog.Insert(0, entry);
            drained++;
        }
        while (_trafficLog.Count > 80)
            _trafficLog.RemoveAt(_trafficLog.Count - 1);
    }

    private void OnTrafficLogged(TrafficLogEntry entry)
    {
        if (_pendingTrafficLogs.Count >= 80) return;
        _pendingTrafficLogs.Enqueue(entry);
    }

    private void OnIdentityEnrolled(AetherIdentitySummary summary)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var cf = _settings.CloudflareOne;
            cf.DeviceId = summary.DeviceId;
            cf.Ipv4Address = summary.Ipv4;
            cf.Ipv6Address = summary.Ipv6;
            cf.Organization = summary.Organization;
            cf.AssignedEndpoint = summary.AssignedEndpoint;
            cf.CertIssuedAt = summary.CertIssuedAt;
            SaveSettingsSilent();
            UpdateEnrolledSummaryUi();
        });
    }

    private void ClearTrafficLog_Click(object sender, RoutedEventArgs e) => _trafficLog.Clear();

    private void QuickToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializing) return;
        _settings.AlwaysOnVpn = DashAlwaysOnCheck.IsChecked == true;
        _settings.KillSwitchEnabled = DashKillSwitchCheck.IsChecked == true;
        SaveSettingsSilent();
    }

    // --- Split Tunnel Exclusions ---

    private void SplitPreset_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializing) return;
        var split = _settings.SplitTunnel;
        split.BypassNetflix = SplitNetflixCheck.IsChecked == true;
        split.BypassHulu = SplitHuluCheck.IsChecked == true;
        split.BypassPrimeVideo = SplitPrimeVideoCheck.IsChecked == true;
        split.BypassDisneyPlus = SplitDisneyPlusCheck.IsChecked == true;
        split.BypassMax = SplitMaxCheck.IsChecked == true;
        split.BypassAppleTv = SplitAppleTvCheck.IsChecked == true;
        split.BypassAppleOne = SplitAppleOneCheck.IsChecked == true;
        split.BypassPeacock = SplitPeacockCheck.IsChecked == true;
        split.BypassYouTube = SplitYouTubeCheck.IsChecked == true;
        split.BypassYouTubeTv = SplitYouTubeTvCheck.IsChecked == true;
        split.BypassSlingTv = SplitSlingTvCheck.IsChecked == true;
        split.BypassPlutoTv = SplitPlutoCheck.IsChecked == true;
        split.BypassParamountPlus = SplitParamountPlusCheck.IsChecked == true;
        split.BypassEspnPlus = SplitEspnPlusCheck.IsChecked == true;
        split.BypassSpotify = SplitSpotifyCheck.IsChecked == true;
        split.BypassTubi = SplitTubiCheck.IsChecked == true;
        split.BypassPhoneLink = SplitPhoneLinkCheck.IsChecked == true;
        split.BypassGamingClients = SplitGamingCheck.IsChecked == true;
        split.InvalidateCache();
        SaveSettingsSilent();
        StatusText.Text = "Updated split-tunnel presets (reconnect to refresh WFP rules)";
    }

    private void SaveSplitDomains_Click(object sender, RoutedEventArgs e)
    {
        var lines = SplitDomainsBox.Text
            .Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
            .Select(l => l.Trim())
            .Where(l => l.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        _settings.SplitTunnel.CustomExcludedDomains = lines;
        _settings.SplitTunnel.InvalidateCache();
        SaveSettingsSilent();
        StatusText.Text = $"Saved {lines.Count} custom split-tunnel domain(s)";
    }

    private void RefreshSplitAppsList()
    {
        SplitAppsList.ItemsSource = null;
        SplitAppsList.ItemsSource = _settings.SplitTunnel.CustomExcludedExecutables;
    }

    private void AddSplitExe_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select game or application executable to exclude from VPN",
            Filter = "Executable files (*.exe)|*.exe|All files (*.*)|*.*",
            Multiselect = true
        };
        if (dlg.ShowDialog(this) != true) return;

        var list = _settings.SplitTunnel.CustomExcludedExecutables;
        foreach (var file in dlg.FileNames)
        {
            if (!list.Contains(file, StringComparer.OrdinalIgnoreCase))
                list.Add(file);
        }
        SaveSettingsSilent();
        RefreshSplitAppsList();
        StatusText.Text = $"Excluded {dlg.FileNames.Length} executable(s) from VPN";
    }

    private async void AddSplitFolder_Click(object sender, RoutedEventArgs e)
    {
        using var dlg = new Forms.FolderBrowserDialog
        {
            Description = "Select a game drive or SteamLibrary folder — all .exe files inside will be excluded from VPN"
        };
        if (dlg.ShowDialog() != Forms.DialogResult.OK || string.IsNullOrWhiteSpace(dlg.SelectedPath)) return;

        var selectedPath = dlg.SelectedPath;
        StatusText.Text = $"Scanning {selectedPath} for game executables...";
        try
        {
            var exes = await Task.Run(() =>
            {
                var opts = new EnumerationOptions
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = true,
                    AttributesToSkip = FileAttributes.Hidden | FileAttributes.System
                };
                return Directory.EnumerateFiles(selectedPath, "*.exe", opts)
                    .Take(500)
                    .ToList();
            });

            var list = _settings.SplitTunnel.CustomExcludedExecutables;
            var added = 0;
            foreach (var exe in exes)
            {
                if (!list.Contains(exe, StringComparer.OrdinalIgnoreCase))
                {
                    list.Add(exe);
                    added++;
                }
            }
            SaveSettingsSilent();
            RefreshSplitAppsList();
            StatusText.Text = $"Added {added} game executable(s) from {selectedPath}";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Could not scan folder:\n\n{ex.Message}", "xPrivacyGuard", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void RemoveSplitExe_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.Button btn || btn.Tag is not string exePath) return;
        _settings.SplitTunnel.CustomExcludedExecutables.RemoveAll(x => x.Equals(exePath, StringComparison.OrdinalIgnoreCase));
        SaveSettingsSilent();
        RefreshSplitAppsList();
    }

    // --- Cloudflare One Tab ---

    private async void CfRequestCode_Click(object sender, RoutedEventArgs e)
    {
        var team = CloudflareOneConfig.NormalizeTeamName(CfTeamBox.Text);
        var email = CfEmailBox.Text.Trim();
        if (string.IsNullOrEmpty(team) || string.IsNullOrEmpty(email))
        {
            CfAuthStatusText.Text = "Enter both your Cloudflare Zero Trust team name and email address.";
            return;
        }

        CfRequestCodeBtn.IsEnabled = false;
        CfAuthStatusText.Text = "Requesting one-time code from Cloudflare Access...";
        try
        {
            if (_activeOtpSession is not null) await _activeOtpSession.DisposeAsync();
            _activeOtpSession = await AetherEmailSignIn.RequestCodeAsync(team, email);
            _settings.CloudflareOne.TeamName = team;
            _settings.CloudflareOne.UserEmail = email;
            SaveSettingsSilent();
            CfAuthStatusText.Text = $"Verification code sent to {_activeOtpSession.Email}. Enter the code and click Verify.";
        }
        catch (Exception ex)
        {
            CfAuthStatusText.Text = $"Failed to request code: {ex.Message}";
        }
        finally
        {
            CfRequestCodeBtn.IsEnabled = true;
        }
    }

    private async void CfSubmitCode_Click(object sender, RoutedEventArgs e)
    {
        if (_activeOtpSession is null)
        {
            CfAuthStatusText.Text = "Request an email code first.";
            return;
        }
        var code = CfOtpCodeBox.Text.Trim();
        if (string.IsNullOrEmpty(code))
        {
            CfAuthStatusText.Text = "Enter the 6-digit verification code from your email.";
            return;
        }

        CfSubmitCodeBtn.IsEnabled = false;
        CfAuthStatusText.Text = "Verifying code and enrolling device...";
        try
        {
            var token = await _activeOtpSession.SubmitCodeAsync(code);
            if (string.IsNullOrWhiteSpace(token))
            {
                CfAuthStatusText.Text = "Cloudflare did not return an Access token. Check the code and try again.";
                return;
            }

            _settings.CloudflareOne.AccessToken = token;
            SaveSettingsSilent();

            Directory.CreateDirectory(_settingsStore.IdentityDirectory);
            var idPath = Path.Combine(_settingsStore.IdentityDirectory, "aether.toml");
            await using var identity = await AetherIdentity.OpenAsync(idPath, _settings.CloudflareOne);
            OnIdentityEnrolled(identity.Info.Summary);
            CfAuthStatusText.Text = $"Enrolled device {identity.Info.Summary.DeviceId} ({identity.Info.Summary.Ipv4}). Ready to connect!";
            CfOtpCodeBox.Clear();
        }
        catch (Exception ex)
        {
            CfAuthStatusText.Text = $"Verification/enrollment failed: {ex.Message}";
        }
        finally
        {
            CfSubmitCodeBtn.IsEnabled = true;
        }
    }

    private async void CfResendCode_Click(object sender, RoutedEventArgs e)
    {
        if (_activeOtpSession is null) return;
        try
        {
            await _activeOtpSession.ResendCodeAsync();
            CfAuthStatusText.Text = $"Resent verification code to {_activeOtpSession.Email}.";
        }
        catch (Exception ex)
        {
            CfAuthStatusText.Text = $"Resend failed: {ex.Message}";
        }
    }

    private void OpenCfServiceTokens_Click(object sender, RoutedEventArgs e)
        => OpenUrl("https://one.dash.cloudflare.com/?to=/:account/access/service-auth/service-tokens");

    private void OpenCfDnsLocations_Click(object sender, RoutedEventArgs e)
        => OpenUrl("https://one.dash.cloudflare.com/?to=/:account/gateway/locations");

    private void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo { FileName = url, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Could not open browser: {ex.Message}";
        }
    }

    private void SaveCloudflare_Click(object sender, RoutedEventArgs e)
    {
        var cf = _settings.CloudflareOne;
        cf.TeamName = CfTeamBox.Text.Trim();
        cf.UserEmail = CfEmailBox.Text.Trim();
        cf.ServiceClientId = CfClientIdBox.Text.Trim();
        cf.ServiceClientSecret = CfClientSecretBox.Password.Trim();
        cf.CustomPeerEndpoint = CfCustomPeerBox.Text.Trim();
        cf.PreferHttp2 = CfPreferHttp2Check.IsChecked == true;
        cf.EnableEch = CfEnableEchCheck.IsChecked == true;
        SaveSettingsSilent();
        UpdateEnrolledSummaryUi();
        StatusText.Text = "Saved Cloudflare One configuration";
    }

    private void ClearEnrollment_Click(object sender, RoutedEventArgs e)
    {
        var cf = _settings.CloudflareOne;
        cf.AccessToken = "";
        cf.DeviceId = "";
        cf.Organization = "";
        cf.Ipv4Address = "";
        cf.Ipv6Address = "";
        cf.AssignedEndpoint = "";
        cf.CertIssuedAt = 0;
        try { AetherIdentity.ClearCachedTeamToken(); } catch { }
        try
        {
            if (Directory.Exists(_settingsStore.IdentityDirectory))
                Directory.Delete(_settingsStore.IdentityDirectory, recursive: true);
        }
        catch { }
        SaveSettingsSilent();
        UpdateEnrolledSummaryUi();
        CfAuthStatusText.Text = "Enrollment cleared.";
    }

    private void UpdateEnrolledSummaryUi()
    {
        var cf = _settings.CloudflareOne;
        CfEnrolledSummaryText.Text = cf.IsEnrolled
            ? $"Status:       Enrolled\nOrganization: {cf.Organization}\nDevice ID:    {cf.DeviceId}\nIPv4:         {cf.Ipv4Address}\nIPv6:         {cf.Ipv6Address}\nEndpoint:     {cf.DisplayEndpoint}"
            : "Status: Not enrolled yet. Sign in with Email OTP or configure a Service Token above.";
    }

    // --- DNS Tab ---

    private void SaveDns_Click(object sender, RoutedEventArgs e)
    {
        var dns = _settings.ZeroTrustDns;
        dns.Ipv4Primary = DnsV4PrimaryBox.Text.Trim();
        dns.Ipv4Secondary = DnsV4SecondaryBox.Text.Trim();
        dns.DohEndpoint = DnsDohBox.Text.Trim();
        dns.DotEndpoint = DnsDotBox.Text.Trim();
        dns.PrivateDomain = DnsPrivateDomainBox.Text.Trim();
        dns.PrivateDnsServer = DnsPrivateServerBox.Text.Trim();
        SaveSettingsSilent();
        StatusText.Text = $"Saved DNS config ({dns.ActiveModeSummary()})";
    }

    // --- Security & Settings Tab ---

    private void RefreshLocalPosture()
    {
        var snap = _vpnEngine.Snapshot;
        var rep = WindowsSecurityChecker.CollectReport(snap.Status == TunnelStatus.Connected, 0, snap.BypassCount);
        SecurityReportText.Text =
            $"Machine:       {rep.MachineName}\n" +
            $"OS:            {rep.OsVersion}\n" +
            $"Administrator: {(rep.IsAdministrator ? "Yes" : "No")}\n" +
            $"Defender RTP:  {(rep.DefenderEnabled ? "Enabled" : "Disabled / Unknown")}\n" +
            $"Win Firewall:  {(rep.FirewallEnabled ? "Enabled" : "Disabled")}\n" +
            $"Secure Boot:   {(rep.SecureBootEnabled ? "Enabled" : "Disabled / Unsupported")}\n" +
            $"UAC:           {(rep.UacEnabled ? "Enabled" : "Disabled")}\n" +
            $"VPN Tunnel:    {(rep.VpnConnected ? "Connected" : "Disconnected")}";
    }

    private void RefreshPosture_Click(object sender, RoutedEventArgs e) => RefreshLocalPosture();

    private async void SyncXSecurity_Click(object sender, RoutedEventArgs e)
    {
        _settings.XSecurityDomain = XSecDomainBox.Text.Trim();
        _settings.XSecurityApiKey = XSecApiKeyBox.Password.Trim();
        SaveSettingsSilent();

        var snap = _vpnEngine.Snapshot;
        var rep = WindowsSecurityChecker.CollectReport(snap.Status == TunnelStatus.Connected, 0, snap.BypassCount);
        StatusText.Text = "Syncing Windows posture to xSecurity...";
        var res = await WindowsSecurityChecker.SyncReportToXServerAsync(_settings.XSecurityDomain, _settings.XSecurityApiKey, rep);
        StatusText.Text = res.Ok
            ? $"{res.Message}" + (res.PostureScore.HasValue ? $" (Score: {res.PostureScore})" : "")
            : res.Message;
        if (!res.Ok)
            MessageBox.Show(this, res.Message, "xSecurity Sync", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void SettingToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializing) return;
        _settings.LanBypassEnabled = LanBypassCheck.IsChecked == true;
        SaveSettingsSilent();
    }

    private void StartWithWindowsCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (!IsInitialized || _isInitializing) return;
        try
        {
            _startupManager.SetEnabled(StartWithWindowsCheck.IsChecked == true);
            StatusText.Text = StartWithWindowsCheck.IsChecked == true
                ? "Startup enabled — launches elevated in tray"
                : "Startup disabled";
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            MessageBox.Show(this, $"Could not update Windows startup.\n\n{ex.Message}", "xPrivacyGuard", MessageBoxButton.OK, MessageBoxImage.Error);
            StartWithWindowsCheck.IsChecked = _startupManager.IsEnabled();
        }
    }

    private void ExportBackup_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new SaveFileDialog
        {
            Title = "Export xPrivacyGuard settings backup",
            FileName = "xprivacyguard-backup.json",
            Filter = "JSON backup (*.json)|*.json"
        };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            _settingsStore.ExportBackup(dlg.FileName, _settings, _settings.SplitTunnel.CustomExcludedExecutables);
            StatusText.Text = $"Exported backup to {Path.GetFileName(dlg.FileName)}";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Backup export failed:\n\n{ex.Message}", "xPrivacyGuard", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ImportBackup_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Import xPrivacyGuard settings backup",
            Filter = "JSON backup (*.json)|*.json"
        };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            var backup = _settingsStore.ImportBackup(dlg.FileName);
            if (backup is null) return;
            _settingsStore.Save(backup.Settings);
            LoadAllState();
            StatusText.Text = "Imported settings backup";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Backup import failed:\n\n{ex.Message}", "xPrivacyGuard", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void SaveSettingsSilent()
    {
        try { _settingsStore.Save(_settings); } catch { }
    }

    private void ExitButton_Click(object sender, RoutedEventArgs e) => RequestExit?.Invoke();

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):F1} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):F2} GB";
    }
}
