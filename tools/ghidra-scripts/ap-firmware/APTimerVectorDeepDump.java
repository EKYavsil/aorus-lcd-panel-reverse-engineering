// Ghidra headless script: dump AP vector/timer related areas.
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

public class APTimerVectorDeepDump extends GhidraScript {
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

    private String hx(long v) {
        return String.format("0x%08X", v & 0xffffffffL);
    }

    private void dumpFunction(PrintWriter pw, DecompInterface ifc, Function fn) throws Exception {
        if (fn == null) return;
        pw.println();
        pw.println("### " + fn.getName() + " @ " + fn.getEntryPoint());
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

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_timer_vector_deep_dump_" + tag + ".txt");

        long[] interestingWords = new long[] {
            0x000000E4L, 0x00000814L, 0x00000934L,
            0x00001810L, 0x00001814L, 0x00001818L,
            0x00001934L, 0x00001938L, 0x0000193CL,
            0x0000C34CL, 0x0000C350L, 0x0000C354L, 0x0000C358L, 0x0000C35CL,
            0x0000C360L, 0x0000C364L, 0x0000C368L, 0x0000C36CL
        };
        long[] targetScalars = new long[] {
            0x40076000L, 0x40068000L, 0x40088000L, 0x4006A000L, 0x43100618L,
            0x43100000L, 0x40080000L, 0x43004000L, 0x0001A7D0L, 0x5FA00001L,
            0x000035CAL, 0x00002710L, 0x0000012CL
        };
        long[] containingAddrs = new long[] {
            0x000017D4L, 0x00001814L, 0x0000181CL, 0x00001980L,
            0x0000198EL, 0x000019A2L, 0x000019B6L, 0x000019BAL,
            0x0000C260L
        };

        Set<Function> funcs = new LinkedHashSet<>();

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP timer/vector deep dump");
            pw.println("program=" + currentProgram.getName());
            pw.println();

            pw.println("## Words");
            for (long a : interestingWords) {
                long v = readU32(a);
                pw.printf("%s -> %s%n", hx(a), v < 0 ? "<read failed>" : hx(v));
            }

            pw.println();
            pw.println("## Scalar hits");
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object obj : ins.getOpObjects(op)) {
                        if (obj instanceof Scalar) {
                            long value = ((Scalar)obj).getUnsignedValue() & 0xffffffffL;
                            for (long target : targetScalars) {
                                if (value == (target & 0xffffffffL)) {
                                    Function fn = getFunctionContaining(ins.getAddress());
                                    if (fn != null) funcs.add(fn);
                                    pw.printf("%s scalar=%s ins=%s fn=%s%n",
                                        ins.getAddress(), hx(value), ins,
                                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                                }
                            }
                        }
                    }
                }
            }

            pw.println();
            pw.println("## References to literal addresses");
            for (long a : interestingWords) {
                Address addr = toAddr(a);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(addr);
                pw.println("### " + hx(a));
                for (Reference ref : refs) {
                    Function fn = getFunctionContaining(ref.getFromAddress());
                    if (fn != null) funcs.add(fn);
                    pw.printf("from=%s type=%s fn=%s%n",
                        ref.getFromAddress(), ref.getReferenceType(),
                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                }
            }

            for (long a : containingAddrs) {
                Function fn = getFunctionContaining(toAddr(a));
                if (fn != null) funcs.add(fn);
            }

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            pw.println();
            pw.println("## Functions");
            for (Function fn : funcs) {
                dumpFunction(pw, ifc, fn);
            }
            ifc.dispose();
        }

        println("Wrote " + out.getAbsolutePath());
    }
}
