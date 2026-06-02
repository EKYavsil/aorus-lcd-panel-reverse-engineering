// Ghidra headless script: dump AP timeout/status-poll support functions and xrefs.
// Offline analysis only.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class APTimeoutAndWatchdogDump extends GhidraScript {
    private final long[] entries = new long[] {
        0x0000BA44L, // status poll helper
        0x0000C370L, // recurring service/watchdog helper seen in status poll loop
        0x0000C986L, // explicit delay helper used elsewhere with 100/300 arguments
        0x0000C260L, // timer/watchdog/peripheral init area
        0x0000181CL, // handler with write xref to shared timeout counter
        0x00001980L, // helper called at handler entry with 0x40076000
        0x0000B4D0L, // 64KB erase helper
        0x0000B910L, // 4KB erase helper
        0x0000B6CCL, // page program helper
        0x0000BAB4L  // WREN helper
    };

    private final long[] literalOrDataTargets = new long[] {
        0x0000BAA8L, 0x0000BAACL, 0x0000BAB0L, // BA44 literal pool
        0x200000FEL,                            // BA44 timeout counter target
        0x0000C370L,                            // service helper entry
        0x40076000L,                            // handler peripheral argument
        0x40068000L, 0x5FA00001L,               // watchdog-looking constants
        0x0000012CL                             // 300 timeout
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

    private String hx(long v) {
        return String.format("0x%08X", v & 0xffffffffL);
    }

    private long readU32(long addr) {
        try {
            byte[] b = new byte[4];
            currentProgram.getMemory().getBytes(toAddr(addr), b);
            return ((long)b[0] & 0xffL) |
                   (((long)b[1] & 0xffL) << 8) |
                   (((long)b[2] & 0xffL) << 16) |
                   (((long)b[3] & 0xffL) << 24);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_timeout_watchdog_dump_" + tag + ".txt");

        Set<Function> hitFuncs = new LinkedHashSet<>();

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP timeout/watchdog dump");
            pw.println("program=" + currentProgram.getName());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println();

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            pw.println("## Selected functions");
            for (long entry : entries) {
                Address addr = toAddr(entry);
                Function fn = getFunctionAt(addr);
                pw.println();
                pw.println("### Function " + addr + " " + (fn == null ? "<none>" : fn.getName()));
                if (fn == null) continue;
                hitFuncs.add(fn);

                pw.println("#### Instructions");
                InstructionIterator it = currentProgram.getListing().getInstructions(fn.getBody(), true);
                for (Instruction ins : it) {
                    pw.printf("%s  %-11s  %s%n", ins.getAddress(), bytesFor(ins), ins.toString());
                }

                pw.println();
                pw.println("#### Decompile");
                DecompileResults res = ifc.decompileFunction(fn, 60, monitor);
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed>");
                }
            }

            pw.println();
            pw.println("## Literal/data words");
            long[] words = new long[] {
                0x000000E4L,
                0x00001934L, 0x00001938L, 0x0000193CL, 0x00001940L, 0x00001944L, 0x00001948L,
                0x0000C34CL, 0x0000C350L, 0x0000C354L, 0x0000C358L, 0x0000C35CL,
                0x0000C360L, 0x0000C364L, 0x0000C368L, 0x0000C36CL,
                0x0000BAA8L, 0x0000BAACL, 0x0000BAB0L,
                0x0000C378L, 0x0000C37CL
            };
            for (long a : words) {
                long v = readU32(a);
                pw.printf("%s -> %s%n", hx(a), v < 0 ? "<read failed>" : hx(v));
            }

            pw.println();
            pw.println("## Scalar hits");
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object obj : ins.getOpObjects(op)) {
                        if (obj instanceof Scalar) {
                            long value = ((Scalar) obj).getUnsignedValue() & 0xffffffffL;
                            for (long target : literalOrDataTargets) {
                                if (value == (target & 0xffffffffL)) {
                                    Function fn = getFunctionContaining(ins.getAddress());
                                    if (fn != null) hitFuncs.add(fn);
                                    pw.printf("%s scalar=%s ins=%s fn=%s%n",
                                        ins.getAddress(),
                                        hx(value),
                                        ins,
                                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                                }
                            }
                        }
                    }
                }
            }

            pw.println();
            pw.println("## References to selected addresses");
            for (long t : literalOrDataTargets) {
                Address a;
                try {
                    a = toAddr(t);
                } catch (Exception e) {
                    continue;
                }
                pw.println("### " + hx(t) + " / " + a);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(a);
                for (Reference ref : refs) {
                    Function fn = getFunctionContaining(ref.getFromAddress());
                    if (fn != null) hitFuncs.add(fn);
                    pw.printf("from=%s type=%s fn=%s%n",
                        ref.getFromAddress(),
                        ref.getReferenceType(),
                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                }
            }

            pw.println();
            pw.println("## Decompilation of additional hit functions");
            for (Function fn : hitFuncs) {
                boolean selected = false;
                for (long entry : entries) {
                    if (fn.getEntryPoint().equals(toAddr(entry))) {
                        selected = true;
                        break;
                    }
                }
                if (selected) continue;

                pw.println("### " + fn.getName() + " @ " + fn.getEntryPoint());
                DecompileResults res = ifc.decompileFunction(fn, 45, monitor);
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
