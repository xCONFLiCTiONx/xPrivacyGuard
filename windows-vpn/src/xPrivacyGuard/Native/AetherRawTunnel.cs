using System.Runtime.InteropServices;
using System.Text.Json;

namespace xPrivacyGuard.Native;

public sealed class AetherNativeException(string message) : Exception(message) { }

internal static partial class AetherMethods
{
    private const string Library = "aether.dll";

    [LibraryImport(Library, EntryPoint = "aether_string_free")]
    internal static partial void StringFree(nint value);

    [LibraryImport(Library, EntryPoint = "aether_version")]
    internal static partial nint Version();

    [LibraryImport(Library, EntryPoint = "aether_job_poll")]
    internal static partial nint JobPoll(ulong job);

    [LibraryImport(Library, EntryPoint = "aether_job_cancel")]
    internal static partial nint JobCancel(ulong job);

    [LibraryImport(Library, EntryPoint = "aether_job_free")]
    internal static partial nint JobFree(ulong job);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_start", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint RawTunnelStart(ulong identity, string payload);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_send")]
    internal static partial int RawTunnelSend(ulong tunnel, byte[] packet, nuint length);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_recv")]
    internal static partial int RawTunnelReceive(ulong tunnel, [Out] byte[] buffer, nuint capacity, uint timeoutMs);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_is_alive")]
    internal static partial int RawTunnelIsAlive(ulong tunnel);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_migrate")]
    internal static partial int RawTunnelMigrate(ulong tunnel);

    [LibraryImport(Library, EntryPoint = "aether_raw_tunnel_stop")]
    internal static partial nint RawTunnelStop(ulong tunnel);
}

public sealed record AetherTunnelInfo(
    ulong Handle,
    string Peer,
    string? Ipv4,
    string? Ipv6,
    int Mtu,
    string Carrier);

public sealed class AetherRawTunnel : IAsyncDisposable
{
    private ulong _handle;
    private int _disposed;

    private AetherRawTunnel(AetherTunnelInfo info)
    {
        Info = info;
        _handle = info.Handle;
    }

    public AetherTunnelInfo Info { get; }
    public bool IsAlive => Volatile.Read(ref _disposed) == 0
                           && Volatile.Read(ref _handle) != 0
                           && AetherMethods.RawTunnelIsAlive(Volatile.Read(ref _handle)) == 1;

    public static string GetNativeVersion()
    {
        using var reply = NativeReply.From(AetherMethods.Version());
        return reply.Root.GetProperty("version").GetString()
               ?? throw new AetherNativeException("Aether returned no version.");
    }

    public static bool IsNativeLibraryAvailable(out string? versionOrError)
    {
        try
        {
            versionOrError = GetNativeVersion();
            return true;
        }
        catch (Exception ex)
        {
            versionOrError = ex.Message;
            return false;
        }
    }

    public static async Task<AetherRawTunnel> StartAsync(
        ulong identity,
        string peer,
        bool useHttp2 = false,
        bool enableEch = false,
        string? profile = null,
        int mtu = 1280,
        TimeSpan? startupTimeout = null,
        CancellationToken cancellationToken = default)
    {
        if (identity == 0) throw new ArgumentOutOfRangeException(nameof(identity));
        if (string.IsNullOrWhiteSpace(peer)) throw new ArgumentException("A MASQUE peer is required.", nameof(peer));
        if (mtu is < 1200 or > 1400) throw new ArgumentOutOfRangeException(nameof(mtu));

        var timeout = startupTimeout ?? TimeSpan.FromSeconds(25);
        if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(startupTimeout));
        var payload = JsonSerializer.Serialize(new
        {
            peer,
            h2 = useHttp2,
            ech = enableEch,
            profile,
            mtu,
            startup_secs = Math.Clamp((long)Math.Ceiling(timeout.TotalSeconds), 3, 90)
        });

        using var startReply = NativeReply.From(AetherMethods.RawTunnelStart(identity, payload));
        var job = startReply.Root.GetProperty("job").GetUInt64();

