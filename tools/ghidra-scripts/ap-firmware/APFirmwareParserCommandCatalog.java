// Ghidra headless script: dump AP parser command/opcode evidence around FUN_00007db8.
// Offline-only. Writes a text report; does not modify the program.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class APFirmwareParserCommandCatalog extends GhidraScript {
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
        File out = new File(outDir, "ap_parser_command_catalog_" + tag + ".txt");

        Function parser = fn(0x7db8);
        Function mainLoop = fn(0xcb54);

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP parser command catalog");
            pw.println("program=" + currentProgram.getName());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println("parser=" + (parser == null ? "<missing>" : parser.getName() + "@" + parser.getEntryPoint()));
            pw.println("mainLoop=" + (mainLoop == null ? "<missing>" : mainLoop.getName() + "@" + mainLoop.getEntryPoint()));
            pw.println();

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            if (parser != null) {
                dumpFunction(pw, ifc, "Parser", parser);
                dumpConstantsInFunction(pw, parser);
            }
            if (mainLoop != null) {
                dumpFunction(pw, ifc, "MainLoop", mainLoop);
            }

            pw.println();
            pw.println("## Functions mentioning command/flash-related helper names in decompile");
            String[] needles = new String[] {
                "FUN_0000b54c", "FUN_0000b760", "FUN_0000b910", "FUN_0000b4d0", "FUN_0000b6cc",
                "FUN_0000ba44", "FUN_00009330", "DAT_000081cc", "DAT_000086a0", "DAT_00008258"
            };
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Function f = it.next();
                DecompileResults res = ifc.decompileFunction(f, 30, monitor);
                if (res == null || !res.decompileCompleted() || res.getDecompiledFunction() == null) {
                    continue;
                }
                String c = res.getDecompiledFunction().getC();
                boolean hit = false;
                for (String n : needles) {
                    if (c.contains(n)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
                pw.println("### " + f.getName() + " @ " + f.getEntryPoint() + " body=" + f.getBody());
                for (String n : needles) {
                    if (c.contains(n)) {
                        pw.println("contains=" + n);
                    }
                }
                pw.println();
            }

            ifc.dispose();
        }

        println("Wrote " + out.getAbsolutePath());
    }

    private void dumpFunction(PrintWriter pw, DecompInterface ifc, String title, Function f) {
        pw.println("## " + title + " " + f.getName() + " @ " + f.getEntryPoint());
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
        DecompileResults res = ifc.decompileFunction(f, 120, monitor);
        if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
            pw.println(res.getDecompiledFunction().getC());
        } else {
            pw.println("<decompile failed>");
        }
        pw.println();
    }

    private void dumpConstantsInFunction(PrintWriter pw, Function f) {
        pw.println("## Parser scalar constants");
        Map<Long, TreeSet<String>> hits = new TreeMap<>();
        Instruction ins = getInstructionAt(f.getEntryPoint());
        int count = 0;
        while (ins != null && f.getBody().contains(ins.getAddress()) && count < 4000 && !monitor.isCancelled()) {
            for (int i = 0; i < ins.getNumOperands(); i++) {
                Object[] objs = ins.getOpObjects(i);
                for (Object o : objs) {
                    if (o instanceof Scalar) {
                        long v = ((Scalar)o).getUnsignedValue();
                        if ((v >= 0 && v <= 0xff) || v == 0x1300000L || v == 0x1320000L || v == 0x1000000L || v == 0x20000L) {
                            hits.computeIfAbsent(v, k -> new TreeSet<>()).add(ins.getAddress() + ": " + ins.toString());
                        }
                    }
                }
            }
            ins = ins.getNext();
            count++;
        }
        for (Map.Entry<Long, TreeSet<String>> e : hits.entrySet()) {
            pw.printf("### 0x%02x (%d)%n", e.getKey(), e.getKey());
            int n = 0;
            for (String s : e.getValue()) {
                pw.println(s);
                if (++n >= 40) {
                    pw.println("... truncated");
                    break;
                }
            }
        }
        pw.println();
    }
}
