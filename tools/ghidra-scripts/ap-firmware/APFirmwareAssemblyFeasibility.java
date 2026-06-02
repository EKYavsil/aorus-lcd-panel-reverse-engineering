// Ghidra headless script: assemble candidate Thumb snippets into an in-memory buffer only.
// Offline analysis only. This script does not patch currentProgram.

import ghidra.app.plugin.assembler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwareAssemblyFeasibility extends GhidraScript {
    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    private void tryAssemble(PrintWriter pw, Assembler asm, long addr, String name, String[] lines) {
        pw.println("## " + name);
        pw.println("address=" + String.format("0x%08X", addr));
        Address a = toAddr(addr);
        try {
            AssemblyBuffer buffer = new AssemblyBuffer(asm, a);
            for (String line : lines) {
                buffer.assemble(line);
            }
            byte[] bytes = buffer.getBytes();
            pw.println("status=OK");
            pw.println("length=" + bytes.length);
            pw.println("bytes=" + hex(bytes));
            for (String line : lines) {
                pw.println("  " + line);
            }
        }
        catch (Throwable t) {
            pw.println("status=FAILED");
            pw.println("error=" + t.getClass().getName() + ": " + t.getMessage());
            for (String line : lines) {
                pw.println("  " + line);
            }
        }
        pw.println();
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_assembly_feasibility_" + tag + ".txt");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP assembly feasibility");
            pw.println("program=" + currentProgram.getName());
            pw.println("language=" + currentProgram.getLanguageID());
            pw.println();

            Assembler asm = Assemblers.getAssembler(currentProgram);

            tryAssemble(pw, asm, 0x0000B534L, "option1_nop", new String[] {
                "nop"
            });

            tryAssemble(pw, asm, 0x0000BA4AL, "timeout_movw_1000", new String[] {
                "movw r0,#0x03e8"
            });

            tryAssemble(pw, asm, 0x0000BA4AL, "timeout_movw_3000", new String[] {
                "movw r0,#0x0bb8"
            });

            tryAssemble(pw, asm, 0x0000BA4AL, "timeout_movw_5000", new String[] {
                "movw r0,#0x1388"
            });

            tryAssemble(pw, asm, 0x0000B4D0L, "option3_b4d0_16x_b910_loop", new String[] {
                "push {r4,r5,lr}",
                "mov r4,r0",
                "movs r5,#0x10",
                "mov r0,r4",
                "bl 0x0000b910",
                "cbz r0,0x0000b4ea",
                "add.w r4,r4,#0x1000",
                "subs r5,#0x1",
                "bne 0x0000b4d6",
                "movs r0,#0x1",
                "pop {r4,r5,pc}",
                "movs r0,#0x0",
                "pop {r4,r5,pc}"
            });

            tryAssemble(pw, asm, 0x0000B4D0L, "option3_b4d0_16x_b910_loop_no_cbz", new String[] {
                "push {r4,r5,lr}",
                "mov r4,r0",
                "movs r5,#0x10",
                "mov r0,r4",
                "bl 0x0000b910",
                "cmp r0,#0x0",
                "beq 0x0000b4ec",
                "add.w r4,r4,#0x1000",
                "subs r5,#0x1",
                "bne 0x0000b4d6",
                "movs r0,#0x1",
                "pop {r4,r5,pc}",
                "movs r0,#0x0",
                "pop {r4,r5,pc}"
            });
        }
        println("Wrote " + out.getAbsolutePath());
    }
}
