// Ghidra headless post-script.
// Offline-only: dumps callers of the NVAPI private I2C wrappers.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class GvDisplayNvapiI2CXrefDump extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decomp;

    @Override
    public void run() throws Exception {
        String root = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output").getPath();
        String md5 = currentProgram.getExecutableMD5();
        String sig = (md5 == null || md5.length() < 8) ? "unknown" : md5.substring(0, 8);
        out = new PrintWriter(new FileWriter(new File(root, "ghidra_nvapi_i2c_xrefs_" + currentProgram.getName() + "_" + sig + ".txt")));
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        try {
            out.println("# Program");
            out.println("name=" + currentProgram.getName());
            out.println("md5=" + md5);
            out.println("imageBase=" + currentProgram.getImageBase());
            out.println();

            dump("private_i2c_write_wrapper", toAddr("180002170"));
            dump("private_i2c_read_wrapper", toAddr("180002250"));
            dump("export_GvWriteI2C_modern", findFunctionAddressByName("GvWriteI2C"));
            dump("export_GvReadI2C_modern", findFunctionAddressByName("GvReadI2C"));
        } finally {
            decomp.dispose();
            out.close();
        }
    }

    private Address findFunctionAddressByName(String name) {
        var symbols = currentProgram.getSymbolTable().getSymbols(name);
        while (symbols.hasNext()) {
            var s = symbols.next();
            Function f = getFunctionAt(s.getAddress());
            if (f != null) return f.getEntryPoint();
        }
        return null;
    }

    private void dump(String label, Address target) {
        out.println();
        out.println("=== " + label + " target=" + target + " ===");
        if (target == null) {
            out.println("<target not found>");
            return;
        }
        Function f = getFunctionAt(target);
        if (f == null) f = getFunctionContaining(target);
        out.println("targetFunction=" + (f == null ? "<none>" : f.getName(true) + " @ " + f.getEntryPoint()));
        if (f != null) dumpDecompile(f);

        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(target);
        int count = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Reference r = it.next();
            count++;
            Address from = r.getFromAddress();
            Function caller = getFunctionContaining(from);
            out.println();
            out.println("--- xref " + count + " from=" + from + " type=" + r.getReferenceType() +
                    " caller=" + (caller == null ? "<none>" : caller.getName(true) + " @ " + caller.getEntryPoint()));
            dumpWindow(from, 18, 28);
            if (caller != null) dumpDecompile(caller);
            if (count >= 80) {
                out.println("<xref output truncated>");
                break;
            }
        }
        out.println("xref_count=" + count);
    }

    private void dumpWindow(Address addr, int before, int after) {
        Instruction cur = currentProgram.getListing().getInstructionAt(addr);
        if (cur == null) cur = currentProgram.getListing().getInstructionBefore(addr);
        if (cur == null) return;
        for (int i = 0; i < before; i++) {
            Instruction p = currentProgram.getListing().getInstructionBefore(cur.getAddress());
            if (p == null) break;
            cur = p;
        }
        for (int i = 0; i < before + after && cur != null; i++) {
            out.println("  " + cur.getAddress() + ": " + cur.toString());
            cur = currentProgram.getListing().getInstructionAfter(cur.getAddress());
        }
    }

    private void dumpDecompile(Function f) {
        try {
            DecompileResults res = decomp.decompileFunction(f, 90, monitor);
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
