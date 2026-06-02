// Ghidra headless script: dump receive/parser-adjacent helpers for AP GIF upload analysis.
// Offline-only. Does not modify the program.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;

public class APReceivePathExactDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    private Function fn(long off) {
        Function f = getFunctionAt(addr(off));
        if (f == null) {
            f = getFunctionContaining(addr(off));
        }
        return f;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outDir = args.length > 0 ? args[0] : ".";
        String tag = args.length > 1 ? args[1] : currentProgram.getName();
        File out = new File(outDir, "ap_receive_path_exact_dump_" + tag + ".txt");

        long[] targets = new long[] {
            0x01cf0L, // called immediately before parser in main loop
            0x091e8L, // response/receive state helper
            0x09260L, // timeout/receive helper called before 091e8
            0x09330L, // response builder used by read commands
            0x0c370L, // watchdog/timer service called through loop
            0x07db8L, // parser for local context
            0x0cb54L  // main loop for local context
        };

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP receive path exact dump");
            pw.println("program=" + currentProgram.getName());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println();

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            for (long t : targets) {
                Function f = fn(t);
                if (f == null) {
                    pw.printf("## 0x%08x <missing>%n%n", t);
                    continue;
                }
                dumpFunction(pw, ifc, f);
            }

            ifc.dispose();
        }

        println("Wrote " + out.getAbsolutePath());
    }

    private void dumpFunction(PrintWriter pw, DecompInterface ifc, Function f) throws Exception {
        pw.println("## " + f.getName() + " @ " + f.getEntryPoint());
        pw.println("body=" + f.getBody());
        pw.println();

        pw.println("### Callers");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
        while (refs.hasNext()) {
            Reference r = refs.next();
            Function caller = getFunctionContaining(r.getFromAddress());
            pw.println(r.getFromAddress() + " " + r.getReferenceType() + " " +
                (caller == null ? "<none>" : caller.getName() + "@" + caller.getEntryPoint()));
        }
        pw.println();

        pw.println("### Decompile");
        DecompileResults res = ifc.decompileFunction(f, 180, monitor);
        if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed>");
        }
        pw.println();

        pw.println("### Instructions");
        Instruction ins = getInstructionAt(f.getEntryPoint());
        int count = 0;
        while (ins != null && f.getBody().contains(ins.getAddress()) && count < 2500 && !monitor.isCancelled()) {
            pw.println(ins.getAddress() + "  " + ins);
            ins = ins.getNext();
            count++;
        }
        pw.println();
    }
}
