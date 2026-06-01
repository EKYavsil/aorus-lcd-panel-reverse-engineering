// Ghidra headless script: dump references around AP firmware command/session globals.
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
import java.util.LinkedHashSet;
import java.util.Set;

public class APFirmwareXrefDump extends GhidraScript {
    private Address addr(long off) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(off);
    }

    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_xref_dump_" + tag + ".txt");

        long[] addrs = new long[] {
            0x81b8, 0x81bc, 0x81c0, 0x81c4, 0x81c8, 0x81cc, 0x81d0,
            0x8204, 0x8208, 0x820c, 0x8210, 0x8214, 0x8218, 0x821c,
            0x8220, 0x8224, 0x8228, 0x8230, 0x8238, 0x823c, 0x8240, 0x8244,
            0x86a0, 0x86c8, 0x86ec, 0x86f0,
            0x9000, 0x904c, 0x9068, 0x90a8, 0x90c8,
            0x91a8, 0x91ac, 0x91b0, 0x91b4, 0x91b8, 0x91bc, 0x91c0,
            0x91c4, 0x91c8, 0x91cc, 0x91d0, 0x91d4, 0x91d8, 0x91dc, 0x91e0, 0x91e4,
            0xcf74, 0xcf78, 0xcf7c, 0xcf80, 0xcf84, 0xcf88, 0xcf8c, 0xcf90, 0xcf94, 0xcf98,
            0xcfa0, 0xcfa4, 0xcfa8, 0xcfac, 0xcfb0, 0xcfb4, 0xcfb8, 0xcfbc, 0xcfc0, 0xcfc4
        };

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP firmware xref dump");
            pw.println("program=" + currentProgram.getName());
            pw.println();

            Set<Function> funcs = new LinkedHashSet<>();
            for (long off : addrs) {
                Address a = addr(off);
                pw.println("## refs to " + a);
                ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(a);
                while (refs.hasNext()) {
                    Reference r = refs.next();
                    Function f = getFunctionContaining(r.getFromAddress());
                    pw.printf("from=%s type=%s fn=%s%n", r.getFromAddress(), r.getReferenceType(),
                        f == null ? "<none>" : f.getName() + "@" + f.getEntryPoint());
                    if (f != null) {
                        funcs.add(f);
                    }
                }
                pw.println();
            }

            pw.println("# decompile referenced functions");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            int count = 0;
            for (Function f : funcs) {
                if (count++ > 120) {
                    pw.println("decompile limit reached");
                    break;
                }
                pw.println("### " + f.getName() + " @ " + f.getEntryPoint());
                DecompileResults res = ifc.decompileFunction(f, 20, monitor);
                if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed: " + res.getErrorMessage() + ">");
                }
                pw.println();
            }
            ifc.dispose();
        }
        println("wrote " + out.getAbsolutePath());
    }
}
