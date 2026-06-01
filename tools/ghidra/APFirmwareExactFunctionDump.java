// Ghidra headless script: dump exact AP firmware functions supplied by address.
// Offline-only: reads the loaded Ghidra program and writes decompiled text.

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

public class APFirmwareExactFunctionDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outDir = args.length > 0 ? args[0] : ".";
        String tag = args.length > 1 ? args[1] : currentProgram.getName();
        File out = new File(outDir, "ap_exact_function_dump_" + tag + ".txt");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            pw.println("# AP exact function dump");
            pw.println("program=" + currentProgram.getName());
            pw.println("tag=" + tag);
            pw.println();

            for (int i = 2; i < args.length; i++) {
                long off = Long.decode(args[i]);
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
            f = getFunctionContaining(a);
        }

        pw.println("================================================================================");
        pw.printf("requested=0x%08x address=%s%n", off, a);

        if (f == null) {
            pw.println("function=<missing>");
            pw.println();
            return;
        }

        pw.println("function=" + f.getName() + " entry=" + f.getEntryPoint() + " body=" + f.getBody());
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
        while (refs.hasNext()) {
            Reference r = refs.next();
            Function caller = getFunctionContaining(r.getFromAddress());
            pw.printf("caller from=%s type=%s fn=%s%n", r.getFromAddress(), r.getReferenceType(),
                caller == null ? "<none>" : caller.getName() + "@" + caller.getEntryPoint());
        }

        pw.println("-- disasm head --");
        Instruction ins = getInstructionAt(f.getEntryPoint());
        int count = 0;
        while (ins != null && f.getBody().contains(ins.getAddress()) && count < 80) {
            pw.println(ins.getAddress() + ": " + ins);
            ins = ins.getNext();
            count++;
        }

        pw.println("-- decompile --");
        DecompileResults res = ifc.decompileFunction(f, 90, monitor);
        if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed: " + res.getErrorMessage() + ">");
        }
        pw.println();
    }
}
