import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class APFirmwareOpcode14Search extends GhidraScript {
    private boolean insnHasScalar(Instruction ins, long value) {
        for (int i = 0; i < ins.getNumOperands(); i++) {
            for (Object obj : ins.getOpObjects(i)) {
                if (obj instanceof Scalar) {
                    long v = ((Scalar) obj).getUnsignedValue();
                    if (v == value) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_opcode14_search_" + tag + ".txt");

        Set<Function> funcs = new LinkedHashSet<>();
        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP opcode/constant 0x14 search for " + currentProgram.getName());
            pw.println();

            InstructionIterator it = currentProgram.getListing().getInstructions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Instruction ins = it.next();
                if (insnHasScalar(ins, 0x14) || insnHasScalar(ins, 20) ||
                    insnHasScalar(ins, 0x76) || insnHasScalar(ins, 0xec) ||
                    insnHasScalar(ins, 0xea)) {
                    Function f = getFunctionContaining(ins.getAddress());
                    if (f != null) {
                        funcs.add(f);
                    }
                    pw.printf("INSN %s fn=%s %s%n",
                            ins.getAddress(),
                            f == null ? "<none>" : f.getName() + "@" + f.getEntryPoint(),
                            ins.toString());
                }
            }

            pw.println();
            pw.println("# Candidate function decompilations");
            DecompInterface decomp = new DecompInterface();
            decomp.openProgram(currentProgram);

            for (Function f : funcs) {
                if (monitor.isCancelled()) {
                    break;
                }
                DecompileResults res = decomp.decompileFunction(f, 30, monitor);
                String c = res.decompileCompleted() && res.getDecompiledFunction() != null
                        ? res.getDecompiledFunction().getC()
                        : "<decompile failed>";
                boolean relevant = c.contains("0x14") || c.contains("== 20") ||
                        c.contains("0x76") || c.contains("0xec") || c.contains("0xea") ||
                        c.contains("-0x14");
                if (!relevant) {
                    continue;
                }
                pw.println();
                pw.println("### " + f.getName() + " @ " + f.getEntryPoint() + " body=" + f.getBody());
                pw.println(c);
            }
            decomp.dispose();
        }

        println("wrote " + out.getAbsolutePath());
    }
}
