// Decompile selected functions by name and print call references.
// @category AORUS

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.symbol.Reference;

public class DecompileNamedFunctions extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] names = getScriptArgs();
        if (names == null || names.length == 0) {
            names = new String[] { "GvWriteI2C", "GvReadI2C", "ctlI2CAccess" };
        }

        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);

        for (String wanted : names) {
            println("===== SEARCH " + wanted + " =====");
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext()) {
                Function f = it.next();
                if (!f.getName().equals(wanted) && !f.getName().contains(wanted)) {
                    continue;
                }

                println("===== FUNCTION " + f.getName() + " @ " + f.getEntryPoint() + " =====");
                println("signature: " + f.getSignature());
                Reference[] refs = currentProgram.getReferenceManager().getReferencesFrom(f.getEntryPoint());
                for (Reference r : refs) {
                    println("entry_ref_from: " + r.toString());
                }

                DecompileResults res = ifc.decompileFunction(f, 90, monitor);
                if (res != null && res.decompileCompleted()) {
                    println(res.getDecompiledFunction().getC());
                } else {
                    println("DECOMPILE_FAILED: " + (res == null ? "<null>" : res.getErrorMessage()));
                }

                println("----- CALLED FUNCTIONS -----");
                for (Function callee : f.getCalledFunctions(monitor)) {
                    println(callee.getName() + " @ " + callee.getEntryPoint());
                }
            }
        }

        ifc.dispose();
    }
}
