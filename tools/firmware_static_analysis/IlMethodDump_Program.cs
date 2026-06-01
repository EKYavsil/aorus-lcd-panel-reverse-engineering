using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Reflection;

if (args.Length < 2)
{
    Console.Error.WriteLine("Usage: IlMethodDump <assembly-path> <output-path> [method-name-substring ...]");
    return 2;
}

var assemblyPath = args[0];
var outputPath = args[1];
var filters = args.Skip(2).DefaultIfEmpty("I2C").ToArray();

using var fs = File.OpenRead(assemblyPath);
using var pe = new PEReader(fs);
var reader = pe.GetMetadataReader();

Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
using var writer = new StreamWriter(outputPath, false);

writer.WriteLine("# IL Method Dump");
writer.WriteLine();
writer.WriteLine($"Assembly: `{assemblyPath}`");
writer.WriteLine($"Filters: `{string.Join("`, `", filters)}`");
writer.WriteLine();

foreach (var typeHandle in reader.TypeDefinitions)
{
    var type = reader.GetTypeDefinition(typeHandle);
    var typeName = FullTypeName(reader, type);
    foreach (var methodHandle in type.GetMethods())
    {
        var method = reader.GetMethodDefinition(methodHandle);
        var methodName = reader.GetString(method.Name);
        if (!filters.Any(f => methodName.Contains(f, StringComparison.OrdinalIgnoreCase) ||
                              typeName.Contains(f, StringComparison.OrdinalIgnoreCase)))
            continue;

        writer.WriteLine($"## {typeName}.{methodName}");
        writer.WriteLine();
        writer.WriteLine($"RVA: 0x{method.RelativeVirtualAddress:X8}");
        writer.WriteLine($"Attributes: `{method.Attributes}` Impl: `{method.ImplAttributes}`");
        writer.WriteLine($"Signature: `{DecodeSignature(reader, method.Signature)}`");
        writer.WriteLine();

        if (method.RelativeVirtualAddress == 0)
        {
            writer.WriteLine("_No method body (likely P/Invoke/abstract/runtime provided)._");
            writer.WriteLine();
            continue;
        }

        MethodBodyBlock body;
        try
        {
            body = pe.GetMethodBody(method.RelativeVirtualAddress);
        }
        catch (Exception ex)
        {
            writer.WriteLine($"_Failed to read body: {ex.GetType().Name}: {ex.Message}_");
            writer.WriteLine();
            continue;
        }

        writer.WriteLine($"MaxStack: {body.MaxStack}; Locals: `{FormatToken(reader, body.LocalSignature)}`; IL size: {body.GetILBytes()?.Length ?? 0}");
        writer.WriteLine();
        writer.WriteLine("```il");
        foreach (var line in Disassemble(reader, body.GetILBytes() ?? Array.Empty<byte>()))
            writer.WriteLine(line);
        writer.WriteLine("```");
        writer.WriteLine();
    }
}

static string FullTypeName(MetadataReader reader, TypeDefinition type)
{
    var ns = reader.GetString(type.Namespace);
    var name = reader.GetString(type.Name);
    return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
}

static string DecodeSignature(MetadataReader reader, BlobHandle sigHandle)
{
    var bytes = reader.GetBlobBytes(sigHandle);
    return BitConverter.ToString(bytes);
}

