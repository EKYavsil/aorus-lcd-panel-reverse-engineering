// Ghidra headless script: targeted xref/decompile dump for AP GIF timing field.
// Offline analysis only.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.scalar.Scalar;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class APFirmwareTimingFieldXrefDump extends GhidraScript {
    private final long[] targets = new long[] {
        0x20000308L, // DAT_0000d168: upload finalization timing threshold
        0x20000307L, // DAT_0000d160: upload phase
        0x20000310L, // DAT_0000d174: upload offset
        0x20000314L, // DAT_0000d178: erase selector
        0x20000316L, // DAT_0000d16c: erase count
        0x20000318L, // DAT_0000d184: page count
        0x20000321L  // DAT_0000d164: upload pending/finalize gate
    };

    private boolean isTarget(long v) {
        long vv = v & 0xffffffffL;
        for (long t : targets) {
            if (vv == (t & 0xffffffffL)) return true;
        }
        return false;
    }

    private String hx(long v) {
        return String.format("0x%08X", v & 0xffffffffL);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_timing_field_xrefs_" + tag + ".txt");

        Set<Function> funcs = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP timing/finalization field xrefs");
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
                                pw.printf("%s scalar=%s ins=%s fn=%s%n",
                                    ins.getAddress(),
                                    hx(v),
                                    ins,
                                    fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                            }
                        }
                    }
                }
            }

            pw.println();
            pw.println("## References to pointer literals / target addresses");
            long[] pointerLiteralAddrs = new long[] {
                0x0000d168L, 0x0000d160L, 0x0000d174L, 0x0000d178L,
                0x0000d16cL, 0x0000d184L, 0x0000d164L,
                0x00008248L
            };
            for (long t : pointerLiteralAddrs) {
                Address a = toAddr(t);
                pw.println("### " + a);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(a);
                for (Reference ref : refs) {
                    Function fn = getFunctionContaining(ref.getFromAddress());
                    if (fn != null) funcs.add(fn);
                    pw.printf("from=%s type=%s fn=%s%n",
                        ref.getFromAddress(),
                        ref.getReferenceType(),
                        fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint());
                }
            }

            pw.println();
            pw.println("## Decompilation of hit functions");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            for (Function fn : funcs) {
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
