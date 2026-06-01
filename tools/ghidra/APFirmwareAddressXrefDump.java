// Ghidra headless script: dump xrefs and decompilation for selected AP RAM addresses.
// Offline analysis only.

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

public class APFirmwareAddressXrefDump extends GhidraScript {
    private final long[] targets = new long[] {
        0x20000160L, 0x200001A8L, 0x20000260L, 0x200002D8L,
        0x2000030CL, 0x20000310L, 0x20000314L, 0x20000318L, 0x20000320L, 0x20000321L,
        0x01300000L, 0x0130000CL, 0x01320000L, 0x0132000CL, 0x01400000L
    };

    private String hx(long v) {
        return "0x" + Long.toHexString(v).toUpperCase();
    }

    private boolean isTarget(long v) {
        long vv = v & 0xffffffffL;
        for (long t : targets) {
            if (vv == (t & 0xffffffffL)) return true;
        }
        return false;
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_address_xrefs_" + tag + ".txt");

        Set<Function> funcs = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP selected address xrefs");
            pw.println("program=" + currentProgram.getName());
            pw.println();

            pw.println("## Scalar/immediate hits");
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object obj : ins.getOpObjects(op)) {
                        if (obj instanceof Scalar) {
                            long v = ((Scalar) obj).getUnsignedValue();
                            if (isTarget(v)) {
                                Function fn = getFunctionContaining(ins.getAddress());
                                if (fn != null) funcs.add(fn);
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
                    if (fn != null) funcs.add(fn);
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
        println("Wrote " + out.getAbsolutePath());
    }
}
