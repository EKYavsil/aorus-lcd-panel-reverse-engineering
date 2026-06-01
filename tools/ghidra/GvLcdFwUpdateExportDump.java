// Ghidra post-script: dump selected GvLcdFwUpdate.dll exports and helper bodies.
// Read-only analysis script. Writes report under the supplied output directory.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GvLcdFwUpdateExportDump extends GhidraScript {
    private PrintWriter out;
    private DecompInterface ifc;

    private static final String[] TARGET_NAMES = new String[] {
        "I2CInitial",
        "I2CReadFWVersion",
        "I2CAPChangeToIAP",
        "I2CIAPChangeToErase12ByteMode",
        "I2CIAPSetAPFlashTable",
        "I2CIAPSubmitCRCAP",
        "I2CIAPFlashAP12ByteMode",
        "I2CIAPChangeToAP",
        "FUN_180003a70",
        "FUN_180003c30",
        "FUN_180003e90",
        "FUN_180003b30",
        "FUN_180004060",
        "FUN_1800041b0",
        "FUN_1800042b0",
        "FUN_180005040"
    };

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        File outDir;
        if (args.length > 0) {
            outDir = new File(args[0]);
        } else {
            outDir = new File(System.getProperty("user.home"), "Desktop");
        }
        outDir.mkdirs();
        File outFile = new File(outDir, currentProgram.getName() + "_exports_decompile.txt");

        ifc = new DecompInterface();
        DecompileOptions opts = new DecompileOptions();
        ifc.setOptions(opts);
        ifc.openProgram(currentProgram);

        try (PrintWriter pw = new PrintWriter(outFile, "UTF-8")) {
            out = pw;
            out.println("Program: " + currentProgram.getName());
            out.println("Executable path: " + currentProgram.getExecutablePath());
            out.println("Language: " + currentProgram.getLanguageID());
            out.println("Compiler: " + currentProgram.getCompilerSpec().getCompilerSpecID());
            out.println();

            Map<String, Function> targets = resolveTargets();
            out.println("=== Target Resolution ===");
            for (String name : TARGET_NAMES) {
                Function f = targets.get(name);
                out.println(name + " -> " + (f == null ? "<not found>" : (f.getName() + " @ " + f.getEntryPoint())));
            }
            out.println();

            for (String name : TARGET_NAMES) {
                Function f = targets.get(name);
                if (f != null) {
                    dumpFunction(name, f);
                }
            }

            out.println();
            out.println("=== Imports Used By Targets ===");
            dumpImportCallSummary(targets);
        }

        println("Wrote " + outFile.getAbsolutePath());
    }

    private Map<String, Function> resolveTargets() {
        Map<String, Function> map = new LinkedHashMap<>();
        for (String name : TARGET_NAMES) {
            Function f = findFunction(name);
            map.put(name, f);
        }
        return map;
    }

    private Function findFunction(String name) {
        SymbolTable st = currentProgram.getSymbolTable();
        SymbolIterator it = st.getSymbolIterator(name, true);
        while (it.hasNext()) {
            Symbol s = it.next();
            Function f = getFunctionContaining(s.getAddress());
            if (f != null) {
                return f;
            }
        }

        FunctionIterator fit = currentProgram.getFunctionManager().getFunctions(true);
        while (fit.hasNext()) {
            Function f = fit.next();
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private void dumpFunction(String label, Function f) {
        out.println();
        out.println("================================================================================");
        out.println("TARGET " + label);
        out.println("Function: " + f.getName() + " @ " + f.getEntryPoint());
        out.println("Signature: " + f.getSignature());
        out.println("Body: " + f.getBody());
        out.println();

        out.println("--- Interesting Constants / Calls ---");
        dumpInterestingInstructions(f, 500);
        out.println();

        out.println("--- Instructions ---");
        dumpInstructions(f, 260);
        out.println();

        out.println("--- Decompiled C ---");
        DecompileResults res = ifc.decompileFunction(f, 60, monitor);
        if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
            out.println(res.getDecompiledFunction().getC());
        } else {
            out.println("<decompile failed>");
            if (res != null) {
                out.println(res.getErrorMessage());
            }
        }
    }

    private void dumpInterestingInstructions(Function f, int max) {
        AddressSetView body = f.getBody();
        int count = 0;
        for (Instruction ins = getInstructionAt(body.getMinAddress());
             ins != null && body.contains(ins.getAddress()) && count < max;
             ins = ins.getNext()) {
            count++;
            String text = ins.toString().toLowerCase();
            boolean interesting = false;
            StringBuilder why = new StringBuilder();

            if (text.contains("0xc2") || text.contains("0x44") || text.contains("0x46") ||
                text.contains("0xd6") || text.contains("0x19") || text.contains("0x100") ||
                text.contains("0x3e8") || text.contains("0x12") || text.contains("0x0c")) {
                interesting = true;
                why.append(" const");
            }

            for (Reference ref : ins.getReferencesFrom()) {
                Address to = ref.getToAddress();
                Symbol s = currentProgram.getSymbolTable().getPrimarySymbol(to);
                String sym = s == null ? "" : s.getName();
                Function callee = getFunctionAt(to);
                String calleeName = callee == null ? "" : callee.getName();
                if (ref.getReferenceType().isCall() || sym.toLowerCase().contains("gvi2c") ||
                    sym.toLowerCase().contains("gvread") || sym.toLowerCase().contains("gvwrite") ||
                    calleeName.startsWith("FUN_180003b30") || calleeName.startsWith("FUN_180004")) {
                    interesting = true;
                    why.append(" ref=").append(sym.length() > 0 ? sym : calleeName);
                }
            }

            if (interesting) {
                out.println(ins.getAddress() + ": " + ins + " ;" + why.toString());
            }
        }
    }

    private void dumpInstructions(Function f, int max) {
        AddressSetView body = f.getBody();
        int count = 0;
        for (Instruction ins = getInstructionAt(body.getMinAddress());
             ins != null && body.contains(ins.getAddress()) && count < max;
             ins = ins.getNext()) {
            out.println(ins.getAddress() + ": " + ins);
            count++;
        }
        if (count >= max) {
            out.println("<instruction dump truncated at " + max + " instructions>");
        }
    }

    private void dumpImportCallSummary(Map<String, Function> targets) {
        Set<String> emitted = new LinkedHashSet<>();
        for (Map.Entry<String, Function> e : targets.entrySet()) {
            Function f = e.getValue();
            if (f == null) {
                continue;
            }
            AddressSetView body = f.getBody();
            for (Instruction ins = getInstructionAt(body.getMinAddress());
                 ins != null && body.contains(ins.getAddress());
                 ins = ins.getNext()) {
                for (Reference ref : ins.getReferencesFrom()) {
                    if (!ref.getReferenceType().isCall() && ref.getReferenceType() != RefType.COMPUTED_CALL) {
                        continue;
                    }
                    Symbol s = currentProgram.getSymbolTable().getPrimarySymbol(ref.getToAddress());
                    Function callee = getFunctionAt(ref.getToAddress());
                    String name = s != null ? s.getName() : (callee != null ? callee.getName() : ref.getToAddress().toString());
                    String line = e.getKey() + " @ " + ins.getAddress() + " -> " + name;
                    if (emitted.add(line)) {
                        out.println(line);
                    }
                }
            }
        }
    }
}
