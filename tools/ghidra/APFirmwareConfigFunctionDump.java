// Ghidra headless script: dump AP config/state load/save functions.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwareConfigFunctionDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_config_function_dump_" + tag + ".txt");

        long[] targets = new long[] {
            0x017d4, 0x01ac4, 0x036e0, 0x036f0, 0x038ac,
            0x06928, 0x0b45c, 0x0b54c, 0x0bb94, 0x0bcd8,
            0x091e8, 0x09d88, 0x0cb54
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

        DecompileResults res = ifc.decompileFunction(f, 60, monitor);
        if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed: " + res.getErrorMessage() + ">");
        }
        pw.println();
    }
}
