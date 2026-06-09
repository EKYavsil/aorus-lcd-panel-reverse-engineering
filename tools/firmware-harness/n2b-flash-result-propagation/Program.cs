using System.Security.Cryptography;
using System.Text;

internal static class Program
{
    private const string ExpectedApSha = "046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333";
    private const string OriginalGvLcdSha = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70";
    private const int ApSize = 58_328;
    private const ushort ExpectedCrc = 0xCB8A;

    private static readonly string BaseDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    private static int Main(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--live", StringComparison.OrdinalIgnoreCase)))
        {
            Console.Error.WriteLine("Live firmware flashing is intentionally not included in this verifier.");
            return 2;
        }

        string logPath = Path.Combine(BaseDir, "n2b_dry_run.log");
        using StreamWriter writer = new(new FileStream(logPath, FileMode.Create, FileAccess.Write, FileShare.Read), new UTF8Encoding(false));
        writer.AutoFlush = true;

        try
        {
            Log(writer, "=== N2B timeout1000 + flash result propagation dry-run verifier ===");
            Log(writer, "mode=DRY_RUN_ONLY");
            Log(writer, "No native GvLcdFwUpdate exports are imported or called.");
            VerifyPackage(writer);
            Log(writer, "DRY_RUN_SUCCESS");
            return 0;
        }
        catch (Exception ex)
        {
            Log(writer, "FATAL_EXCEPTION:");
            Log(writer, ex.ToString());
            return 1;
        }
    }

    private static void VerifyPackage(TextWriter log)
    {
        string apPath = Path.Combine(BaseDir, "AP");
        string ap1Path = Path.Combine(BaseDir, "AP1");
        string dllPath = Path.Combine(BaseDir, "GvLcdFwUpdate.dll");

        RequireFile(apPath);
        RequireFile(ap1Path);
        RequireFile(dllPath);

        byte[] ap = File.ReadAllBytes(apPath);
        byte[] ap1 = File.ReadAllBytes(ap1Path);
        string apSha = Sha256(ap);
        string ap1Sha = Sha256(ap1);
        string dllSha = Sha256(File.ReadAllBytes(dllPath));
        ushort crc = Crc16(ap.AsSpan(0x28));

        Log(log, $"AP.size={ap.Length}");
        Log(log, $"AP.sha256={apSha}");
        Log(log, $"AP.crc16_0x28_eof=0x{crc:X4}");
        Log(log, $"AP.patch.BA44.bytes={Hex(ap, 0xAA4A, 4)}");
        Log(log, $"AP.patch.B4D0.bytes={Hex(ap, 0xA534, 2)}");
        Log(log, $"AP.patch.B6CC.bytes={Hex(ap, 0xA74E, 2)}");
        Log(log, $"AP1.sha256={ap1Sha}");
        Log(log, $"GvLcdFwUpdate.sha256={dllSha}");

        if (ap.Length != ApSize || ap1.Length != ApSize)
        {
            throw new InvalidOperationException("AP/AP1 size mismatch.");
        }

        if (!ap.AsSpan().SequenceEqual(ap1))
        {
            throw new InvalidOperationException("AP and AP1 differ.");
        }

        if (apSha != ExpectedApSha || ap1Sha != ExpectedApSha)
        {
            throw new InvalidOperationException("AP/AP1 patched SHA256 mismatch.");
        }

        if (crc != ExpectedCrc)
        {
            throw new InvalidOperationException($"AP CRC mismatch: 0x{crc:X4}.");
        }

        if (dllSha != OriginalGvLcdSha)
        {
            throw new InvalidOperationException("GvLcdFwUpdate.dll hash mismatch.");
        }

        RequireBytes(ap, 0xAA4A, "40 F2 E8 30", "BA44 timeout1000");
        RequireBytes(ap, 0xA534, "00 BF", "B4D0 result propagation");
        RequireBytes(ap, 0xA74E, "00 BF", "B6CC result propagation");
    }

    private static void RequireFile(string path)
    {
        if (!File.Exists(path))
        {
            throw new FileNotFoundException($"Required local file not found: {Path.GetFileName(path)}");
        }
    }

    private static void RequireBytes(byte[] data, int offset, string expected, string label)
    {
        string actual = Hex(data, offset, expected.Split(' ').Length);
        if (actual != expected)
        {
            throw new InvalidOperationException($"{label}: expected {expected}, got {actual}.");
        }
    }

    private static string Sha256(byte[] data) => Convert.ToHexString(SHA256.HashData(data));

    private static ushort Crc16(ReadOnlySpan<byte> data)
    {
        int crc = 0;
        foreach (byte b in data)
        {
            crc ^= b << 8;
            for (int i = 0; i < 8; i++)
            {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return (ushort)crc;
    }

    private static string Hex(byte[] data, int offset, int count) =>
        BitConverter.ToString(data, offset, count).Replace("-", " ");

    private static void Log(TextWriter log, string message)
    {
        string line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}";
        Console.WriteLine(line);
        log.WriteLine(line);
    }
}
