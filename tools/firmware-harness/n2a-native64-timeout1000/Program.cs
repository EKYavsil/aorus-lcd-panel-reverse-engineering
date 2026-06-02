using System.Security.Cryptography;
using System.Text;

internal static class Program
{
    private const string OriginalGvLcdSha = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70";
    private const int ApSize = 58_328;

    private static readonly string BaseDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    private static int Main(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--live", StringComparison.OrdinalIgnoreCase)))
        {
            Console.Error.WriteLine("Live firmware flashing is intentionally not included in the public repository.");
            Console.Error.WriteLine("Use the root AorusLcdFirmwarePatcher to generate patched AP/AP1 payloads only.");
            return 2;
        }

        string logPath = Path.Combine(BaseDir, "n2a_dry_run.log");
        using StreamWriter writer = new(new FileStream(logPath, FileMode.Create, FileAccess.Write, FileShare.Read), new UTF8Encoding(false));
        writer.AutoFlush = true;

        try
        {
            Log(writer, "=== N2A native64 timeout1000 dry-run verifier ===");
            Log(writer, "mode=DRY_RUN_ONLY");
            Log(writer, "No native GvLcdFwUpdate exports are imported or called by this public verifier.");
            Log(writer, $"baseDir={SanitizePath(BaseDir)}");
            Log(writer, $"pid={Environment.ProcessId}");
            Log(writer, $"is64BitProcess={Environment.Is64BitProcess}");

            VerifyPackage(writer);

            Log(writer, "DRY_RUN_SUCCESS: package layout and patch bytes verified.");
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
        string ap = Path.Combine(BaseDir, "AP");
        string ap1 = Path.Combine(BaseDir, "AP1");
        string dll = Path.Combine(BaseDir, "GvLcdFwUpdate.dll");

        RequireFile(ap);
        RequireFile(ap1);
        RequireFile(dll);

        byte[] apBytes = File.ReadAllBytes(ap);
        byte[] ap1Bytes = File.ReadAllBytes(ap1);
        string apSha = Sha256(apBytes);
        string ap1Sha = Sha256(ap1Bytes);
        string dllSha = Sha256(File.ReadAllBytes(dll));

        Log(log, $"AP.size={apBytes.Length}");
        Log(log, $"AP.sha256={apSha}");
        Log(log, $"AP.crc16_0x28_eof=0x{Crc16(apBytes.AsSpan(0x28)):X4}");
        Log(log, $"AP.patch.BA44.bytes={Hex(apBytes, 0xAA4A, 4)}");
        Log(log, $"AP.patch.B4D0.bytes={Hex(apBytes, 0xA534, 2)}");
        Log(log, $"AP1.size={ap1Bytes.Length}");
        Log(log, $"AP1.sha256={ap1Sha}");
        Log(log, $"GvLcdFwUpdate.sha256={dllSha}");

        if (apBytes.Length != ApSize || ap1Bytes.Length != ApSize)
        {
            throw new InvalidOperationException("AP/AP1 size mismatch.");
        }

        if (!apBytes.AsSpan().SequenceEqual(ap1Bytes))
        {
            throw new InvalidOperationException("AP and AP1 differ.");
        }

        if (!string.Equals(dllSha, OriginalGvLcdSha, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("GvLcdFwUpdate.dll is not the original signed hash.");
        }

        if (Hex(apBytes, 0xAA4A, 4) != "40 F2 E8 30")
        {
            throw new InvalidOperationException("BA44 timeout1000 patch bytes missing.");
        }

        if (Hex(apBytes, 0xA534, 2) != "00 BF")
        {
            throw new InvalidOperationException("B4D0 propagation patch bytes missing.");
        }
    }

    private static void RequireFile(string path)
    {
        if (!File.Exists(path))
        {
            throw new FileNotFoundException($"Required local file not found: {Path.GetFileName(path)}");
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

    private static string Hex(byte[] data, int offset, int count)
    {
        return BitConverter.ToString(data, offset, count).Replace("-", " ");
    }

    private static void Log(TextWriter log, string message)
    {
        string line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}";
        Console.WriteLine(line);
        log.WriteLine(line);
    }

    private static string SanitizePath(string path)
    {
        return path.Replace(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "<user-profile>", StringComparison.OrdinalIgnoreCase);
    }
}