static IEnumerable<string> Disassemble(MetadataReader reader, byte[] il)
{
    var i = 0;
    while (i < il.Length)
    {
        var offset = i;
        ushort code = il[i++];
        if (code == 0xFE && i < il.Length)
            code = (ushort)(0xFE00 | il[i++]);

        var op = OpInfo(code);
        object? operand = null;
        string resolved = "";

        try
        {
            switch (op.Operand)
            {
                case OperandType.InlineNone:
                    break;
                case OperandType.ShortInlineI:
                    operand = unchecked((sbyte)il[i]);
                    i += 1;
                    break;
                case OperandType.InlineI:
                    operand = BitConverter.ToInt32(il, i);
                    i += 4;
                    break;
                case OperandType.InlineI8:
                    operand = BitConverter.ToInt64(il, i);
                    i += 8;
                    break;
                case OperandType.ShortInlineR:
                    operand = BitConverter.ToSingle(il, i);
                    i += 4;
                    break;
                case OperandType.InlineR:
                    operand = BitConverter.ToDouble(il, i);
                    i += 8;
                    break;
                case OperandType.ShortInlineBrTarget:
                    operand = $"{offset + 2 + unchecked((sbyte)il[i]):X4}";
                    i += 1;
                    break;
                case OperandType.InlineBrTarget:
                    operand = $"{offset + 5 + BitConverter.ToInt32(il, i):X4}";
                    i += 4;
                    break;
                case OperandType.ShortInlineVar:
                    operand = il[i];
                    i += 1;
                    break;
                case OperandType.InlineVar:
                    operand = BitConverter.ToUInt16(il, i);
                    i += 2;
                    break;
                case OperandType.InlineSwitch:
                    var count = BitConverter.ToInt32(il, i);
                    i += 4;
                    var baseOffset = i + count * 4;
                    var targets = new string[count];
                    for (var s = 0; s < count; s++)
                    {
                        targets[s] = $"{baseOffset + BitConverter.ToInt32(il, i):X4}";
                        i += 4;
                    }
                    operand = string.Join(", ", targets);
                    break;
                case OperandType.InlineString:
                case OperandType.InlineTok:
                case OperandType.InlineType:
                case OperandType.InlineField:
                case OperandType.InlineMethod:
                case OperandType.InlineSig:
                    var token = BitConverter.ToInt32(il, i);
                    operand = $"0x{token:X8}";
                    resolved = ResolveToken(reader, token);
                    i += 4;
                    break;
            }
        }
        catch
        {
            yield return $"IL_{offset:X4}: {op.Name} <decode-error>";
            yield break;
        }

        yield return operand is null
            ? $"IL_{offset:X4}: {op.Name}"
            : $"IL_{offset:X4}: {op.Name} {operand}{resolved}";
    }
}

static string ResolveToken(MetadataReader reader, int token)
{
    try
    {
        var handle = MetadataTokens.Handle(token);
        return handle.Kind switch
        {
            HandleKind.UserString => " // \"" + reader.GetUserString((UserStringHandle)handle).Replace("\r", "\\r").Replace("\n", "\\n") + "\"",
            HandleKind.MemberReference => " // " + MemberRefName(reader, (MemberReferenceHandle)handle),
            HandleKind.MethodDefinition => " // " + MethodDefName(reader, (MethodDefinitionHandle)handle),
            HandleKind.TypeReference => " // " + TypeRefName(reader, (TypeReferenceHandle)handle),
            HandleKind.TypeDefinition => " // " + FullTypeName(reader, reader.GetTypeDefinition((TypeDefinitionHandle)handle)),
            HandleKind.FieldDefinition => " // " + reader.GetString(reader.GetFieldDefinition((FieldDefinitionHandle)handle).Name),
            _ => ""
        };
    }
    catch
    {
        return "";
    }
}

static string MethodDefName(MetadataReader reader, MethodDefinitionHandle h)
{
    var m = reader.GetMethodDefinition(h);
    var t = reader.GetTypeDefinition(m.GetDeclaringType());
    return FullTypeName(reader, t) + "." + reader.GetString(m.Name);
}

static string MemberRefName(MetadataReader reader, MemberReferenceHandle h)
{
    var mr = reader.GetMemberReference(h);
    return reader.GetString(mr.Name);
}

static string TypeRefName(MetadataReader reader, TypeReferenceHandle h)
{
    var tr = reader.GetTypeReference(h);
    var ns = reader.GetString(tr.Namespace);
    var name = reader.GetString(tr.Name);
    return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
}

static string FormatToken(MetadataReader reader, StandaloneSignatureHandle h)
{
    if (h.IsNil) return "";
    return "0x" + MetadataTokens.GetToken(h).ToString("X8");
}

