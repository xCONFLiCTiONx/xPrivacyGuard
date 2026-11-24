using System.Runtime.InteropServices;

namespace xPrivacyGuard.Native;

internal static class WintunNative
{
    private const string WintunLib = "wintun.dll";
    private const string Kernel32 = "kernel32.dll";

    public const uint WaitObject0 = 0x00000000;
    public const uint WaitTimeout = 0x00000102;
    public const int ErrorNoMoreItems = 259;

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern nint WintunCreateAdapter(string name, string tunnelType, ref Guid requestedGuid);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern nint WintunOpenAdapter(string name);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern void WintunCloseAdapter(nint adapter);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern void WintunGetAdapterLUID(nint adapter, out ulong luid);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall, SetLastError = true)]
    public static extern nint WintunStartSession(nint adapter, uint capacity);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern void WintunEndSession(nint session);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern nint WintunGetReadWaitEvent(nint session);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall, SetLastError = true)]
    public static extern nint WintunReceivePacket(nint session, out uint packetSize);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern void WintunReleaseReceivePacket(nint session, nint packet);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall, SetLastError = true)]
    public static extern nint WintunAllocateSendPacket(nint session, uint packetSize);

    [DllImport(WintunLib, CallingConvention = CallingConvention.StdCall)]
    public static extern void WintunSendPacket(nint session, nint packet);

    [DllImport(Kernel32, SetLastError = true)]
    public static extern uint WaitForSingleObject(nint handle, uint milliseconds);
}
