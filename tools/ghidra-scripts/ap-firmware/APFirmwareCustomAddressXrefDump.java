// Ghidra headless script: dump xrefs and decompilation for caller-supplied addresses.
// Usage args: outDir tag address...

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.scalar.Scalar;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class APFirmwareCustomAddressXrefDump extends GhidraScript {
    private long parseTargetLong(String s) {
        String t = s.trim().toLowerCase();
        if (t.startsWith("0x")) {
            return Long.parseUnsignedLong(t.substring(2), 16);
        }
        return Long.parseUnsignedLong(t, 16);
    }

    private String hx(long v) {
        return String.format("0x%08X", v & 0xffffffffL);
    }

    private boolean isTarget(long v, long[] targets) {
        long vv = v & 0xffffffffL;
        for (long t : targets) {
            if (vv == (t & 0xffffffffL)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outDir = args.length > 0 ? args[0] : ".";
        String tag = args.length > 1 ? args[1] : currentProgram.getName();
        long[] targets = new long[Math.max(0, args.length - 2)];
        for (int i = 2; i < args.length; i++) {
            targets[i - 2] = parseTargetLong(args[i]);
        }

        File out = new File(outDir, "ap_custom_address_xrefs_" + tag + ".txt");
        Set<Function> funcs = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP custom address xrefs");
            pw.println("program=" + currentProgram.getName());
            pw.print("targets=");
            for (long t : targets) {
                pw.print(hx(t) + " ");
            }
            pw.println();
            pw.println();

            pw.println("## Scalar/immediate hits");
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object obj : ins.getOpObjects(op)) {
                        if (obj instanceof Scalar) {
                            long v = ((Scalar)obj).getUnsignedValue();
                            if (isTarget(v, targets)) {
                                Function fn = getFunctionContaining(ins.getAddress());
                                if (fn != null) {
                                    funcs.add(fn);
                                }
                                pw.printf("%s %s scalar=%s fn=%s%n",
                                    ins.getAddress(), ins, hx(v),
                                    fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                            }
                        }
                    }
                }
            }

            pw.println();
            pw.println("## Memory references to target addresses");
            for (long t : targets) {
                Address a = toAddr(t);
                pw.println("### " + hx(t));
                currentProgram.getReferenceManager().getReferencesTo(a).forEach(ref -> {
                    Function fn = getFunctionContaining(ref.getFromAddress());
                    if (fn != null) {
                        funcs.add(fn);
                    }
                    pw.printf("from=%s type=%s fn=%s%n",
                        ref.getFromAddress(), ref.getReferenceType(),
                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                });
            }

            pw.println();
            pw.println("## Decompilation of hit functions");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            for (Function fn : funcs) {
                pw.println("### " + fn.getName() + " @ " + fn.getEntryPoint());
                DecompileResults res = ifc.decompileFunction(fn, 30, monitor);
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed>");
                }
            }
            ifc.dispose();
        }
        println("wrote " + out.getAbsolutePath());
    }
}
