// Ghidra headless script: dump AP firmware constants, vector table, and selected decompilation.
// Offline analysis only.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.scalar.Scalar;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class APFirmwareConstantDump extends GhidraScript {
    private final long[] targets = new long[] {
        0x01300000L, 0x012fcc00L, 0x01320000L,
        0x1000L, 0xefffL,
        0x8101L, 0x8203L, 0x8104L, 0x8204L,
        0xf1L, 0xf2L, 0xf3L, 0xe1L, 0xe5L, 0xe7L, 0xaaL,
        0xcbL, 0x55L, 0xacL, 0x38L,
        320L, 170L, 240L, 480L
    };

    private boolean isTarget(long v) {
        for (long t : targets) {
            if ((v & 0xffffffffL) == t) {
                return true;
            }
        }
        return false;
    }

    private String hx(long v) {
        return "0x" + Long.toHexString(v).toUpperCase();
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_constant_dump_" + tag + ".txt");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP firmware constant dump");
            pw.println("program=" + currentProgram.getName());
            pw.println("language=" + currentProgram.getLanguageID());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println();

            pw.println("## Vector table first 64 entries");
            Address base = currentProgram.getMinAddress();
            for (int i = 0; i < 64; i++) {
                Address a = base.add(i * 4L);
                long val = currentProgram.getMemory().getInt(a) & 0xffffffffL;
                pw.printf("%02d @ %s = %s%n", i, a, hx(val));
            }
            pw.println();

            Map<Function, Set<String>> funcs = new LinkedHashMap<>();
            pw.println("## Immediate/scalar target hits");
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    Object[] objs = ins.getOpObjects(op);
                    for (Object obj : objs) {
                        if (obj instanceof Scalar) {
                            long v = ((Scalar) obj).getUnsignedValue();
                            if (isTarget(v)) {
                                Function fn = getFunctionContaining(ins.getAddress());
                                String fnName = fn == null ? "<no function>" : fn.getName() + "@" + fn.getEntryPoint();
                                String line = ins.getAddress() + " " + ins + " scalar=" + hx(v) + " fn=" + fnName;
                                pw.println(line);
                                if (fn != null) {
                                    funcs.computeIfAbsent(fn, k -> new LinkedHashSet<>()).add(hx(v));
                                }
                            }
                        }
                    }
                }
            }
            pw.println();

            pw.println("## Function summary for target hits");
            for (Map.Entry<Function, Set<String>> e : funcs.entrySet()) {
                Function f = e.getKey();
                pw.printf("%s %s body=%s targets=%s%n", f.getEntryPoint(), f.getName(), f.getBody(), e.getValue());
            }
            pw.println();

            pw.println("## Decompilation for target-hit functions");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            int count = 0;
            for (Function f : funcs.keySet()) {
                if (count++ >= 80) {
                    pw.println("decompile limit reached");
                    break;
                }
                pw.println("### " + f.getName() + " @ " + f.getEntryPoint());
                DecompileResults res = ifc.decompileFunction(f, 20, monitor);
                if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed: " + res.getErrorMessage() + ">");
                }
            }
            ifc.dispose();
            pw.println();

            pw.println("## All functions");
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            for (Function f : it) {
                pw.printf("%s %s body=%s%n", f.getEntryPoint(), f.getName(), f.getBody());
            }
        }

        println("wrote " + out.getAbsolutePath());
    }
}
