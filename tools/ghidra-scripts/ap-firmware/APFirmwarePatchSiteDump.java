// Ghidra headless script: dump instruction bytes and decompile for AP patch candidate functions.
// Offline analysis only.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.MemoryAccessException;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwarePatchSiteDump extends GhidraScript {
    private final long[] entries = new long[] {
        0x0000B4D0L, // 64 KB erase
        0x0000B910L, // 4 KB erase
        0x0000B6CCL, // page program
        0x0000BA44L, // status poll
        0x0000BAB4L, // WREN
        0x0000CB54L, // main loop
        0x00007DB8L  // parser
    };

    private String bytesFor(Instruction ins) {
        byte[] bytes = new byte[ins.getLength()];
        try {
            currentProgram.getMemory().getBytes(ins.getAddress(), bytes);
        } catch (MemoryAccessException e) {
            return "<read failed>";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_patch_site_dump_" + tag + ".txt");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP patch site dump");
            pw.println("program=" + currentProgram.getName());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println();

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            for (long entry : entries) {
                Address addr = toAddr(entry);
                Function fn = getFunctionAt(addr);
                pw.println("## Function " + addr + " " + (fn == null ? "<none>" : fn.getName()));
                if (fn == null) {
                    pw.println();
                    continue;
                }

                pw.println("### Instructions");
                InstructionIterator it = currentProgram.getListing().getInstructions(fn.getBody(), true);
                for (Instruction ins : it) {
                    pw.printf("%s  %-11s  %s%n", ins.getAddress(), bytesFor(ins), ins.toString());
                }

                pw.println();
                pw.println("### Decompile");
                DecompileResults res = ifc.decompileFunction(fn, 60, monitor);
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed>");
                }
                pw.println();
            }
            ifc.dispose();
        }
        println("Wrote " + out.getAbsolutePath());
    }
}
