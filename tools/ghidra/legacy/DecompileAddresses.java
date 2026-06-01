// @category Aorus

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.util.task.ConsoleTaskMonitor;

public class DecompileAddresses extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);
        for (String a : args) {
            Address addr = toAddr(a);
            Function f = getFunctionAt(addr);
            if (f == null) {
                f = getFunctionContaining(addr);
            }
            println("## " + a + " " + (f == null ? "<no function>" : f.getName(true) + " " + f.getEntryPoint()));
            if (f != null) {
                println("SIGNATURE: " + f.getSignature());
                DecompileResults res = ifc.decompileFunction(f, 120, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    println(res.getDecompiledFunction().getC());
                }
                else {
                    println("<decompile failed>");
                }
            }
        }
    }
}
