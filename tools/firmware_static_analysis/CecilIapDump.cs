using System;
using System.IO;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

class CecilIapDump
{
    static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine("Usage: CecilIapDump <assembly-path> <output-path> [filters...]");
            return 2;
        }

        var assemblyPath = args[0];
        var outputPath = args[1];
        var filters = args.Skip(2).DefaultIfEmpty("I2C").ToArray();

        Directory.CreateDirectory(Path.GetDirectoryName(outputPath));
        var rp = new ReaderParameters { ReadSymbols = false, ReadingMode = ReadingMode.Deferred };
        var asm = AssemblyDefinition.ReadAssembly(assemblyPath, rp);

        using (var w = new StreamWriter(outputPath, false))
        {
            w.WriteLine("# Cecil IAP IL Dump");
            w.WriteLine();
            w.WriteLine("Assembly: `" + assemblyPath + "`");
            w.WriteLine("Filters: `" + string.Join("`, `", filters) + "`");
            w.WriteLine();

            foreach (var module in asm.Modules)
            foreach (var type in module.Types.SelectMany(WalkTypes))
            foreach (var method in type.Methods)
            {
                var full = type.FullName + "." + method.Name;
                if (!filters.Any(f => full.IndexOf(f, StringComparison.OrdinalIgnoreCase) >= 0))
                    continue;

                w.WriteLine("## " + full);
                w.WriteLine();
                w.WriteLine("Signature: `" + method.FullName.Replace("`", "'") + "`");
                w.WriteLine("RVA: `0x" + method.RVA.ToString("X8") + "`");
                w.WriteLine("Impl: `" + method.ImplAttributes + "` Attr: `" + method.Attributes + "`");
                if (method.PInvokeInfo != null)
                {
                    w.WriteLine("PInvoke: module=`" + method.PInvokeInfo.Module.Name + "` entry=`" + method.PInvokeInfo.EntryPoint + "` attrs=`" + method.PInvokeInfo.Attributes + "`");
                }
                w.WriteLine();

                if (!method.HasBody)
                {
                    w.WriteLine("_No body._");
                    w.WriteLine();
                    continue;
                }

                w.WriteLine("Locals:");
                foreach (var v in method.Body.Variables)
                    w.WriteLine("- V_" + v.Index + ": `" + Safe(v.VariableType) + "`");
                w.WriteLine();
                w.WriteLine("```il");
                foreach (var ins in method.Body.Instructions)
                {
                    w.Write("IL_" + ins.Offset.ToString("X4") + ": " + ins.OpCode);
                    if (ins.Operand != null)
                        w.Write(" " + FormatOperand(ins.Operand));
                    w.WriteLine();
                }
                w.WriteLine("```");
                w.WriteLine();
            }
        }
        return 0;
    }

    static System.Collections.Generic.IEnumerable<TypeDefinition> WalkTypes(TypeDefinition t)
    {
        yield return t;
        foreach (var n in t.NestedTypes.SelectMany(WalkTypes))
            yield return n;
    }

    static string FormatOperand(object o)
    {
        var s = o as string;
        if (s != null) return "\"" + s.Replace("\\", "\\\\").Replace("\r", "\\r").Replace("\n", "\\n").Replace("\"", "\\\"") + "\"";
        var i = o as Instruction;
        if (i != null) return "IL_" + i.Offset.ToString("X4");
        var arr = o as Instruction[];
        if (arr != null) return string.Join(", ", arr.Select(x => "IL_" + x.Offset.ToString("X4")).ToArray());
        var mr = o as MethodReference;
        if (mr != null) return Safe(mr.FullName);
        var fr = o as FieldReference;
        if (fr != null) return Safe(fr.FullName);
        var tr = o as TypeReference;
        if (tr != null) return Safe(tr.FullName);
        var vd = o as VariableDefinition;
        if (vd != null) return "V_" + vd.Index;
        var pd = o as ParameterDefinition;
        if (pd != null) return pd.Name;
        return Convert.ToString(o);
    }

    static string Safe(object o)
    {
        return Convert.ToString(o).Replace("`", "'");
    }
}
