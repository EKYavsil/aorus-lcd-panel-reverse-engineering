using System;
using System.IO;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

public static class BuildFinalStaticSectorPatch
{
    public static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine("Usage: BuildFinalStaticSectorPatch <input-ucVga.dll> <output-ucVga.dll> [assembly-search-dir]");
            return 2;
        }

        var inDll = Path.GetFullPath(args[0]);
        var outDll = Path.GetFullPath(args[1]);
        var searchDir = args.Length >= 3 ? Path.GetFullPath(args[2]) : Path.GetDirectoryName(inDll);

        var resolver = new DefaultAssemblyResolver();
        resolver.AddSearchDirectory(Path.GetDirectoryName(inDll));
        if (!string.IsNullOrWhiteSpace(searchDir))
            resolver.AddSearchDirectory(searchDir);

        var asm = AssemblyDefinition.ReadAssembly(inDll, new ReaderParameters { AssemblyResolver = resolver });
        var mod = asm.MainModule;

        var imgData = mod.GetTypes().Single(t => t.FullName == "ucVga.Models.GvLcd.IMG_DATA");
        var nType = imgData.Fields.Single(f => f.Name == "nType");

        var sendImage = mod.GetTypes().Single(t => t.FullName == "ucVga.Api.GvLcdApi")
            .Methods.Single(m => m.Name == "SendImage" && m.Parameters.Count == 3);
        ExpandShortBranches(sendImage);

        var il = sendImage.Body.GetILProcessor();
        var at = sendImage.Body.Instructions.FirstOrDefault(i => i.Offset == 0x0225);
        if (at == null)
            throw new InvalidOperationException("Expected F1 SendData call at IL_0225 was not found.");

        var f1 = sendImage.Body.Variables[9];
        var dest = sendImage.Body.Variables[5];
        var skip = at;

        Instruction[] seq =
        {
            // Static image branch only.
            il.Create(OpCodes.Ldarg_1),
            il.Create(OpCodes.Ldfld, nType),
            il.Create(OpCodes.Ldc_I4_1),
            il.Create(OpCodes.Bne_Un, skip),

            // SendImage local destination must be the custom static image slot.
            il.Create(OpCodes.Ldloc_S, dest),
            il.Create(OpCodes.Ldc_I4, 0x01300000),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Brfalse, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldlen),
            il.Create(OpCodes.Conv_I4),
            il.Create(OpCodes.Ldc_I4, 0x13),
            il.Create(OpCodes.Blt, skip),

            // F1 packet magic: F1 CB 55 AC 38.
            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_0),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0xF1),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_1),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0xCB),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_2),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0x55),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_3),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0xAC),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_4),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0x38),
            il.Create(OpCodes.Bne_Un, skip),

            // F1 packet destination must also be 0x01300000.
            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_5),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4_1),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_6),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4, 0x30),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_7),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4_0),
            il.Create(OpCodes.Bne_Un, skip),

            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4_8),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4_0),
            il.Create(OpCodes.Bne_Un, skip),

            // Original erase-mode selector must be the problematic block-erase value.
            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4, 0x11),
            il.Create(OpCodes.Ldelem_U1),
            il.Create(OpCodes.Ldc_I4_2),
            il.Create(OpCodes.Bne_Un, skip),

            // Switch static custom image upload from 64 KB block erase to 4 KB sector erase.
            il.Create(OpCodes.Ldloc_S, f1),
            il.Create(OpCodes.Ldc_I4, 0x11),
            il.Create(OpCodes.Ldc_I4_1),
            il.Create(OpCodes.Stelem_I1),
        };

        foreach (var i in seq)
            il.InsertBefore(at, i);

        var outDir = Path.GetDirectoryName(outDll);
        if (!string.IsNullOrEmpty(outDir))
            Directory.CreateDirectory(outDir);
        asm.Write(outDll);
        Console.WriteLine("Wrote " + outDll);
        return 0;
    }

    private static void ExpandShortBranches(MethodDefinition method)
    {
        foreach (var i in method.Body.Instructions)
        {
            switch (i.OpCode.Code)
            {
                case Code.Br_S: i.OpCode = OpCodes.Br; break;
                case Code.Brfalse_S: i.OpCode = OpCodes.Brfalse; break;
                case Code.Brtrue_S: i.OpCode = OpCodes.Brtrue; break;
                case Code.Beq_S: i.OpCode = OpCodes.Beq; break;
                case Code.Bge_S: i.OpCode = OpCodes.Bge; break;
                case Code.Bge_Un_S: i.OpCode = OpCodes.Bge_Un; break;
                case Code.Bgt_S: i.OpCode = OpCodes.Bgt; break;
                case Code.Bgt_Un_S: i.OpCode = OpCodes.Bgt_Un; break;
                case Code.Ble_S: i.OpCode = OpCodes.Ble; break;
                case Code.Ble_Un_S: i.OpCode = OpCodes.Ble_Un; break;
                case Code.Blt_S: i.OpCode = OpCodes.Blt; break;
                case Code.Blt_Un_S: i.OpCode = OpCodes.Blt_Un; break;
                case Code.Bne_Un_S: i.OpCode = OpCodes.Bne_Un; break;
                case Code.Leave_S: i.OpCode = OpCodes.Leave; break;
            }
        }
    }
}
