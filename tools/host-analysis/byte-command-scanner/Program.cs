using Mono.Cecil;
using Mono.Cecil.Cil;

if (args.Length < 2)
{
    Console.Error.WriteLine("Usage: ByteCommandScanner <output.md> <assembly-or-folder> [more...]");
    return 2;
}

string outputPath = args[0];
Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath))!);

string[] inputs = args.Skip(1).ToArray();
List<string> files = [];
foreach (string input in inputs)
{
    if (File.Exists(input))
    {
        files.Add(input);
    }
    else if (Directory.Exists(input))
    {
        files.AddRange(Directory.EnumerateFiles(input, "*.dll", SearchOption.AllDirectories));
        files.AddRange(Directory.EnumerateFiles(input, "*.exe", SearchOption.AllDirectories));
    }
}

using StreamWriter writer = new(outputPath, false);
writer.WriteLine("# Byte Command Scanner");
writer.WriteLine();
writer.WriteLine($"Generated: {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
writer.WriteLine();

foreach (string file in files.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x => x))
{
    ScanAssembly(file, writer);
}

return 0;

static void ScanAssembly(string path, TextWriter writer)
{
    AssemblyDefinition asm;
    try
    {
        asm = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { ReadSymbols = false });
    }
    catch (Exception ex)
    {
        writer.WriteLine($"## `{SanitizePath(path)}`");
        writer.WriteLine();
        writer.WriteLine($"Skipped: `{ex.GetType().Name}: {ex.Message}`");
        writer.WriteLine();
        return;
    }

    List<string> hits = [];
    foreach (ModuleDefinition module in asm.Modules)
    {
        foreach (TypeDefinition type in module.Types)
        {
            ScanType(type, hits);
        }
    }

    writer.WriteLine($"## `{SanitizePath(path)}`");
    writer.WriteLine();
    if (hits.Count == 0)
    {
        writer.WriteLine("No SendData / command-byte candidate hit.");
        writer.WriteLine();
        return;
    }

    foreach (string hit in hits)
    {
        writer.WriteLine(hit);
    }
    writer.WriteLine();
}

static void ScanType(TypeDefinition type, List<string> hits)
{
    foreach (MethodDefinition method in type.Methods)
    {
        if (method.HasBody)
        {
            ScanMethod(type, method, hits);
        }
    }

    foreach (TypeDefinition nested in type.NestedTypes)
    {
        ScanType(nested, hits);
    }
}

static void ScanMethod(TypeDefinition type, MethodDefinition method, List<string> hits)
{
    Mono.Collections.Generic.Collection<Instruction> ins = method.Body.Instructions;
    List<ConstHit> constants = ins.Select((i, idx) => new ConstHit(idx, i.Offset, GetIntConstant(i))).Where(x => x.Value.HasValue).ToList();
    List<int> byteConstants = constants.Select(x => x.Value!.Value & 0xff).ToList();
    string methodName = $"{type.FullName}::{method.Name}";

    if (ContainsSubsequence(byteConstants, [0x66, 0xCB, 0x55, 0xAC, 0x38, 0xAB, 0xCD, 0xEF]) ||
        ContainsSubsequence(byteConstants, [0x66, 0xCB, 0x55, 0xAC, 0x38]) ||
        ContainsSubsequence(byteConstants, [0x11, 0xCB, 0x55, 0xAC, 0x38, 0xAB, 0xCD, 0xEF]) ||
        ContainsSubsequence(byteConstants, [0xCB, 0x55, 0xAC, 0x38]))
    {
        hits.Add($"### `{methodName}`");
        hits.Add("");
        hits.Add("Literal constants contain a command-like sequence.");
        hits.Add("");
        hits.Add("```text");
        hits.Add(FormatConstants(constants));
        hits.Add("```");
        hits.Add("");
    }

    for (int i = 0; i < ins.Count; i++)
    {
        if (ins[i].OpCode.FlowControl != FlowControl.Call)
        {
            continue;
        }

        string operand = ins[i].Operand?.ToString() ?? "";
        if (!operand.Contains("SendData", StringComparison.OrdinalIgnoreCase) &&
            !operand.Contains("ReadData", StringComparison.OrdinalIgnoreCase))
        {
            continue;
        }

        int lo = Math.Max(0, i - 140);
        int hi = Math.Min(ins.Count - 1, i + 12);
        List<ConstHit> window = ins.Skip(lo).Take(hi - lo + 1).Select((instruction, rel) =>
            new ConstHit(lo + rel, instruction.Offset, GetIntConstant(instruction))).Where(x => x.Value.HasValue).ToList();
        List<int> values = window.Select(x => x.Value!.Value & 0xff).ToList();

        bool interesting =
            ContainsSubsequence(values, [0x66, 0xCB, 0x55, 0xAC, 0x38]) ||
            ContainsSubsequence(values, [0x11, 0xCB, 0x55, 0xAC, 0x38]) ||
            ContainsSubsequence(values, [0xF1, 0xCB, 0x55, 0xAC, 0x38]) ||
            ContainsSubsequence(values, [0xF2, 0xCB, 0x55, 0xAC, 0x38]) ||
            ContainsSubsequence(values, [0xCB, 0x55, 0xAC, 0x38]) ||
            values.Contains(0x66) && values.Contains(0xAB) && values.Contains(0xCD) && values.Contains(0xEF);

        if (!interesting)
        {
            continue;
        }

        hits.Add($"### `{methodName}` near `{operand}` at IL_{ins[i].Offset:X4}");
        hits.Add("");
        hits.Add("```text");
        hits.Add(FormatConstants(window));
        hits.Add("```");
        hits.Add("");
    }
}

static int? GetIntConstant(Instruction instruction)
{
    return instruction.OpCode.Code switch
    {
        Code.Ldc_I4_M1 => -1,
        Code.Ldc_I4_0 => 0,
        Code.Ldc_I4_1 => 1,
        Code.Ldc_I4_2 => 2,
        Code.Ldc_I4_3 => 3,
        Code.Ldc_I4_4 => 4,
        Code.Ldc_I4_5 => 5,
        Code.Ldc_I4_6 => 6,
        Code.Ldc_I4_7 => 7,
        Code.Ldc_I4_8 => 8,
        Code.Ldc_I4_S => Convert.ToSByte(instruction.Operand),
        Code.Ldc_I4 => Convert.ToInt32(instruction.Operand),
        _ => null
    };
}

static bool ContainsSubsequence(IReadOnlyList<int> haystack, IReadOnlyList<int> needle)
{
    if (needle.Count == 0 || haystack.Count < needle.Count)
    {
        return false;
    }

    for (int i = 0; i <= haystack.Count - needle.Count; i++)
    {
        bool ok = true;
        for (int j = 0; j < needle.Count; j++)
        {
            if (haystack[i + j] != needle[j])
            {
                ok = false;
                break;
            }
        }

        if (ok)
        {
            return true;
        }
    }

    return false;
}

static string FormatConstants(IEnumerable<ConstHit> constants)
{
    return string.Join(Environment.NewLine, constants.Select(c =>
        $"idx={c.Index:D4} IL_{c.Offset:X4} value={c.Value} hex=0x{(c.Value!.Value & 0xff):X2}"));
}

static string SanitizePath(string path)
{
    return path
        .Replace(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "<user-profile>", StringComparison.OrdinalIgnoreCase)
        .Replace(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "<program-files>", StringComparison.OrdinalIgnoreCase)
        .Replace(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "<program-files-x86>", StringComparison.OrdinalIgnoreCase);
}

readonly record struct ConstHit(int Index, int Offset, int? Value);
