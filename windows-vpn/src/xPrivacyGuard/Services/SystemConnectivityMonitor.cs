using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace xPrivacyGuard.Services;

/// <summary>
/// Monitors Windows power transitions (both legacy S3 via SystemEvents and Modern Standby S0ix
/// via PowerRegisterSuspendResumeNotification) as well as network interface/availability changes.
/// </summary>
internal sealed class SystemConnectivityMonitor : IDisposable
{
    private const uint DeviceNotifyCallback = 2;
    private const uint PbtApmSuspend = 0x0004;
    private const uint PbtApmResumeSuspend = 0x0007;
    private const uint PbtApmResumeAutomatic = 0x0012;

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate uint DeviceNotifyCallbackRoutine(nint context, uint type, nint setting);

    [StructLayout(LayoutKind.Sequential)]
    private struct DeviceNotifySubscribeParameters
    {
        public nint Callback;
        public nint Context;
    }

    [DllImport("powrprof.dll", SetLastError = true)]
    private static extern uint PowerRegisterSuspendResumeNotification(
        uint flags,
        ref DeviceNotifySubscribeParameters recipient,
        out nint registrationHandle);

    [DllImport("powrprof.dll", SetLastError = true)]
    private static extern uint PowerUnregisterSuspendResumeNotification(nint registrationHandle);

    private readonly DeviceNotifyCallbackRoutine _powerCallback;
    private nint _powerRegistrationHandle;
    private long _lastSuspendTickMs;
    private long _lastResumeTickMs;
    private long _lastNetworkChangeTickMs;
    private int _disposed;

    public event Action? SystemSuspending;
    public event Action? SystemResumed;
    public event Action? NetworkChanged;

    public SystemConnectivityMonitor()
    {
        _powerCallback = OnWin32PowerNotification;

        try
        {
            SystemEvents.PowerModeChanged += OnPowerModeChanged;
        }
        catch { /* Best-effort subscription. */ }

        try
        {
            var parameters = new DeviceNotifySubscribeParameters
            {
                Callback = Marshal.GetFunctionPointerForDelegate(_powerCallback),
                Context = nint.Zero
            };
            _ = PowerRegisterSuspendResumeNotification(DeviceNotifyCallback, ref parameters, out _powerRegistrationHandle);
        }
        catch { /* Fallback to SystemEvents + clock-jump watchdog if unavailable. */ }

        try
        {
            NetworkChange.NetworkAddressChanged += OnNetworkChanged;
            NetworkChange.NetworkAvailabilityChanged += OnNetworkAvailabilityChanged;
        }
        catch { /* Best-effort subscription. */ }
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (Volatile.Read(ref _disposed) != 0) return;

        switch (e.Mode)
        {
            case PowerModes.Suspend:
                EmitSuspendDebounced();
                break;
            case PowerModes.Resume:
                EmitResumeDebounced();
                break;
        }
    }

    private uint OnWin32PowerNotification(nint context, uint type, nint setting)
    {
        if (Volatile.Read(ref _disposed) != 0) return 0;

        switch (type)
        {
            case PbtApmSuspend:
                EmitSuspendDebounced();
                break;
            case PbtApmResumeAutomatic:
            case PbtApmResumeSuspend:
                EmitResumeDebounced();
                break;
        }

        return 0;
    }

    private void OnNetworkChanged(object? sender, EventArgs e)
        => EmitNetworkChangedDebounced();

    private void OnNetworkAvailabilityChanged(object? sender, NetworkAvailabilityEventArgs e)
        => EmitNetworkChangedDebounced();

    private void EmitSuspendDebounced()
    {
        var now = Environment.TickCount64;
        var prev = Interlocked.Read(ref _lastSuspendTickMs);
        if (now - prev < 2000) return;
        if (Interlocked.CompareExchange(ref _lastSuspendTickMs, now, prev) != prev) return;

        try { SystemSuspending?.Invoke(); } catch { }
    }

    private void EmitResumeDebounced()
    {
        var now = Environment.TickCount64;
        var prev = Interlocked.Read(ref _lastResumeTickMs);
        if (now - prev < 3000) return;
        if (Interlocked.CompareExchange(ref _lastResumeTickMs, now, prev) != prev) return;

        // Dispatch off the Win32 power callback thread so we never block OS resume notifications.
        ThreadPool.QueueUserWorkItem(_ =>
        {
            if (Volatile.Read(ref _disposed) != 0) return;
            try { SystemResumed?.Invoke(); } catch { }
        });
    }

    private void EmitNetworkChangedDebounced()
    {
        var now = Environment.TickCount64;
        var prev = Interlocked.Read(ref _lastNetworkChangeTickMs);
        if (now - prev < 500) return;
        if (Interlocked.CompareExchange(ref _lastNetworkChangeTickMs, now, prev) != prev) return;

        ThreadPool.QueueUserWorkItem(_ =>
        {
            if (Volatile.Read(ref _disposed) != 0) return;
            try { NetworkChanged?.Invoke(); } catch { }
        });
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;

        try { SystemEvents.PowerModeChanged -= OnPowerModeChanged; } catch { }
        try { NetworkChange.NetworkAddressChanged -= OnNetworkChanged; } catch { }
        try { NetworkChange.NetworkAvailabilityChanged -= OnNetworkAvailabilityChanged; } catch { }

        var reg = Interlocked.Exchange(ref _powerRegistrationHandle, nint.Zero);
        if (reg != nint.Zero)
        {
            try { _ = PowerUnregisterSuspendResumeNotification(reg); } catch { }
        }

        GC.KeepAlive(_powerCallback);
    }
}