static (string Name, OperandType Operand) OpInfo(ushort code) => code switch
{
    0x00 => ("nop", OperandType.InlineNone),
    0x01 => ("break", OperandType.InlineNone),
    0x02 => ("ldarg.0", OperandType.InlineNone),
    0x03 => ("ldarg.1", OperandType.InlineNone),
    0x04 => ("ldarg.2", OperandType.InlineNone),
    0x05 => ("ldarg.3", OperandType.InlineNone),
    0x06 => ("ldloc.0", OperandType.InlineNone),
    0x07 => ("ldloc.1", OperandType.InlineNone),
    0x08 => ("ldloc.2", OperandType.InlineNone),
    0x09 => ("ldloc.3", OperandType.InlineNone),
    0x0A => ("stloc.0", OperandType.InlineNone),
    0x0B => ("stloc.1", OperandType.InlineNone),
    0x0C => ("stloc.2", OperandType.InlineNone),
    0x0D => ("stloc.3", OperandType.InlineNone),
    0x0E => ("ldarg.s", OperandType.ShortInlineVar),
    0x0F => ("ldarga.s", OperandType.ShortInlineVar),
    0x10 => ("starg.s", OperandType.ShortInlineVar),
    0x11 => ("ldloc.s", OperandType.ShortInlineVar),
    0x12 => ("ldloca.s", OperandType.ShortInlineVar),
    0x13 => ("stloc.s", OperandType.ShortInlineVar),
    0x14 => ("ldnull", OperandType.InlineNone),
    0x15 => ("ldc.i4.m1", OperandType.InlineNone),
    0x16 => ("ldc.i4.0", OperandType.InlineNone),
    0x17 => ("ldc.i4.1", OperandType.InlineNone),
    0x18 => ("ldc.i4.2", OperandType.InlineNone),
    0x19 => ("ldc.i4.3", OperandType.InlineNone),
    0x1A => ("ldc.i4.4", OperandType.InlineNone),
    0x1B => ("ldc.i4.5", OperandType.InlineNone),
    0x1C => ("ldc.i4.6", OperandType.InlineNone),
    0x1D => ("ldc.i4.7", OperandType.InlineNone),
    0x1E => ("ldc.i4.8", OperandType.InlineNone),
    0x1F => ("ldc.i4.s", OperandType.ShortInlineI),
    0x20 => ("ldc.i4", OperandType.InlineI),
    0x21 => ("ldc.i8", OperandType.InlineI8),
    0x22 => ("ldc.r4", OperandType.ShortInlineR),
    0x23 => ("ldc.r8", OperandType.InlineR),
    0x25 => ("dup", OperandType.InlineNone),
    0x26 => ("pop", OperandType.InlineNone),
    0x27 => ("jmp", OperandType.InlineMethod),
    0x28 => ("call", OperandType.InlineMethod),
    0x29 => ("calli", OperandType.InlineSig),
    0x2A => ("ret", OperandType.InlineNone),
    0x2B => ("br.s", OperandType.ShortInlineBrTarget),
    0x2C => ("brfalse.s", OperandType.ShortInlineBrTarget),
    0x2D => ("brtrue.s", OperandType.ShortInlineBrTarget),
    0x2E => ("beq.s", OperandType.ShortInlineBrTarget),
    0x2F => ("bge.s", OperandType.ShortInlineBrTarget),
    0x30 => ("bgt.s", OperandType.ShortInlineBrTarget),
    0x31 => ("ble.s", OperandType.ShortInlineBrTarget),
    0x32 => ("blt.s", OperandType.ShortInlineBrTarget),
    0x33 => ("bne.un.s", OperandType.ShortInlineBrTarget),
    0x34 => ("bge.un.s", OperandType.ShortInlineBrTarget),
    0x35 => ("bgt.un.s", OperandType.ShortInlineBrTarget),
    0x36 => ("ble.un.s", OperandType.ShortInlineBrTarget),
    0x37 => ("blt.un.s", OperandType.ShortInlineBrTarget),
    0x38 => ("br", OperandType.InlineBrTarget),
    0x39 => ("brfalse", OperandType.InlineBrTarget),
    0x3A => ("brtrue", OperandType.InlineBrTarget),
    0x3B => ("beq", OperandType.InlineBrTarget),
    0x3C => ("bge", OperandType.InlineBrTarget),
    0x3D => ("bgt", OperandType.InlineBrTarget),
    0x3E => ("ble", OperandType.InlineBrTarget),
    0x3F => ("blt", OperandType.InlineBrTarget),
    0x40 => ("bne.un", OperandType.InlineBrTarget),
    0x41 => ("bge.un", OperandType.InlineBrTarget),
    0x42 => ("bgt.un", OperandType.InlineBrTarget),
    0x43 => ("ble.un", OperandType.InlineBrTarget),
    0x44 => ("blt.un", OperandType.InlineBrTarget),
    0x45 => ("switch", OperandType.InlineSwitch),
    0x46 => ("ldind.i1", OperandType.InlineNone),
    0x47 => ("ldind.u1", OperandType.InlineNone),
    0x48 => ("ldind.i2", OperandType.InlineNone),
    0x49 => ("ldind.u2", OperandType.InlineNone),
    0x4A => ("ldind.i4", OperandType.InlineNone),
    0x4B => ("ldind.u4", OperandType.InlineNone),
    0x4C => ("ldind.i8", OperandType.InlineNone),
    0x4D => ("ldind.i", OperandType.InlineNone),
    0x4E => ("ldind.r4", OperandType.InlineNone),
    0x4F => ("ldind.r8", OperandType.InlineNone),
    0x50 => ("ldind.ref", OperandType.InlineNone),
    0x51 => ("stind.ref", OperandType.InlineNone),
    0x52 => ("stind.i1", OperandType.InlineNone),
    0x53 => ("stind.i2", OperandType.InlineNone),
    0x54 => ("stind.i4", OperandType.InlineNone),
    0x55 => ("stind.i8", OperandType.InlineNone),
    0x56 => ("stind.r4", OperandType.InlineNone),
    0x57 => ("stind.r8", OperandType.InlineNone),
    0x58 => ("add", OperandType.InlineNone),
    0x59 => ("sub", OperandType.InlineNone),
    0x5A => ("mul", OperandType.InlineNone),
    0x5B => ("div", OperandType.InlineNone),
    0x5C => ("div.un", OperandType.InlineNone),
    0x5D => ("rem", OperandType.InlineNone),
    0x5E => ("rem.un", OperandType.InlineNone),
    0x5F => ("and", OperandType.InlineNone),
    0x60 => ("or", OperandType.InlineNone),
    0x61 => ("xor", OperandType.InlineNone),
    0x62 => ("shl", OperandType.InlineNone),
    0x63 => ("shr", OperandType.InlineNone),
    0x64 => ("shr.un", OperandType.InlineNone),
    0x65 => ("neg", OperandType.InlineNone),
    0x66 => ("not", OperandType.InlineNone),
    0x67 => ("conv.i1", OperandType.InlineNone),
    0x68 => ("conv.i2", OperandType.InlineNone),
    0x69 => ("conv.i4", OperandType.InlineNone),
    0x6A => ("conv.i8", OperandType.InlineNone),
    0x6B => ("conv.r4", OperandType.InlineNone),
    0x6C => ("conv.r8", OperandType.InlineNone),
    0x6D => ("conv.u4", OperandType.InlineNone),
    0x6E => ("conv.u8", OperandType.InlineNone),
    0x6F => ("callvirt", OperandType.InlineMethod),
    0x70 => ("cpobj", OperandType.InlineType),
    0x71 => ("ldobj", OperandType.InlineType),
    0x72 => ("ldstr", OperandType.InlineString),
    0x73 => ("newobj", OperandType.InlineMethod),
    0x74 => ("castclass", OperandType.InlineType),
    0x75 => ("isinst", OperandType.InlineType),
    0x76 => ("conv.r.un", OperandType.InlineNone),
    0x79 => ("unbox", OperandType.InlineType),
    0x7A => ("throw", OperandType.InlineNone),
    0x7B => ("ldfld", OperandType.InlineField),
    0x7C => ("ldflda", OperandType.InlineField),
    0x7D => ("stfld", OperandType.InlineField),
    0x7E => ("ldsfld", OperandType.InlineField),
    0x7F => ("ldsflda", OperandType.InlineField),
    0x80 => ("stsfld", OperandType.InlineField),
    0x81 => ("stobj", OperandType.InlineType),
    0x82 => ("conv.ovf.i1.un", OperandType.InlineNone),
    0x83 => ("conv.ovf.i2.un", OperandType.InlineNone),
    0x84 => ("conv.ovf.i4.un", OperandType.InlineNone),
    0x85 => ("conv.ovf.i8.un", OperandType.InlineNone),
    0x86 => ("conv.ovf.u1.un", OperandType.InlineNone),
    0x87 => ("conv.ovf.u2.un", OperandType.InlineNone),
    0x88 => ("conv.ovf.u4.un", OperandType.InlineNone),
    0x89 => ("conv.ovf.u8.un", OperandType.InlineNone),
    0x8A => ("conv.ovf.i.un", OperandType.InlineNone),
    0x8B => ("conv.ovf.u.un", OperandType.InlineNone),
    0x8C => ("box", OperandType.InlineType),
    0x8D => ("newarr", OperandType.InlineType),
    0x8E => ("ldlen", OperandType.InlineNone),
    0x8F => ("ldelema", OperandType.InlineType),
    0x90 => ("ldelem.i1", OperandType.InlineNone),
    0x91 => ("ldelem.u1", OperandType.InlineNone),
    0x92 => ("ldelem.i2", OperandType.InlineNone),
    0x93 => ("ldelem.u2", OperandType.InlineNone),
    0x94 => ("ldelem.i4", OperandType.InlineNone),
    0x95 => ("ldelem.u4", OperandType.InlineNone),
    0x96 => ("ldelem.i8", OperandType.InlineNone),
    0x97 => ("ldelem.i", OperandType.InlineNone),
    0x98 => ("ldelem.r4", OperandType.InlineNone),
    0x99 => ("ldelem.r8", OperandType.InlineNone),
    0x9A => ("ldelem.ref", OperandType.InlineNone),
    0x9B => ("stelem.i", OperandType.InlineNone),
    0x9C => ("stelem.i1", OperandType.InlineNone),
    0x9D => ("stelem.i2", OperandType.InlineNone),
    0x9E => ("stelem.i4", OperandType.InlineNone),
    0x9F => ("stelem.i8", OperandType.InlineNone),
    0xA0 => ("stelem.r4", OperandType.InlineNone),
    0xA1 => ("stelem.r8", OperandType.InlineNone),
    0xA2 => ("stelem.ref", OperandType.InlineNone),
    0xA3 => ("ldelem", OperandType.InlineType),
    0xA4 => ("stelem", OperandType.InlineType),
    0xA5 => ("unbox.any", OperandType.InlineType),
    0xB3 => ("conv.ovf.i1", OperandType.InlineNone),
    0xB4 => ("conv.ovf.u1", OperandType.InlineNone),
    0xB5 => ("conv.ovf.i2", OperandType.InlineNone),
    0xB6 => ("conv.ovf.u2", OperandType.InlineNone),
    0xB7 => ("conv.ovf.i4", OperandType.InlineNone),
    0xB8 => ("conv.ovf.u4", OperandType.InlineNone),
    0xB9 => ("conv.ovf.i8", OperandType.InlineNone),
    0xBA => ("conv.ovf.u8", OperandType.InlineNone),
    0xC2 => ("refanyval", OperandType.InlineType),
    0xC3 => ("ckfinite", OperandType.InlineNone),
    0xC6 => ("mkrefany", OperandType.InlineType),
    0xD0 => ("ldtoken", OperandType.InlineTok),
    0xD1 => ("conv.u2", OperandType.InlineNone),
    0xD2 => ("conv.u1", OperandType.InlineNone),
    0xD3 => ("conv.i", OperandType.InlineNone),
    0xD4 => ("conv.ovf.i", OperandType.InlineNone),
    0xD5 => ("conv.ovf.u", OperandType.InlineNone),
    0xD6 => ("add.ovf", OperandType.InlineNone),
    0xD7 => ("add.ovf.un", OperandType.InlineNone),
    0xD8 => ("mul.ovf", OperandType.InlineNone),
    0xD9 => ("mul.ovf.un", OperandType.InlineNone),
    0xDA => ("sub.ovf", OperandType.InlineNone),
    0xDB => ("sub.ovf.un", OperandType.InlineNone),
    0xDC => ("endfinally", OperandType.InlineNone),
    0xDD => ("leave", OperandType.InlineBrTarget),
    0xDE => ("leave.s", OperandType.ShortInlineBrTarget),
    0xDF => ("stind.i", OperandType.InlineNone),
    0xE0 => ("conv.u", OperandType.InlineNone),
    0xFE00 => ("arglist", OperandType.InlineNone),
    0xFE01 => ("ceq", OperandType.InlineNone),
    0xFE02 => ("cgt", OperandType.InlineNone),
    0xFE03 => ("cgt.un", OperandType.InlineNone),
    0xFE04 => ("clt", OperandType.InlineNone),
    0xFE05 => ("clt.un", OperandType.InlineNone),
    0xFE06 => ("ldftn", OperandType.InlineMethod),
    0xFE07 => ("ldvirtftn", OperandType.InlineMethod),
    0xFE09 => ("ldarg", OperandType.InlineVar),
    0xFE0A => ("ldarga", OperandType.InlineVar),
    0xFE0B => ("starg", OperandType.InlineVar),
    0xFE0C => ("ldloc", OperandType.InlineVar),
    0xFE0D => ("ldloca", OperandType.InlineVar),
    0xFE0E => ("stloc", OperandType.InlineVar),
    0xFE0F => ("localloc", OperandType.InlineNone),
    0xFE11 => ("endfilter", OperandType.InlineNone),
    0xFE12 => ("unaligned.", OperandType.ShortInlineI),
    0xFE13 => ("volatile.", OperandType.InlineNone),
    0xFE14 => ("tail.", OperandType.InlineNone),
    0xFE15 => ("initobj", OperandType.InlineType),
    0xFE16 => ("constrained.", OperandType.InlineType),
    0xFE17 => ("cpblk", OperandType.InlineNone),
    0xFE18 => ("initblk", OperandType.InlineNone),
    0xFE1A => ("rethrow", OperandType.InlineNone),
    0xFE1C => ("sizeof", OperandType.InlineType),
    0xFE1D => ("refanytype", OperandType.InlineNone),
    0xFE1E => ("readonly.", OperandType.InlineNone),
    _ => ($"unknown_0x{code:X}", OperandType.InlineNone)
};
