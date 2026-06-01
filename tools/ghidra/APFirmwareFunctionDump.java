// Ghidra headless script: decompile specific AP firmware functions and their callers.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class APFirmwareFunctionDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_function_dump_" + tag + ".txt");

        long[] targets = new long[] {
            0x6aa0, 0x6ccc, 0x6e40, 0x7188, 0x71cc, 0x7988, 0x7bbc, 0x7bfc,
            0x7db8, 0x91e8, 0x9260, 0x9330, 0x9a44, 0x9c14, 0x9cf8, 0x9d88,
            0xacc4, 0xb400, 0xb410, 0xb45c, 0xb5d8, 0xb760, 0xb990, 0xb9c4,
            0xbb94, 0xbcd8, 0xc170, 0xc370, 0xcb54
        };

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            Set<Function> callerSet = new LinkedHashSet<>();
            pw.println("# Direct target functions");
            for (long off : targets) {
                Function f = getFunctionAt(addr(off));
                if (f == null) {
                    pw.println("## missing function at " + addr(off));
                    continue;
                }
                dumpFunction(pw, ifc, f);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function caller = getFunctionContaining(r.getFromAddress());
                    if (caller != null) {
                        callerSet.add(caller);
                    }
                }
            }

            pw.println("# Callers of target functions");
            for (Function f : callerSet) {
                dumpFunction(pw, ifc, f);
            }

            ifc.dispose();
        }
        println("wrote " + out.getAbsolutePath());
    }

    private void dumpFunction(PrintWriter pw, DecompInterface ifc, Function f) {
        pw.println("### " + f.getName() + " @ " + f.getEntryPoint() + " body=" + f.getBody());
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
        while (refs.hasNext()) {
            Reference r = refs.next();
            Function caller = getFunctionContaining(r.getFromAddress());
            pw.printf("caller from=%s type=%s fn=%s%n", r.getFromAddress(), r.getReferenceType(),
                caller == null ? "<none>" : caller.getName() + "@" + caller.getEntryPoint());
        }
        DecompileResults res = ifc.decompileFunction(f, 30, monitor);
        if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed: " + res.getErrorMessage() + ">");
        }
        pw.println();
    }
}
