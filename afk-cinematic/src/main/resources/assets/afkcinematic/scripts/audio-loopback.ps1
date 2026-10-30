# AFK Cinematic audio bridge: records what the PC is playing (WASAPI loopback of the default
# output device) and writes it to stdout as small binary frames for the beat detection in Java.
#
# Frame layout (little endian):  int32 magic 0x41464B41 ("AFKA") | int32 sampleRate | int32 count | float32[count]
# The audio is mixed to mono and downsampled to roughly 11-12 kHz (plenty for the bass band).
# Nothing is stored or sent anywhere; the process exits as soon as stdin is closed (Minecraft quits).
# ASCII only: Windows PowerShell 5.1 misreads non-ASCII characters in scripts without a BOM.
$ErrorActionPreference = 'Stop'

$code = @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace AfkCinematic {
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(int access, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    public static class Loopback {
        const int AUDCLNT_SHAREMODE_SHARED = 0;
        const int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
        const uint AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
        const int CLSCTX_ALL = 23;
        const int TARGET_RATE = 11025;
        const int FRAME_SAMPLES = 256;

        static BinaryWriter output;

        /// Runs the capture on an MTA thread (WASAPI is happiest there) and blocks forever.
        public static void Run() {
            output = new BinaryWriter(Console.OpenStandardOutput());
            var t = new Thread(CaptureForever);
            t.IsBackground = true;
            t.SetApartmentState(ApartmentState.MTA);
            t.Start();
            // Minecraft closes our stdin when it quits or no longer needs us.
            var stdin = new StreamReader(Console.OpenStandardInput());
            while (stdin.ReadLine() != null) { }
            Environment.Exit(0);
        }

        static void CaptureForever() {
            while (true) {
                try { CaptureDevice(); } catch { }
                // Device lost, changed or not available yet: try again shortly.
                Thread.Sleep(1000);
            }
        }

        static string DefaultDeviceId(IMMDeviceEnumerator enumerator) {
            IMMDevice device;
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out device) != 0) return null;
            string id;
            return device.GetId(out id) == 0 ? id : null;
        }

        static void CaptureDevice() {
            var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumerator();
            IMMDevice device;
            // eRender (0) + eMultimedia (1): the output the user hears.
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out device) != 0) return;
            string deviceId;
            device.GetId(out deviceId);

            Guid clientIid = typeof(IAudioClient).GUID;
            object o;
            if (device.Activate(ref clientIid, CLSCTX_ALL, IntPtr.Zero, out o) != 0) return;
            var client = (IAudioClient) o;

            IntPtr fmt;
            if (client.GetMixFormat(out fmt) != 0) return;
            try {
                int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
                int channels = Marshal.ReadInt16(fmt, 2);
                int rate = Marshal.ReadInt32(fmt, 4);
                int blockAlign = Marshal.ReadInt16(fmt, 12);
                int bits = Marshal.ReadInt16(fmt, 14);
                bool isFloat = tag == 3;
                if (tag == 0xFFFE) {
                    // WAVEFORMATEXTENSIBLE: the first 4 bytes of the SubFormat GUID hold the real format tag.
                    isFloat = Marshal.ReadInt32(fmt, 24) == 3;
                }

                // 1 s buffer (in 100 ns units), polled every 10 ms.
                if (client.Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 10000000, 0, fmt, IntPtr.Zero) != 0) return;
                Guid captureIid = typeof(IAudioCaptureClient).GUID;
                object c;
                if (client.GetService(ref captureIid, out c) != 0) return;
                var capture = (IAudioCaptureClient) c;

                int decimation = Math.Max(1, (int) Math.Round(rate / (double) TARGET_RATE));
                int outRate = rate / decimation;
                float[] frame = new float[FRAME_SAMPLES];
                int frameFill = 0;
                double acc = 0;
                int accCount = 0;
                DateTime lastDeviceCheck = DateTime.UtcNow;

                if (client.Start() != 0) return;
                try {
                    while (true) {
                        Thread.Sleep(10);
                        // Follow the default device when the user switches e.g. to headphones.
                        if ((DateTime.UtcNow - lastDeviceCheck).TotalSeconds > 3) {
                            lastDeviceCheck = DateTime.UtcNow;
                            string now = DefaultDeviceId(enumerator);
                            if (now != null && now != deviceId) return;
                        }
                        uint packet;
                        int hr = capture.GetNextPacketSize(out packet);
                        if (hr != 0) return; // e.g. AUDCLNT_E_DEVICE_INVALIDATED
                        while (packet > 0) {
                            IntPtr data; uint frames, flags; ulong devPos, qpcPos;
                            if (capture.GetBuffer(out data, out frames, out flags, out devPos, out qpcPos) != 0) return;
                            bool silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                            for (int i = 0; i < frames; i++) {
                                double mono = 0;
                                if (!silent) {
                                    int baseOffset = i * blockAlign;
                                    for (int ch = 0; ch < channels; ch++) {
                                        mono += ReadSample(data, baseOffset + ch * (bits / 8), bits, isFloat);
                                    }
                                    mono /= channels;
                                }
                                // Simple box filter while downsampling; fine for 40-150 Hz.
                                acc += mono;
                                if (++accCount == decimation) {
                                    frame[frameFill++] = (float) (acc / decimation);
                                    acc = 0;
                                    accCount = 0;
                                    if (frameFill == FRAME_SAMPLES) {
                                        Write(outRate, frame);
                                        frameFill = 0;
                                    }
                                }
                            }
                            capture.ReleaseBuffer(frames);
                            if (capture.GetNextPacketSize(out packet) != 0) return;
                        }
                    }
                } finally {
                    client.Stop();
                }
            } finally {
                Marshal.FreeCoTaskMem(fmt);
            }
        }

        static double ReadSample(IntPtr data, int offset, int bits, bool isFloat) {
            if (isFloat && bits == 32) {
                return BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data, offset)), 0);
            }
            switch (bits) {
                case 16: return Marshal.ReadInt16(data, offset) / 32768.0;
                case 24: {
                    int v = Marshal.ReadByte(data, offset) | (Marshal.ReadByte(data, offset + 1) << 8) | ((sbyte) Marshal.ReadByte(data, offset + 2) << 16);
                    return v / 8388608.0;
                }
                case 32: return Marshal.ReadInt32(data, offset) / 2147483648.0;
                default: return 0;
            }
        }

        static void Write(int rate, float[] samples) {
            output.Write(0x41464B41);
            output.Write(rate);
            output.Write(samples.Length);
            for (int i = 0; i < samples.Length; i++) output.Write(samples[i]);
            output.Flush();
        }
    }
}
'@

Add-Type -TypeDefinition $code -Language CSharp
[AfkCinematic.Loopback]::Run()
