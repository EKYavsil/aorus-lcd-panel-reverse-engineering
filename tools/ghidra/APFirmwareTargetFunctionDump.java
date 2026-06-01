// Ghidra headless script: dump selected AP firmware functions by address.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwareTargetFunctionDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_target_function_dump_" + tag + ".txt");

        long[] targets = new long[] {
            0x09354, 0x09620, 0x09658, 0x096a8, 0x097a4, 0x097e8,
            0x09a14, 0x09a44, 0x09b90, 0x09b9c, 0x09ba8, 0x09bb8,
            0x09bc6, 0x09bd6, 0x09be6, 0x09bf6, 0x09c14,
            0x03c70, 0x03d24, 0x03da8, 0x03e2c, 0x03eb0,
            0x03ce8, 0x06888, 0x06aa0, 0x06b9c, 0x06bf4, 0x06ccc,
            0x06e40, 0x07988,
            0x09144, 0x09240, 0x09284, 0x06814, 0x06850, 0x068cc, 0x066e8,
            0x07538, 0x06504,
            0x091ec, 0x092e8, 0x0932c, 0x0684c, 0x06888, 0x06904, 0x06720,
            0x07570, 0x0653c
        };

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            for (long off : targets) {
                dumpFunction(pw, ifc, off);
            }
            ifc.dispose();
        }
        println("wrote " + out.getAbsolutePath());
    }

    private void dumpFunction(PrintWriter pw, DecompInterface ifc, long off) {
        Address a = addr(off);
        Function f = getFunctionAt(a);
        if (f == null) {
            pw.println("### missing function at " + a);
            pw.println();
            return;
        }

        pw.println("### " + f.getName() + " @ " + f.getEntryPoint() + " body=" + f.getBody());
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
        while (refs.hasNext()) {
            Reference r = refs.next();
            Function caller = getFunctionContaining(r.getFromAddress());
            pw.printf("caller from=%s type=%s fn=%s%n", r.getFromAddress(), r.getReferenceType(),
                caller == null ? "<none>" : caller.getName() + "@" + caller.getEntryPoint());
        }

        DecompileResults res = ifc.decompileFunction(f, 45, monitor);
        if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed: " + res.getErrorMessage() + ">");
        }
        pw.println();
    }
}
