// Ghidra headless post-script. Static analysis only.
// Dumps callers of the old Panel.exe send-image function and decompiled context.

import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class AorusPanelCallerDump extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decomp;

    @Override
    public void run() throws Exception {
        File dir = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output/ghidra_panel_callers");
        dir.mkdirs();
        File report = new File(dir, currentProgram.getName().replaceAll("[^A-Za-z0-9_.-]", "_") + "_sendimage_callers.txt");
        out = new PrintWriter(report, "UTF-8");

        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        out.println("Program: " + currentProgram.getName());
        out.println("Path: " + currentProgram.getExecutablePath());
        out.println();

        dumpTarget("Old Panel.exe SendImage-like FUN_00406bb0", "00406bb0");
        dumpTarget("Old Panel.exe raw write wrapper FUN_004069b0", "004069b0");
        dumpTarget("Old Panel.exe raw read wrapper FUN_00406ab0", "00406ab0");

        decomp.dispose();
        out.close();
        println("Wrote " + report.getAbsolutePath());
    }

    private void dumpTarget(String label, String addressText) throws Exception {
        Address target = toAddr(addressText);
        Function targetFn = getFunctionAt(target);
        out.println("=== " + label + " @ " + target + " ===");
        out.println("Target function: " + (targetFn == null ? "<none>" : targetFn.getName()));
        out.println();
        if (targetFn != null) {
            out.println("--- Target Decompile ---");
            dumpDecompile(targetFn);
            out.println();
        }

        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target);
        Set<String> seen = new HashSet<String>();
        int count = 0;
        while (refs.hasNext() && !monitor.isCancelled()) {
            Reference r = refs.next();
            Address from = r.getFromAddress();
            Function caller = currentProgram.getListing().getFunctionContaining(from);
            count++;
            out.println("--- XREF " + count + " from " + from + " type=" + r.getReferenceType() +
                " caller=" + (caller == null ? "<none>" : caller.getName() + " @ " + caller.getEntryPoint()) + " ---");
            dumpInstructionsBeforeCall(from, 45);
            if (caller != null) {
                String key = caller.getEntryPoint().toString();
                if (!seen.contains(key)) {
                    seen.add(key);
                    dumpDecompile(caller);
                }
            }
            out.println();
        }
        out.println("xref_count=" + count);
        out.println();
    }

    private void dumpInstructionsBeforeCall(Address callAddress, int count) {
        out.println("Instructions before/around call:");
        Instruction cur = currentProgram.getListing().getInstructionAt(callAddress);
        if (cur == null) {
            cur = currentProgram.getListing().getInstructionBefore(callAddress);
        }
        if (cur == null) {
            out.println("  <no instruction>");
            return;
        }
        for (int i = 0; i < count; i++) {
            Instruction prev = currentProgram.getListing().getInstructionBefore(cur.getAddress());
            if (prev == null) break;
            cur = prev;
        }
        for (int i = 0; i < count + 12 && cur != null; i++) {
            out.println("  " + cur.getAddress() + ": " + cur);
            cur = currentProgram.getListing().getInstructionAfter(cur.getAddress());
        }
    }

    private void dumpDecompile(Function fn) {
        try {
            DecompileResults res = decomp.decompileFunction(fn, 60, monitor);
            if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            } else {
                out.println("<decompile failed>");
            }
        } catch (Exception e) {
            out.println("<decompile exception: " + e.getMessage() + ">");
        }
    }
}
