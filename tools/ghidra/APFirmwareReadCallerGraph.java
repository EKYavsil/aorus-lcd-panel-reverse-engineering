// Ghidra headless script: recursively dump caller graph for AP SPI read functions.
// Offline analysis only.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class APFirmwareReadCallerGraph extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_read_caller_graph_" + tag + ".txt");

        long[] seeds = new long[] { 0xb54c, 0xb760 };
        int maxDepth = 5;

        Map<Function, Integer> depth = new LinkedHashMap<>();
        Queue<Function> q = new ArrayDeque<>();
        for (long s : seeds) {
            Function f = getFunctionAt(addr(s));
            if (f != null) {
                depth.put(f, 0);
                q.add(f);
            }
        }

        Map<Function, Set<Function>> callers = new LinkedHashMap<>();
        while (!q.isEmpty()) {
            Function f = q.remove();
            int d = depth.get(f);
            if (d >= maxDepth) continue;
            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint());
            while (refs.hasNext()) {
                Reference r = refs.next();
                Function c = getFunctionContaining(r.getFromAddress());
                if (c == null) continue;
                callers.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(c);
                if (!depth.containsKey(c)) {
                    depth.put(c, d + 1);
                    q.add(c);
                }
            }
        }

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP SPI read caller graph");
            pw.println("program=" + currentProgram.getName());
            pw.println("maxDepth=" + maxDepth);
            pw.println();

            pw.println("## Nodes");
            for (Map.Entry<Function, Integer> e : depth.entrySet()) {
                pw.printf("depth=%d %s@%s body=%s%n", e.getValue(), e.getKey().getName(), e.getKey().getEntryPoint(), e.getKey().getBody());
            }
            pw.println();

            pw.println("## Edges callee <- caller");
            for (Map.Entry<Function, Set<Function>> e : callers.entrySet()) {
                for (Function c : e.getValue()) {
                    pw.printf("%s@%s <- %s@%s%n", e.getKey().getName(), e.getKey().getEntryPoint(), c.getName(), c.getEntryPoint());
                }
            }
            pw.println();

            pw.println("## Decompile selected nodes");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            for (Map.Entry<Function, Integer> e : depth.entrySet()) {
                Function f = e.getKey();
                if (e.getValue() <= 2 || f.getEntryPoint().toString().equals("00007db8") || f.getEntryPoint().toString().equals("0000cb54")) {
                    pw.println("### depth=" + e.getValue() + " " + f.getName() + " @ " + f.getEntryPoint());
                    DecompileResults res = ifc.decompileFunction(f, 45, monitor);
                    if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                        pw.println(res.getDecompiledFunction().getC());
                    } else {
                        pw.println("<decompile failed>");
                    }
                }
            }
            ifc.dispose();
        }

        println("Wrote " + out.getAbsolutePath());
    }
}
