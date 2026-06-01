import argparse
import pathlib
import pefile
from capstone import *
from capstone.x86 import *

parser = argparse.ArgumentParser(description="Disassemble selected GvLcdFwUpdate exports for offline research.")
parser.add_argument("dll", type=pathlib.Path, help="Path to resource_gvlcdfwupdate.dll")
parser.add_argument("out", type=pathlib.Path, help="Markdown report output path")
args = parser.parse_args()

DLL = args.dll
OUT = args.out

pe = pefile.PE(str(DLL))
base = pe.OPTIONAL_HEADER.ImageBase

exports = []
for s in pe.DIRECTORY_ENTRY_EXPORT.symbols:
    if s.name:
        exports.append((base + s.address, s.name.decode()))
exports.sort()

import_names = {}
for imp in pe.DIRECTORY_ENTRY_IMPORT:
    dll = imp.dll.decode(errors="replace")
    for sym in imp.imports:
        name = (sym.name or str(sym.ordinal).encode()).decode(errors="replace")
        import_names[sym.address] = f"{dll}!{name}"

def rva_to_off(rva):
    return pe.get_offset_from_rva(rva)

def read_va(va, size):
    rva = va - base
    off = rva_to_off(rva)
    with DLL.open("rb") as f:
        f.seek(off)
        return f.read(size)

def read_cstr(va, limit=160):
    try:
        data = read_va(va, limit)
        end = data.find(b"\x00")
        if end >= 0:
            data = data[:end]
        if data and all((32 <= b < 127) or b in (9, 10, 13) for b in data):
            return data.decode("ascii", errors="replace")
    except Exception:
        pass
    return None

def read_ptr(va):
    try:
        return int.from_bytes(read_va(va, 8), "little")
    except Exception:
        return None

targets = {
    "I2CInitial",
    "I2CReadFWVersion",
    "I2CAPChangeToIAP",
    "I2CIAPChangeToErase12ByteMode",
    "I2CIAPSubmitCRCAP",
    "I2CIAPSetAPFlashTable",
    "I2CIAPFlashAP12ByteMode",
    "I2CIAPChangeToAP",
}

manual_targets = [
    (base + 0x003C30, "internal_I2CIAPFlashAP12ByteMode_impl_180003C30", 0x260),
    (base + 0x003E90, "internal_I2CIAPSubmitCRCAP_impl_180003E90", 0x1D0),
    (base + 0x004060, "internal_change_mode_writer_180004060", 0x250),
    (base + 0x0042B0, "internal_erase12_writer_1800042B0", 0x300),
]

md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = True

OUT.parent.mkdir(parents=True, exist_ok=True)

with OUT.open("w", encoding="utf-8") as w:
    w.write("# GvLcdFwUpdate Export Disassembly\n\n")
    w.write(f"DLL: `{DLL}`\n\n")
    w.write("## Exports\n\n")
    for va, name in exports:
        w.write(f"- `{name}` at `0x{va:X}`\n")
    w.write("\n")

    for idx, (va, name) in enumerate(exports):
        if name not in targets:
            continue
        next_va = exports[idx + 1][0] if idx + 1 < len(exports) else va + 0x300
        size = min(max(next_va - va, 0x20), 0x1000)
        code = read_va(va, size)
        w.write(f"## {name}\n\n")
        w.write(f"VA `0x{va:X}`, size window `{size}` bytes\n\n")
        w.write("```asm\n")
        for ins in md.disasm(code, va):
            extra = ""
            if ins.id == X86_INS_CALL:
                op = ins.operands[0]
                if op.type == X86_OP_IMM:
                    extra = f" ; call 0x{op.imm:X}"
                elif op.type == X86_OP_MEM:
                    mem = op.mem
                    if mem.base == X86_REG_RIP:
                        ptr_slot = ins.address + ins.size + mem.disp
                        ptr = read_ptr(ptr_slot)
                        if ptr_slot in import_names:
                            extra = f" ; call [{ptr_slot:X}] -> {import_names[ptr_slot]}"
                        else:
                            extra = f" ; call [{ptr_slot:X}] -> {ptr and hex(ptr)}"
            for op in ins.operands:
                if op.type == X86_OP_MEM and op.mem.base == X86_REG_RIP:
                    addr = ins.address + ins.size + op.mem.disp
                    s = read_cstr(addr)
                    if s and not extra:
                        extra = f" ; str@0x{addr:X}={s!r}"
            w.write(f"0x{ins.address:X}: {ins.mnemonic:<8} {ins.op_str}{extra}\n")
            if ins.id == X86_INS_RET:
                break
        w.write("```\n\n")

    for va, name, size in manual_targets:
        code = read_va(va, size)
        w.write(f"## {name}\n\n")
        w.write(f"VA `0x{va:X}`, size window `{size}` bytes\n\n")
        w.write("```asm\n")
        for ins in md.disasm(code, va):
            extra = ""
            if ins.id == X86_INS_CALL:
                op = ins.operands[0]
                if op.type == X86_OP_IMM:
                    extra = f" ; call 0x{op.imm:X}"
                elif op.type == X86_OP_MEM:
                    mem = op.mem
                    if mem.base == X86_REG_RIP:
                        ptr_slot = ins.address + ins.size + mem.disp
                        ptr = read_ptr(ptr_slot)
                        if ptr_slot in import_names:
                            extra = f" ; call [{ptr_slot:X}] -> {import_names[ptr_slot]}"
                        else:
                            extra = f" ; call [{ptr_slot:X}] -> {ptr and hex(ptr)}"
            for op in ins.operands:
                if op.type == X86_OP_MEM and op.mem.base == X86_REG_RIP:
                    addr = ins.address + ins.size + op.mem.disp
                    s = read_cstr(addr)
                    if s and not extra:
                        extra = f" ; str@0x{addr:X}={s!r}"
            w.write(f"0x{ins.address:X}: {ins.mnemonic:<8} {ins.op_str}{extra}\n")
            if ins.id == X86_INS_RET:
                break
        w.write("```\n\n")
