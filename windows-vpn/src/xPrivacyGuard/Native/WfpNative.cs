using System.Runtime.InteropServices;

namespace xPrivacyGuard.Native;

internal static class WfpNative
{
    private const string FwpLib = "fwpuclnt.dll";

    public const uint RpcCAuthnWinnt = 10;
    public const uint FwpmSessionFlagDynamic = 0x00000001;

    public const uint FwpActionBlock = 0x00000001 | 0x00001000;
    public const uint FwpActionPermit = 0x00000002 | 0x00001000;

    public const uint FwpMatchEqual = 0;
    public const uint FwpMatchNotEqual = 5;

    public const uint FwpUint8 = 1;
    public const uint FwpUint16 = 2;
    public const uint FwpUint64 = 4;
    public const uint FwpByteBlobType = 12;
    public const uint FwpV4AddrMask = 0x100;
    public const uint FwpV6AddrMask = 0x101;

    public const byte IpProtoUdp = 17;

    public static readonly Guid FwpmLayerAleAuthConnectV4 = new("c38d57d1-05a7-4c33-904f-7fbceee60e82");
    public static readonly Guid FwpmLayerAleAuthConnectV6 = new("4a72393b-319f-44bc-84c3-ba54dcb3b6b4");

    public static readonly Guid FwpmConditionAleAppId = new("d78e1e87-8644-4ea5-9437-d809ecefc971");
    public static readonly Guid FwpmConditionIpLocalInterface = new("4cd62a49-59c3-4969-b7f3-bda5d32890a4");
    public static readonly Guid FwpmConditionIpRemoteAddress = new("b235ae9a-1d64-49b8-a44c-5ff3d9095045");
    public static readonly Guid FwpmConditionIpProtocol = new("3971ef2b-623e-4f9a-8cb1-6e79b806b9a7");
    public static readonly Guid FwpmConditionIpLocalPort = new("0c1ba1af-5765-453f-af22-a8f791ac775b");
    public static readonly Guid FwpmConditionIpRemotePort = new("c35a604d-d22b-4e1a-91b4-68f674ee674b");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct FwpmDisplayData0
    {
        public string? Name;
        public string? Description;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpByteBlob
    {
        public uint Size;
        public nint Data;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpV4AddrAndMask
    {
        public uint Addr;
        public uint Mask;
    }

    [StructLayout(LayoutKind.Sequential)]
    public unsafe struct FwpV6AddrAndMask
    {
        public fixed byte Addr[16];
        public byte PrefixLength;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpmSession0
    {
        public Guid SessionKey;
        public FwpmDisplayData0 DisplayData;
        public uint Flags;
        public uint TxnWaitTimeoutInMSec;
        public uint ProcessId;
        public nint Sid;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string? Username;
        [MarshalAs(UnmanagedType.Bool)]
        public bool KernelMode;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpmSublayer0
    {
        public Guid SubLayerKey;
        public FwpmDisplayData0 DisplayData;
        public ushort Flags;
        public nint ProviderKey;
        public FwpByteBlob ProviderData;
        public ushort Weight;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct FwpValue0
    {
        [FieldOffset(0)]
        public uint Type;
        [FieldOffset(8)]
        public byte Uint8;
        [FieldOffset(8)]
        public ushort Uint16;
        [FieldOffset(8)]
        public nint Uint64Ptr;
        [FieldOffset(8)]
        public nint ByteBlobPtr;
        [FieldOffset(8)]
        public nint V4AddrMaskPtr;
        [FieldOffset(8)]
        public nint V6AddrMaskPtr;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpmFilterCondition0
    {
        public Guid FieldKey;
        public uint MatchType;
        public FwpValue0 ConditionValue;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpmAction0
    {
        public uint Type;
        public Guid FilterType;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct FwpmFilter0
    {
        public Guid FilterKey;
        public FwpmDisplayData0 DisplayData;
        public uint Flags;
        public nint ProviderKey;
        public FwpByteBlob ProviderData;
        public Guid LayerKey;
        public Guid SubLayerKey;
        public FwpValue0 Weight;
        public uint NumFilterConditions;
        public nint FilterCondition;
        public FwpmAction0 Action;
        public Guid ProviderContextKey; // union { UINT64 rawContext; GUID providerContextKey; }
        public nint Reserved;
        public ulong FilterId;
        public FwpValue0 EffectiveWeight;
    }

    [DllImport(FwpLib, CharSet = CharSet.Unicode)]
    public static extern uint FwpmEngineOpen0(
        string? serverName,
        uint authnService,
        nint authIdentity,
        ref FwpmSession0 session,
        out nint engineHandle);

    [DllImport(FwpLib)]
    public static extern uint FwpmEngineClose0(nint engineHandle);

    [DllImport(FwpLib)]
    public static extern uint FwpmTransactionBegin0(nint engineHandle, uint flags);

    [DllImport(FwpLib)]
    public static extern uint FwpmTransactionCommit0(nint engineHandle);

    [DllImport(FwpLib)]
    public static extern uint FwpmTransactionAbort0(nint engineHandle);

    [DllImport(FwpLib)]
    public static extern uint FwpmSubLayerAdd0(nint engineHandle, ref FwpmSublayer0 subLayer, nint sd);

    [DllImport(FwpLib)]
    public static extern uint FwpmFilterAdd0(nint engineHandle, ref FwpmFilter0 filter, nint sd, out ulong id);

    [DllImport(FwpLib, CharSet = CharSet.Unicode)]
    public static extern uint FwpmGetAppIdFromFileName0(string fileName, out nint appId);

    [DllImport(FwpLib)]
    public static extern void FwpmFreeMemory0(ref nint ptr);
}
