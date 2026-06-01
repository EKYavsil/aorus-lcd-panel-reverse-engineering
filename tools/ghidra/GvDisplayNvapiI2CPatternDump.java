// Ghidra headless post-script.
// Offline-only: finds NVAPI private I2C QueryInterface IDs and decompiles containing functions.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.Memory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class GvDisplayNvapiI2CPatternDump extends GhidraScript {
    private PrintWriter out;

    @Override
    protected void run() throws Exception {
        String root = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output").getPath();
        String md5 = currentProgram.getExecutableMD5();
        String sig = (md5 == null || md5.length() < 8) ? "unknown" : md5.substring(0, 8);
        File outFile = new File(root, "ghidra_nvapi_i2c_patterns_" + currentProgram.getName() + "_" + sig + ".txt");
        out = new PrintWriter(new FileWriter(outFile));
        try {
            out.println("# Program");
            out.println("name=" + currentProgram.getName());
            out.println("md5=" + md5);
            out.println("imageBase=" + currentProgram.getImageBase());
            out.println();

            Map<String, byte[]> patterns = new LinkedHashMap<>();
            patterns.put("NVAPI_PRIVATE_I2C_WRITE_283AC65A_LE", new byte[]{0x5A, (byte)0xC6, 0x3A, 0x28});
            patterns.put("NVAPI_PRIVATE_I2C_READ_4D7B0709_LE", new byte[]{0x09, 0x07, 0x7B, 0x4D});

            Set<Function> targets = new LinkedHashSet<>();
            Memory mem = currentProgram.getMemory();
            out.println("# Pattern hits");
            for (Map.Entry<String, byte[]> e : patterns.entrySet()) {
                out.println("## " + e.getKey());
                Address start = currentProgram.getMinAddress();
                int count = 0;
                while (start != null && count < 100) {
                    Address hit = mem.findBytes(start, currentProgram.getMaxAddress(), e.getValue(), null, true, monitor);
                    if (hit == null) break;
                    Function fn = getFunctionContaining(hit);
                    out.println(hit + " fn=" + (fn == null ? "(none)" : fn.getName(true) + " @ " + fn.getEntryPoint()));
                    if (fn != null) targets.add(fn);
                    start = hit.add(1);
                    count++;
                }
            }
            out.println();

            DecompInterface ifc = new DecompInterface();
            ifc.setOptions(new DecompileOptions());
            ifc.openProgram(currentProgram);
            try {
                out.println("# Target functions count=" + targets.size());
                for (Function fn : targets) {
                    out.println();
                    out.println("## " + fn.getName(true) + " @ " + fn.getEntryPoint());
                    out.println("signature=" + fn.getSignature());
                    out.println("-- instructions --");
                    int n = 0;
                    var insIt = currentProgram.getListing().getInstructions(fn.getBody(), true);
                    while (insIt.hasNext() && n < 260) {
                        Instruction ins = insIt.next();
                        out.println(ins.getAddress() + "  " + ins.toString());
                        n++;
                    }
                    out.println("-- decompile --");
                    DecompileResults res = ifc.decompileFunction(fn, 90, monitor);
                    if (res != null && res.decompileCompleted()) {
                        out.println(res.getDecompiledFunction().getC());
                    } else {
                        out.println("(decompile failed)");
                    }
                }
            } finally {
                ifc.dispose();
            }
        } finally {
            out.close();
        }
        println("Wrote " + outFile.getAbsolutePath());
    }
}