        try
        {
            var deadline = DateTimeOffset.UtcNow + timeout;
            while (DateTimeOffset.UtcNow < deadline)
            {
                cancellationToken.ThrowIfCancellationRequested();
                await Task.Delay(150, cancellationToken).ConfigureAwait(false);

                using var poll = NativeReply.From(AetherMethods.JobPoll(job));
                var state = poll.Root.GetProperty("state").GetString();
                if (state == "running") continue;
                if (state != "done")
                    throw new AetherNativeException($"Unexpected Aether job state '{state}'.");

                var result = poll.Root.GetProperty("result");
                EnsureSuccess(result);
                var handle = result.GetProperty("tunnel").GetUInt64();
                return new AetherRawTunnel(new AetherTunnelInfo(
                    handle,
                    result.TryGetProperty("peer", out var p) ? p.GetString() ?? peer : peer,
                    result.TryGetProperty("ipv4", out var v4) ? v4.GetString() : null,
                    result.TryGetProperty("ipv6", out var v6) ? v6.GetString() : null,
                    result.TryGetProperty("mtu", out var m) ? m.GetInt32() : mtu,
                    result.TryGetProperty("carrier", out var c) ? c.GetString() ?? "h3" : "h3"));
            }

            throw new TimeoutException($"Aether MASQUE startup exceeded {timeout.TotalSeconds:F0} seconds.");
        }
        catch
        {
            TryStopCompletedTunnel(job);
            try { using var cancel = NativeReply.From(AetherMethods.JobCancel(job)); }
            catch { /* Preserve original startup or cancellation exception. */ }
            throw;
        }
        finally
        {
            try { using var free = NativeReply.From(AetherMethods.JobFree(job)); }
            catch { /* Job cleanup is best-effort. */ }
        }
    }

    public int SendPacketFast(byte[] buffer, int length)
    {
        if (Volatile.Read(ref _disposed) != 0) return -1;
        var handle = Volatile.Read(ref _handle);
        if (handle == 0 || length <= 0 || length > buffer.Length) return -1;
        return AetherMethods.RawTunnelSend(handle, buffer, (nuint)length);
    }

    public int SendPacket(ReadOnlySpan<byte> packet)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        if (packet.IsEmpty || packet.Length > 65535) throw new ArgumentOutOfRangeException(nameof(packet));
        var copy = packet.ToArray();
        return AetherMethods.RawTunnelSend(Volatile.Read(ref _handle), copy, (nuint)copy.Length);
    }

    public int ReceivePacket(byte[] buffer, TimeSpan timeout)
    {
        if (Volatile.Read(ref _disposed) != 0) return -1;
        var handle = Volatile.Read(ref _handle);
        if (handle == 0) return -1;
        ArgumentNullException.ThrowIfNull(buffer);
        if (buffer.Length == 0 || buffer.Length > 65535) throw new ArgumentOutOfRangeException(nameof(buffer));
        var milliseconds = (uint)Math.Clamp((long)timeout.TotalMilliseconds, 1, uint.MaxValue);
        return AetherMethods.RawTunnelReceive(handle, buffer, (nuint)buffer.Length, milliseconds);
    }

    public bool RequestMigration()
    {
        if (Volatile.Read(ref _disposed) != 0) return false;
        var handle = Volatile.Read(ref _handle);
        return handle != 0 && AetherMethods.RawTunnelMigrate(handle) == 1;
    }

    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            var handle = Interlocked.Exchange(ref _handle, 0);
            if (handle != 0)
            {
                try { using var reply = NativeReply.From(AetherMethods.RawTunnelStop(handle)); }
                catch { /* Best-effort cleanup. */ }
            }
        }
        return ValueTask.CompletedTask;
    }

    private static void TryStopCompletedTunnel(ulong job)
    {
        try
        {
            using var poll = NativeReply.From(AetherMethods.JobPoll(job));
            if (poll.Root.GetProperty("state").GetString() != "done") return;
            var result = poll.Root.GetProperty("result");
            if (result.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.False) return;
            if (!result.TryGetProperty("tunnel", out var tunnel)) return;
            using var stopped = NativeReply.From(AetherMethods.RawTunnelStop(tunnel.GetUInt64()));
        }
        catch { /* Best-effort cleanup. */ }
    }

    private static void EnsureSuccess(JsonElement result)
    {
        if (result.ValueKind == JsonValueKind.Object
            && result.TryGetProperty("ok", out var ok)
            && ok.ValueKind == JsonValueKind.False)
        {
            var message = result.TryGetProperty("error", out var error)
                ? error.GetString()
                : "Unknown Aether error";
            throw new AetherNativeException(message ?? "Unknown Aether error");
        }
    }
}

internal sealed class NativeReply : IDisposable
{
    private nint _pointer;
    public JsonDocument Document { get; }
    public JsonElement Root => Document.RootElement;

    private NativeReply(nint pointer)
    {
        if (pointer == 0) throw new AetherNativeException("Aether returned a null response pointer.");
        _pointer = pointer;
        try
        {
            var json = Marshal.PtrToStringUTF8(pointer)
                       ?? throw new AetherNativeException("Aether returned an invalid UTF-8 response.");
            Document = JsonDocument.Parse(json);
            EnsureOk(Document.RootElement);
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    public static NativeReply From(nint pointer) => new(pointer);

    private static void EnsureOk(JsonElement root)
    {
        if (root.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.False)
        {
            var message = root.TryGetProperty("error", out var error) ? error.GetString() : null;
            throw new AetherNativeException(message ?? "Aether operation failed.");
        }
    }

    public void Dispose()
    {
        Document?.Dispose();
        var pointer = Interlocked.Exchange(ref _pointer, 0);
        if (pointer != 0) AetherMethods.StringFree(pointer);
    }
}
