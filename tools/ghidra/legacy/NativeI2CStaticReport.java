// @category Aorus

import java.util.*;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.StringDataInstance;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.ExternalLocation;
import ghidra.program.model.symbol.ExternalManager;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.task.ConsoleTaskMonitor;

public class NativeI2CStaticReport extends GhidraScript {
    private final String[] patterns = {
        "I2C", "DDC", "NVAPI", "NvAPI", "Boost", "Intel", "ctlI2CAccess",
        "GvIntelI2C", "GvWriteI2C", "GvReadI2C", "nvapi_Direct", "Direct_Get"
    };

    private final String[] targets = {
        "GvWriteI2C", "GvReadI2C", "GvInitDispLib", "GvFreeDispLib",
        "GvIsNvBoost5", "nvapi_Direct_Get", "nvapi_Direct_GetMethod"
    };

    @Override
    public void run() throws Exception {
        println("# PROGRAM " + currentProgram.getName());
        println("IMAGE_BASE " + currentProgram.getImageBase());
        println("LANGUAGE " + currentProgram.getLanguageID());
        println("COMPILER " + currentProgram.getCompilerSpec().getCompilerSpecID());
        println("");
        printMemory();
        printExports();
        printImports();
        printStrings();
        printTargetFunctions();
    }

    private void printMemory() {
        println("## MEMORY BLOCKS");
        for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
            println(b.getName() + " " + b.getStart() + "-" + b.getEnd() +
                " R" + b.isRead() + " W" + b.isWrite() + " X" + b.isExecute());
        }
        println("");
    }

    private void printExports() {
        println("## EXPORTS");
        SymbolTable st = currentProgram.getSymbolTable();
        SymbolIterator it = st.getAllSymbols(true);
        while (it.hasNext()) {
            Symbol s = it.next();
            if (st.isExternalEntryPoint(s.getAddress())) {
                println(s.getAddress() + " " + s.getName(true));
            }
        }
        println("");
    }

    private void printImports() {
        println("## IMPORTS/EXTERNALS");
        ExternalManager em = currentProgram.getExternalManager();
        for (String lib : em.getExternalLibraryNames()) {
            println("LIB " + lib);
            Iterator<ExternalLocation> it = em.getExternalLocations(lib);
            while (it.hasNext()) {
                ExternalLocation loc = it.next();
                println("  " + loc.getAddress() + " " + loc.getLabel());
            }
        }
        println("");
    }

    private void printStrings() {
        println("## MATCHED STRINGS");
        Iterator<Data> it = currentProgram.getListing().getDefinedData(true);
        while (it.hasNext()) {
            Data d = it.next();
            String value = null;
            try {
                StringDataInstance sdi = StringDataInstance.getStringDataInstance(d);
                if (sdi != null) {
                    value = sdi.getStringValue();
                }
            }
            catch (Exception e) {
                value = null;
            }
            if (value == null) {
                Object raw = d.getValue();
                if (raw instanceof String) {
                    value = (String) raw;
                }
            }
            if (value == null) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            for (String p : patterns) {
                if (lower.contains(p.toLowerCase(Locale.ROOT))) {
                    println(d.getAddress() + " " + value.replace("\n", "\\n"));
                    break;
                }
            }
        }
        println("");
    }

    private void printTargetFunctions() throws Exception {
        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);
        println("## TARGET FUNCTIONS");
        for (String name : targets) {
            List<Function> funcs = findFunctionsByName(name);
            for (Function f : funcs) {
                println("### " + f.getName() + " " + f.getEntryPoint());
                println("SIGNATURE: " + f.getSignature());
                println("CALLS: " + String.join(", ", getCalledNames(f)));
                DecompileResults res = ifc.decompileFunction(f, 90, new ConsoleTaskMonitor());
                if (res != null && res.decompileCompleted()) {
                    println(res.getDecompiledFunction().getC());
                }
                else {
                    println("<decompile failed>");
                }
                println("");
            }
        }
        println("");
    }

    private List<Function> findFunctionsByName(String name) {
        ArrayList<Function> funcs = new ArrayList<>();
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) {
            Function f = it.next();
            if (f.getName().equals(name) || f.getName(true).endsWith("::" + name)) {
                funcs.add(f);
            }
        }
        return funcs;
    }

    private List<String> getCalledNames(Function f) {
        ArrayList<String> names = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        ReferenceManager rm = currentProgram.getReferenceManager();
        InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            for (Reference ref : rm.getReferencesFrom(ins.getAddress())) {
                if (!ref.getReferenceType().isCall()) {
                    continue;
                }
                String name = null;
                Symbol s = getSymbolAt(ref.getToAddress());
                if (s != null) {
                    name = s.getName(true);
                }
                Function callee = getFunctionAt(ref.getToAddress());
                if (callee != null) {
                    name = callee.getName(true);
                }
                if (name == null) {
                    name = ref.getToAddress().toString();
                }
                if (!seen.contains(name)) {
                    seen.add(name);
                    names.add(name);
                }
            }
        }
        return names;
    }
}
