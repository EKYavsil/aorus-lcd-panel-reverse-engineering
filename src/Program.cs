using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace AorusLcdFirmwarePatcher;

internal static class Program
{
    private const int ExpectedApSize = 58_328;
    private const int TimeoutPatchOffset = 0xAA4A;
    private const int ReturnPatchOffset = 0xA534;
    private const int IapCodeSize = 4096;
    private const int ApAddress = 0xC2;
    private const int IapAddressPrimary = 0x44;
    private const int IapAddressFallback = 0x46;
    private const string OriginalApSha256 = "DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C";
    private const string PatchedApSha256 = "FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737";
    private const string OriginalGvLcdFwUpdateSha256 = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70";

    private static readonly byte[] TimeoutOld = Convert.FromHexString("4FF49670");
    private static readonly byte[] TimeoutNew = Convert.FromHexString("40F2E830");
    private static readonly byte[] ReturnOld = Convert.FromHexString("0120");
    private static readonly byte[] ReturnNew = Convert.FromHexString("00BF");

    [STAThread]
    private static int Main()
    {
        try
        {
            LaunchGui();
            return 0;
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "AORUS LCD Firmware Repair", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static void LaunchGui()
    {
        EnsureWindows();
        ApplicationConfiguration.Initialize();
        Application.Run(new RepairForm());
    }

    private static int Repair(RepairOptions options)
    {
        if (!options.ConfirmedByGui)
        {
            throw new InvalidOperationException("Internal repair confirmation is missing.");
        }

        FirmwareInput input = LoadInput(options.InputDir, strictSha: true);
        ValidateOfficialUpdaterHash(input);

        PrepareRepairStage(input, options.StageDir);
        string logPath = Path.Combine(options.StageDir, "repair-flash.log");

        EnsureWindows();
        EnsureElevated();
        ValidateRepairStage(options.StageDir);

        using StreamWriter log = new(new FileStream(logPath, FileMode.Create, FileAccess.Write, FileShare.Read), new UTF8Encoding(false));
        log.AutoFlush = true;

        string originalCurrentDir = Environment.CurrentDirectory;
        try
        {
            Environment.CurrentDirectory = options.StageDir;
            FirmwareFlasher flasher = FirmwareFlasher.Load(Path.Combine(options.StageDir, "GvLcdFwUpdate.dll"), log);
            return flasher.Run();
        }
        finally
        {
            Environment.CurrentDirectory = originalCurrentDir;
        }
    }

    private static void PrepareRepairStage(FirmwareInput input, string stageDir)
    {
        Directory.CreateDirectory(stageDir);

        HashSet<string> allowedStageFiles = new(StringComparer.OrdinalIgnoreCase)
        {
            "GvLcdFwUpdate.dll",
            "GvDisplay.dll",
            "GvDisplayA.dll",
            "GvIntelI2C.dll",
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "Newtonsoft.Json.dll",
            "SQLite.Interop.dll",
            "System.Data.SQLite.dll",
            "config.db"
        };

        foreach (string file in Directory.EnumerateFiles(input.InputDir))
        {
            string name = Path.GetFileName(file);
            if (string.Equals(name, "AP", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(name, "AP1", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (allowedStageFiles.Contains(name))
            {
                File.Copy(file, Path.Combine(stageDir, name), overwrite: true);
            }
        }

        byte[] patchedAp = PatchPayload(input.ApBytes, "AP");
        byte[] patchedAp1 = PatchPayload(input.Ap1Bytes, "AP1");
        ValidatePatchedPair(patchedAp, patchedAp1);

        File.WriteAllBytes(Path.Combine(stageDir, "AP"), patchedAp);
        File.WriteAllBytes(Path.Combine(stageDir, "AP1"), patchedAp1);

        PatchManifest manifest = BuildManifest(input, patchedAp, patchedAp1, stageDir);
        File.WriteAllText(Path.Combine(stageDir, "patch-manifest.json"), JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true }), new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(stageDir, "patch-report.md"), BuildReport(manifest), new UTF8Encoding(false));

        ValidateRepairStage(stageDir);
    }

    private static void ValidateRepairStage(string stageDir)
    {
        string apPath = Path.Combine(stageDir, "AP");
        string ap1Path = Path.Combine(stageDir, "AP1");
        string dllPath = Path.Combine(stageDir, "GvLcdFwUpdate.dll");

        RequireFile(apPath);
        RequireFile(ap1Path);
        RequireFile(dllPath);

        byte[] ap = File.ReadAllBytes(apPath);
        byte[] ap1 = File.ReadAllBytes(ap1Path);
        byte[] dll = File.ReadAllBytes(dllPath);

        ValidatePatchedPayload(ap, "staged AP");
        ValidatePatchedPayload(ap1, "staged AP1");
        ValidatePatchedPair(ap, ap1);

        string dllSha = Sha256(dll);
        if (!string.Equals(dllSha, OriginalGvLcdFwUpdateSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"Staged GvLcdFwUpdate.dll SHA256 mismatch. Expected {OriginalGvLcdFwUpdateSha256}, got {dllSha}.");
        }
    }

    private static FirmwareInput LoadInput(string inputDir, bool strictSha)
    {
        string apPath = Path.Combine(inputDir, "AP");
        string ap1Path = Path.Combine(inputDir, "AP1");
        string gvlcdPath = Path.Combine(inputDir, "GvLcdFwUpdate.dll");

        RequireFile(apPath);
        RequireFile(ap1Path);
        RequireFile(gvlcdPath);

        byte[] ap = File.ReadAllBytes(apPath);
        byte[] ap1 = File.ReadAllBytes(ap1Path);
        byte[] gvlcd = File.ReadAllBytes(gvlcdPath);

        ValidateOriginalPayload(ap, "AP", strictSha);
        ValidateOriginalPayload(ap1, "AP1", strictSha);

        if (!ap.SequenceEqual(ap1))
        {
            throw new InvalidOperationException("AP and AP1 are not identical. This patcher expects the examined F1.4 layout where both payloads match.");
        }

        return new FirmwareInput(inputDir, apPath, ap1Path, gvlcdPath, ap, ap1, gvlcd);
    }

    private static void ValidateOfficialUpdaterHash(FirmwareInput input)
    {
        string sha = Sha256(input.GvLcdFwUpdateBytes);
        if (!string.Equals(sha, OriginalGvLcdFwUpdateSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"Repair mode refuses this updater DLL. Expected {OriginalGvLcdFwUpdateSha256}, got {sha}.");
        }
    }

    private static void ValidateOriginalPayload(byte[] payload, string name, bool strictSha)
    {
        if (payload.Length != ExpectedApSize)
        {
            throw new InvalidOperationException($"{name} size mismatch. Expected {ExpectedApSize}, got {payload.Length}.");
        }

        AssertBytes(payload, TimeoutPatchOffset, TimeoutOld, $"{name} BA44 timeout original bytes");
        AssertBytes(payload, ReturnPatchOffset, ReturnOld, $"{name} B4D0 forced-success original bytes");

        string sha = Sha256(payload);
        if (strictSha && !string.Equals(sha, OriginalApSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"{name} SHA256 mismatch in strict mode. Expected {OriginalApSha256}, got {sha}.");
        }

    }

    private static void ValidatePatchedPayload(byte[] payload, string name)
    {
        if (payload.Length != ExpectedApSize)
        {
            throw new InvalidOperationException($"{name} size mismatch. Expected {ExpectedApSize}, got {payload.Length}.");
        }

        AssertBytes(payload, TimeoutPatchOffset, TimeoutNew, $"{name} BA44 timeout1000 patched bytes");
        AssertBytes(payload, ReturnPatchOffset, ReturnNew, $"{name} B4D0 propagation patched bytes");

        string sha = Sha256(payload);
        if (!string.Equals(sha, PatchedApSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"{name} patched SHA256 mismatch. Expected {PatchedApSha256}, got {sha}.");
        }

        ushort crc = Crc16(payload.AsSpan(0x28));
        if (crc != 0xFFFE)
        {
            throw new InvalidOperationException($"{name} patched CRC16 mismatch. Expected 0xFFFE, got 0x{crc:X4}.");
        }
    }

    private static void ValidatePatchedPair(byte[] patchedAp, byte[] patchedAp1)
    {
        ValidatePatchedPayload(patchedAp, "patched AP");
        ValidatePatchedPayload(patchedAp1, "patched AP1");

        if (!patchedAp.SequenceEqual(patchedAp1))
        {
            throw new InvalidOperationException("Patched AP and AP1 differ. Refusing inconsistent payloads.");
        }
    }

    private static byte[] PatchPayload(byte[] payload, string name)
    {
        byte[] patched = payload.ToArray();
        ReplaceBytes(patched, TimeoutPatchOffset, TimeoutOld, TimeoutNew, $"{name} BA44 timeout patch");
        ReplaceBytes(patched, ReturnPatchOffset, ReturnOld, ReturnNew, $"{name} B4D0 return propagation patch");

        List<(int Offset, byte Old, byte New)> diffs = Diff(payload, patched);
        int expectedDiffBytes = TimeoutOld.Length + ReturnOld.Length;
        if (diffs.Count != expectedDiffBytes)
        {
            throw new InvalidOperationException($"{name} unexpected diff count. Expected {expectedDiffBytes}, got {diffs.Count}.");
        }

        return patched;
    }

    private static PatchManifest BuildManifest(FirmwareInput input, byte[] patchedAp, byte[] patchedAp1, string outputDir)
    {
        return new PatchManifest
        {
            CreatedAtUtc = DateTimeOffset.UtcNow.ToString("O"),
            InputDirectory = "<local-input-directory>",
            OutputDirectory = "<local-output-directory>",
            PatchName = "N2A native64 timeout1000",
            RootCause = "AP firmware native 64 KB erase path used a too-short BA44 status-poll timeout and B4D0 discarded BA44 failure.",
            Safety = "Generated AP/AP1 from local official firmware files. Flashing starts only from the GUI after the user presses Start.",
            Inputs = new Dictionary<string, FileRecord>
            {
                ["AP"] = FileRecord.From(input.ApPath, input.ApBytes, Crc16(input.ApBytes.AsSpan(0x28))),
                ["AP1"] = FileRecord.From(input.Ap1Path, input.Ap1Bytes, Crc16(input.Ap1Bytes.AsSpan(0x28))),
                ["GvLcdFwUpdate.dll"] = FileRecord.From(input.GvLcdFwUpdatePath, input.GvLcdFwUpdateBytes, null)
            },
            Outputs = new Dictionary<string, FileRecord>
            {
                ["AP"] = FileRecord.From(Path.Combine(outputDir, "AP"), patchedAp, Crc16(patchedAp.AsSpan(0x28))),
                ["AP1"] = FileRecord.From(Path.Combine(outputDir, "AP1"), patchedAp1, Crc16(patchedAp1.AsSpan(0x28)))
            },
            Patches =
            [
                new PatchRecord("BA44 timeout 300 -> 1000", "0x0000BA4A", "0xAA4A", Hex(TimeoutOld), Hex(TimeoutNew)),
                new PatchRecord("B4D0 propagate BA44 poll result", "0x0000B534", "0xA534", Hex(ReturnOld), Hex(ReturnNew))
            ],
            Checks = new Dictionary<string, bool>
            {
                ["AP_size_expected"] = patchedAp.Length == ExpectedApSize,
                ["AP1_size_expected"] = patchedAp1.Length == ExpectedApSize,
                ["AP_AP1_identical"] = patchedAp.SequenceEqual(patchedAp1),
                ["BA44_patch_present"] = HasBytes(patchedAp, TimeoutPatchOffset, TimeoutNew),
                ["B4D0_patch_present"] = HasBytes(patchedAp, ReturnPatchOffset, ReturnNew),
                ["GvLcdFwUpdate_not_modified_by_tool"] = true
            }
        };
    }

    private static string BuildReport(PatchManifest manifest)
    {
        StringBuilder sb = new();
        sb.AppendLine("# AORUS LCD Firmware Patch Report");
        sb.AppendLine();
        sb.AppendLine($"Created UTC: `{manifest.CreatedAtUtc}`");
        sb.AppendLine();
        sb.AppendLine("## Patch");
        sb.AppendLine();
        sb.AppendLine($"Name: `{manifest.PatchName}`");
        sb.AppendLine();
        sb.AppendLine("```text");
        sb.AppendLine("BA44 timeout: 300 -> 1000");
        sb.AppendLine("B4D0 forced success: removed / BA44 result propagated");
        sb.AppendLine("```");
        sb.AppendLine();
        sb.AppendLine("## Inputs");
        sb.AppendLine();
        foreach ((string name, FileRecord record) in manifest.Inputs)
        {
            sb.AppendLine($"- `{name}` size `{record.Size}`, SHA256 `{record.Sha256}`" + (record.Crc16Over0x28ToEof is null ? "" : $", CRC16 `{record.Crc16Over0x28ToEof}`"));
        }
        sb.AppendLine();
        sb.AppendLine("## Outputs");
        sb.AppendLine();
        foreach ((string name, FileRecord record) in manifest.Outputs)
        {
            sb.AppendLine($"- `{name}` size `{record.Size}`, SHA256 `{record.Sha256}`, CRC16 `{record.Crc16Over0x28ToEof}`");
        }
        sb.AppendLine();
        sb.AppendLine("## Modified Offsets");
        sb.AppendLine();
        foreach (PatchRecord patch in manifest.Patches)
        {
            sb.AppendLine($"- `{patch.Name}`: AP address `{patch.ApAddress}`, file offset `{patch.FileOffset}`, `{patch.OldBytes}` -> `{patch.NewBytes}`");
        }
        sb.AppendLine();
        sb.AppendLine("## Safety");
        sb.AppendLine();
        sb.AppendLine("- Flashing starts only after the GUI warning is accepted and Start is pressed.");
        sb.AppendLine("- This tool did not modify GIGABYTE DLLs or EXEs.");
        sb.AppendLine("- Flashing firmware can permanently damage the LCD controller if used incorrectly.");
        return sb.ToString();
    }

    private static void RequireFile(string path)
    {
        if (!File.Exists(path))
        {
            throw new FileNotFoundException($"Required file not found: {path}");
        }
    }

    private static void ReplaceBytes(byte[] data, int offset, byte[] expected, byte[] replacement, string label)
    {
        AssertBytes(data, offset, expected, label);
        replacement.CopyTo(data, offset);
    }

    private static void AssertBytes(byte[] data, int offset, byte[] expected, string label)
    {
        if (!HasBytes(data, offset, expected))
        {
            byte[] actual = data.Skip(offset).Take(expected.Length).ToArray();
            throw new InvalidOperationException($"{label}: expected {Hex(expected)} at 0x{offset:X}, got {Hex(actual)}.");
        }
    }

    private static bool HasBytes(byte[] data, int offset, byte[] expected)
    {
        return offset >= 0 && offset + expected.Length <= data.Length && data.AsSpan(offset, expected.Length).SequenceEqual(expected);
    }

    private static List<(int Offset, byte Old, byte New)> Diff(byte[] oldData, byte[] newData)
    {
        List<(int Offset, byte Old, byte New)> diffs = [];
        for (int i = 0; i < oldData.Length; i++)
        {
            if (oldData[i] != newData[i])
            {
                diffs.Add((i, oldData[i], newData[i]));
            }
        }
        return diffs;
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("Repair flashing is supported only on Windows.");
        }
    }

    private static void EnsureElevated()
    {
#pragma warning disable CA1416
        using WindowsIdentity identity = WindowsIdentity.GetCurrent();
        WindowsPrincipal principal = new(identity);
        if (!principal.IsInRole(WindowsBuiltInRole.Administrator))
        {
            throw new InvalidOperationException("Repair flashing requires an elevated Administrator terminal.");
        }
#pragma warning restore CA1416
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

    private static string Hex(byte[] data) => BitConverter.ToString(data).Replace("-", " ");

    private sealed record RepairOptions(string InputDir, string StageDir, bool ConfirmedByGui);
    private sealed record FirmwareInput(string InputDir, string ApPath, string Ap1Path, string GvLcdFwUpdatePath, byte[] ApBytes, byte[] Ap1Bytes, byte[] GvLcdFwUpdateBytes);
    private sealed record PatchRecord(string Name, string ApAddress, string FileOffset, string OldBytes, string NewBytes);

    private sealed record FileRecord(string Path, int Size, string Sha256, string? Crc16Over0x28ToEof)
    {
        public static FileRecord From(string path, byte[] bytes, ushort? crc)
        {
            return new FileRecord(System.IO.Path.GetFileName(path), bytes.Length, Program.Sha256(bytes), crc is null ? null : $"0x{crc:X4}");
        }
    }

    private sealed class PatchManifest
    {
        public string CreatedAtUtc { get; set; } = "";
        public string InputDirectory { get; set; } = "";
        public string OutputDirectory { get; set; } = "";
        public string PatchName { get; set; } = "";
        public string RootCause { get; set; } = "";
        public string Safety { get; set; } = "";
        public Dictionary<string, FileRecord> Inputs { get; set; } = [];
        public Dictionary<string, FileRecord> Outputs { get; set; } = [];
        public List<PatchRecord> Patches { get; set; } = [];
        public Dictionary<string, bool> Checks { get; set; } = [];
    }

    private sealed class RepairForm : Form
    {
        private readonly TextBox _folderText = new();
        private readonly TextBox _logText = new();
        private readonly Button _browseButton = new();
        private readonly Button _startButton = new();
        private readonly Button _cancelButton = new();

        public RepairForm()
        {
            Text = "AORUS LCD Firmware Repair";
            StartPosition = FormStartPosition.CenterScreen;
            Width = 760;
            Height = 600;
            MinimumSize = new Size(720, 560);

            Label warning = new()
            {
                AutoSize = false,
                Left = 16,
                Top = 16,
                Width = 710,
                Height = 170,
                Text =
                    "WARNING / UYARI\r\n\r\n" +
                    "This tool can flash the LCD controller firmware after you press Start. Flashing firmware is risky and can make the LCD controller unusable if the wrong package is selected or the process is interrupted.\r\n\r\n" +
                    "Select the extracted official GIGABYTE LCD firmware folder that contains AP, AP1, and GvLcdFwUpdate.dll. A common location is your extracted Downloads firmware folder, for example: Downloads\\firmware.",
                Font = new Font(Font.FontFamily, 9.5f, FontStyle.Bold)
            };

            Label folderLabel = new()
            {
                AutoSize = true,
                Left = 16,
                Top = 198,
                Text = "Official firmware folder:"
            };

            _folderText.Left = 16;
            _folderText.Top = 222;
            _folderText.Width = 590;
            _folderText.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            _folderText.ReadOnly = true;
            _folderText.Text = FindDefaultFirmwareFolder();

            _browseButton.Left = 616;
            _browseButton.Top = 220;
            _browseButton.Width = 110;
            _browseButton.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            _browseButton.Text = "Browse...";
            _browseButton.Click += (_, _) => Browse();

            _logText.Left = 16;
            _logText.Top = 262;
            _logText.Width = 710;
            _logText.Height = 230;
            _logText.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            _logText.Multiline = true;
            _logText.ReadOnly = true;
            _logText.ScrollBars = ScrollBars.Vertical;
            _logText.Font = new Font(FontFamily.GenericMonospace, 9f);

            _startButton.Left = 476;
            _startButton.Top = 510;
            _startButton.Width = 120;
            _startButton.Anchor = AnchorStyles.Bottom | AnchorStyles.Right;
            _startButton.Text = "Start / Baslat";
            _startButton.Click += async (_, _) => await StartRepairAsync();

            _cancelButton.Left = 606;
            _cancelButton.Top = 510;
            _cancelButton.Width = 120;
            _cancelButton.Anchor = AnchorStyles.Bottom | AnchorStyles.Right;
            _cancelButton.Text = "Cancel / Iptal";
            _cancelButton.Click += (_, _) => Close();

            Controls.Add(warning);
            Controls.Add(folderLabel);
            Controls.Add(_folderText);
            Controls.Add(_browseButton);
            Controls.Add(_logText);
            Controls.Add(_startButton);
            Controls.Add(_cancelButton);

            AppendLog("Select the official firmware folder, then press Start.");
            AppendLog("No firmware action starts until Start is pressed.");
        }

        private void Browse()
        {
            using FolderBrowserDialog dialog = new()
            {
                Description = "Select the extracted official GIGABYTE LCD firmware folder containing AP, AP1, and GvLcdFwUpdate.dll.",
                UseDescriptionForTitle = true,
                SelectedPath = Directory.Exists(_folderText.Text) ? _folderText.Text : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                ShowNewFolderButton = false
            };

            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                _folderText.Text = dialog.SelectedPath;
                AppendLog($"Selected: {dialog.SelectedPath}");
            }
        }

        private async Task StartRepairAsync()
        {
            string input = _folderText.Text.Trim();
            if (string.IsNullOrWhiteSpace(input) || !Directory.Exists(input))
            {
                MessageBox.Show(this, "Select a valid firmware folder first.", "Missing folder", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            DialogResult confirm = MessageBox.Show(
                this,
                "The tool will now validate the selected folder. If every guard passes, it will flash the LCD controller firmware.\r\n\r\nContinue?",
                "Confirm firmware repair",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Warning,
                MessageBoxDefaultButton.Button2);

            if (confirm != DialogResult.OK)
            {
                AppendLog("Cancelled before validation.");
                return;
            }

            SetBusy(true);
            string stage = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AorusLcdFirmwareRepair", "stage");

            try
            {
                AppendLog("Starting strict validation and repair.");
                AppendLog($"Input: {input}");
                AppendLog($"Stage: {stage}");

                int rc = await Task.Run(() => Repair(new RepairOptions(input, stage, ConfirmedByGui: true)));
                AppendLog(rc == 0 ? "Repair completed successfully." : $"Repair ended with exit code {rc}.");

                MessageBox.Show(
                    this,
                    rc == 0 ? "Repair completed successfully." : $"Repair ended with exit code {rc}. Check repair-flash.log in the stage folder.",
                    "AORUS LCD Firmware Repair",
                    MessageBoxButtons.OK,
                    rc == 0 ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            }
            catch (Exception ex)
            {
                AppendLog("ERROR: " + ex.Message);
                MessageBox.Show(this, ex.Message, "Repair failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                SetBusy(false);
            }
        }

        private void SetBusy(bool busy)
        {
            _startButton.Enabled = !busy;
            _browseButton.Enabled = !busy;
            _cancelButton.Text = busy ? "Close after finish" : "Cancel / Iptal";
        }

        private void AppendLog(string message)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action<string>(AppendLog), message);
                return;
            }

            _logText.AppendText($"{DateTime.Now:HH:mm:ss} {message}{Environment.NewLine}");
        }

        private static string FindDefaultFirmwareFolder()
        {
            string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            string[] candidates =
            [
                Path.Combine(user, "Downloads", "firmware"),
                Path.Combine(user, "Downloads"),
                AppContext.BaseDirectory
            ];

            foreach (string candidate in candidates)
            {
                if (Directory.Exists(candidate) && LooksLikeFirmwareFolder(candidate))
                {
                    return candidate;
                }
            }

            foreach (string candidate in candidates)
            {
                if (Directory.Exists(candidate))
                {
                    return candidate;
                }
            }

            return user;
        }

        private static bool LooksLikeFirmwareFolder(string path)
        {
            return File.Exists(Path.Combine(path, "AP")) &&
                   File.Exists(Path.Combine(path, "AP1")) &&
                   File.Exists(Path.Combine(path, "GvLcdFwUpdate.dll"));
        }
    }

    private sealed class FirmwareFlasher
    {
        private readonly I2CInitialDelegate _i2cInitial;
        private readonly I2CAPChangeToIAPDelegate _changeToIap;
        private readonly I2CIAPChangeToErase12ByteModeDelegate _changeToErase12ByteMode;
        private readonly I2CIAPSetAPFlashTableDelegate _setApFlashTable;
        private readonly I2CIAPSubmitCRCAPDelegate _submitCrcAp;
        private readonly I2CIAPFlashAP12ByteModeDelegate _flashAp12ByteMode;
        private readonly I2CIAPChangeToAPDelegate _changeToAp;
        private readonly TextWriter _log;

        private FirmwareFlasher(nint library, TextWriter log)
        {
            _log = log;
            _i2cInitial = GetDelegate<I2CInitialDelegate>(library, "I2CInitial");
            _changeToIap = GetDelegate<I2CAPChangeToIAPDelegate>(library, "I2CAPChangeToIAP");
            _changeToErase12ByteMode = GetDelegate<I2CIAPChangeToErase12ByteModeDelegate>(library, "I2CIAPChangeToErase12ByteMode");
            _setApFlashTable = GetDelegate<I2CIAPSetAPFlashTableDelegate>(library, "I2CIAPSetAPFlashTable");
            _submitCrcAp = GetDelegate<I2CIAPSubmitCRCAPDelegate>(library, "I2CIAPSubmitCRCAP");
            _flashAp12ByteMode = GetDelegate<I2CIAPFlashAP12ByteModeDelegate>(library, "I2CIAPFlashAP12ByteMode");
            _changeToAp = GetDelegate<I2CIAPChangeToAPDelegate>(library, "I2CIAPChangeToAP");
        }

        public static FirmwareFlasher Load(string dllPath, TextWriter log)
        {
            RequireFile(dllPath);
            log.WriteLine($"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} LOAD_DLL {Path.GetFileName(dllPath)}");
            nint library = NativeLibrary.Load(dllPath);
            return new FirmwareFlasher(library, log);
        }

        public int Run()
        {
            Log("FLASH_START");
            int activeIap = -1;
            bool changedToIap = false;
            bool changedBackToAp = false;

            try
            {
                RequireZero("I2CInitial(4096)", _i2cInitial(IapCodeSize));
                SleepLogged(200);

                RequireZero("I2CAPChangeToIAP(0xC2)", _changeToIap(ApAddress));
                changedToIap = true;
                SleepLogged(500);

                int erasePrimary = Call("I2CIAPChangeToErase12ByteMode(0x44)", () => _changeToErase12ByteMode(IapAddressPrimary));
                if (erasePrimary == 0)
                {
                    activeIap = IapAddressPrimary;
                }
                else
                {
                    Log("PRIMARY_ERASE_MODE_FAILED: trying fallback 0x46");
                    int eraseFallback = Call("I2CIAPChangeToErase12ByteMode(0x46)", () => _changeToErase12ByteMode(IapAddressFallback));
                    if (eraseFallback != 0)
                    {
                        throw new InvalidOperationException($"Both erase-mode transitions failed: primary={erasePrimary}, fallback={eraseFallback}");
                    }
                    activeIap = IapAddressFallback;
                    File.Copy("AP1", "AP", overwrite: true);
                    Log("FALLBACK_ACTIVE: copied AP1 over AP, activeIap=0x46");
                    ValidateRepairStage(Environment.CurrentDirectory);
                }

                RequireZero("I2CIAPSetAPFlashTable(58328)", _setApFlashTable(ExpectedApSize));
                SleepLogged(200);

                RequireZero($"I2CIAPSubmitCRCAP(0x{activeIap:X2})", _submitCrcAp(activeIap));
                SleepLogged(200);

                RequireZero($"I2CIAPFlashAP12ByteMode(0x{activeIap:X2})", _flashAp12ByteMode(activeIap));

                RequireZero($"I2CIAPChangeToAP(0x{activeIap:X2})", _changeToAp(activeIap));
                changedBackToAp = true;

                Log("FLASH_SUCCESS");
                return 0;
            }
            finally
            {
                if (changedToIap && !changedBackToAp && activeIap >= 0)
                {
                    try
                    {
                        Log($"FINALLY: attempting I2CIAPChangeToAP(0x{activeIap:X2})");
                        int rc = _changeToAp(activeIap);
                        Log($"FINALLY_RESULT: I2CIAPChangeToAP rc={rc}");
                    }
                    catch (Exception ex)
                    {
                        Log("FINALLY_EXCEPTION: " + ex);
                    }
                }
            }
        }

        private int Call(string name, Func<int> fn)
        {
            Log($"CALL_START: {name}");
            int rc = fn();
            Log($"CALL_END: {name} rc={rc}");
            return rc;
        }

        private void RequireZero(string name, int rc)
        {
            Log($"CALL_END: {name} rc={rc}");
            if (rc != 0)
            {
                throw new InvalidOperationException($"{name} failed rc={rc}");
            }
        }

        private void SleepLogged(int ms)
        {
            Log($"SLEEP_START: {ms}ms");
            Thread.Sleep(ms);
            Log($"SLEEP_END: {ms}ms");
        }

        private void Log(string message)
        {
            string line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}";
            _log.WriteLine(line);
        }

        private static T GetDelegate<T>(nint library, string exportName) where T : Delegate
        {
            nint proc = NativeLibrary.GetExport(library, exportName);
            return Marshal.GetDelegateForFunctionPointer<T>(proc);
        }

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CInitialDelegate(int iapCodeSize);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CAPChangeToIAPDelegate(int apAddress);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CIAPChangeToErase12ByteModeDelegate(int iapAddress);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CIAPSetAPFlashTableDelegate(int size);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CIAPSubmitCRCAPDelegate(int iapAddress);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CIAPFlashAP12ByteModeDelegate(int iapAddress);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        private delegate int I2CIAPChangeToAPDelegate(int iapAddress);
    }
}
