// Ghidra headless post-script. Static analysis only.
// Dumps xrefs to selected caller functions and surrounding string references.

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class AorusFunctionXrefDump extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decomp;

    @Override
    public void run() throws Exception {
        File dir = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output/ghidra_panel_callers");
        dir.mkdirs();
        out = new PrintWriter(new File(dir, "Panel.exe_selected_function_xrefs.txt"), "UTF-8");
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        dump("SendImage caller A", "004049b0");
        dump("SendImage caller B", "0040f490");
        dump("Post upload command E1", "004074f0");
        dump("Post upload command ? read/current", "004076f0");
        dump("Post upload command ?", "00407780");
        decomp.dispose();
        out.close();
    }

    private void dump(String label, String addrText) throws Exception {
        Address a = toAddr(addrText);
        Function f = getFunctionAt(a);
        out.println("=== " + label + " " + a + " " + (f == null ? "<none>" : f.getName()) + " ===");
        if (f != null) dumpDecompile(f);
        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(a);
        int n = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Reference r = it.next();
            n++;
            Address from = r.getFromAddress();
            Function caller = currentProgram.getListing().getFunctionContaining(from);
            out.println("--- xref " + n + " from " + from + " caller=" + (caller == null ? "<none>" : caller.getName() + " @ " + caller.getEntryPoint()) + " type=" + r.getReferenceType());
            dumpInst(from, 35);
            if (caller != null) dumpDecompile(caller);
        }
        out.println("xref_count=" + n);
        out.println();
    }

    private void dumpInst(Address addr, int n) {
        Instruction cur = currentProgram.getListing().getInstructionAt(addr);
        if (cur == null) cur = currentProgram.getListing().getInstructionBefore(addr);
        if (cur == null) return;
        for (int i = 0; i < n; i++) {
            Instruction p = currentProgram.getListing().getInstructionBefore(cur.getAddress());
            if (p == null) break;
            cur = p;
        }
        for (int i = 0; i < n + 10 && cur != null; i++) {
            out.println("  " + cur.getAddress() + ": " + cur);
            cur = currentProgram.getListing().getInstructionAfter(cur.getAddress());
        }
    }

    private void dumpDecompile(Function f) {
        try {
            DecompileResults res = decomp.decompileFunction(f, 60, monitor);
            if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            } else out.println("<decompile failed>");
        } catch (Exception e) {
            out.println("<decompile exception: " + e.getMessage() + ">");
        }
    }
}
