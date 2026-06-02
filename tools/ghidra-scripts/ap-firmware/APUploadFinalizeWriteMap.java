// Ghidra headless script: upload/GIF finalization state write-map.
// Offline analysis only. It does not modify the program.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class APUploadFinalizeWriteMap extends GhidraScript {
    private static class Field {
        final String name;
        final long ram;
        final long[] ptrs;

        Field(String name, long ram, long... ptrs) {
            this.name = name;
            this.ram = ram;
            this.ptrs = ptrs;
        }
    }

    private String hx(long v) {
        return String.format("0x%08X", v & 0xffffffffL);
    }

    private String fnName(Function fn) {
        return fn == null ? "<none>" : fn.getName() + "@" + fn.getEntryPoint();
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outDir = args.length > 0 ? args[0] : ".";
        String tag = args.length > 1 ? args[1] : currentProgram.getName();
        File out = new File(outDir, "ap_upload_finalize_write_map_" + tag + ".txt");

        Field[] fields = new Field[] {
            new Field("state+0x18 active_delay", 0x20000028L, 0x00003b4cL, 0x00008230L, 0x000090b8L, 0x0000bf6cL),
            new Field("state+0x19 custom_slot_selector", 0x20000029L, 0x00003bccL, 0x000081c4L, 0x00009064L, 0x000091b8L, 0x0000bfecL),
            new Field("state+0x1a slot2_ready", 0x2000002aL, 0x00003bd0L, 0x00008260L, 0x00009070L, 0x000091c0L, 0x0000bff0L),
            new Field("state+0x1b slot1_ready", 0x2000002bL, 0x00003bd4L, 0x00008264L, 0x0000906cL, 0x000091bcL, 0x0000bff4L),
            new Field("state+0x2e upload_display_state", 0x2000003eL, 0x00008244L, 0x000090bcL),
            new Field("state+0x32 upload_run_flag", 0x20000042L, 0x000086f4L, 0x0000904cL),
            new Field("state+0x3d refresh_dirty", 0x2000004dL, 0x000086f0L, 0x00008b88L, 0x000090c0L),
            new Field("state+0x3f display_dirty", 0x2000004fL, 0x00008220L, 0x00009080L, 0x000091d0L),
            new Field("state+0x47 gif_fail_latch", 0x20000057L, 0x0000905cL, 0x000091b0L),
            new Field("state+0x64 frame_timer", 0x20000074L, 0x00001948L),
            new Field("state+0x66 active_frame_count", 0x20000076L, 0x00003b50L, 0x00008228L, 0x000090b0L, 0x0000bf70L),
            new Field("state+0x68 pending_delay", 0x20000078L, 0x00008234L, 0x000090b4L),
            new Field("state+0x6a pending_frame_count", 0x2000007aL, 0x0000822cL, 0x000090acL),
            new Field("state+0x98 upload_elapsed", 0x200000a8L, 0x0000195cL, 0x000090c8L),
            new Field("upload_phase", 0x20000307L, 0x00008240L, 0x000090a8L, 0x000091dcL, 0x0000d160L),
            new Field("timing_threshold", 0x20000308L, 0x00008248L, 0x0000d168L),
            new Field("upload_offset", 0x20000310L, 0x0000823cL, 0x0000d174L),
            new Field("erase_selector", 0x20000314L, 0x00008208L, 0x0000d178L),
            new Field("erase_count", 0x20000316L, 0x00008210L, 0x0000d16cL),
            new Field("remaining_pages", 0x20000318L, 0x0000820cL, 0x0000d184L),
            new Field("upload_pending", 0x20000321L, 0x00001954L, 0x0000d164L),
        };

        Set<Function> functions = new LinkedHashSet<>();
        Map<Function, Set<String>> reasons = new LinkedHashMap<>();

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP upload/GIF finalization state write-map");
            pw.println("program=" + currentProgram.getName());
            pw.println();

            for (Field field : fields) {
                pw.printf("## %s ram=%s%n", field.name, hx(field.ram));
                for (long ptr : field.ptrs) {
                    Address a = toAddr(ptr);
                    pw.printf("### pointer_label_addr=%s%n", a);
                    ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(a);
                    int n = 0;
                    while (refs.hasNext()) {
                        Reference ref = refs.next();
                        Function fn = getFunctionContaining(ref.getFromAddress());
                        if (fn != null) {
                            functions.add(fn);
                            reasons.computeIfAbsent(fn, k -> new LinkedHashSet<>()).add(field.name + " via " + a);
                        }
                        Instruction ins = currentProgram.getListing().getInstructionAt(ref.getFromAddress());
                        pw.printf("from=%s refType=%s fn=%s ins=%s%n",
                            ref.getFromAddress(),
                            ref.getReferenceType(),
                            fnName(fn),
                            ins == null ? "<no instruction>" : ins.toString());
                        n++;
                    }
                    if (n == 0) {
                        pw.println("<no refs>");
                    }
                }
                pw.println();
            }

            pw.println("# Decompilation of referenced functions");
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            for (Function fn : functions) {
                pw.println("================================================================================");
                pw.println("## " + fnName(fn));
                Set<String> rs = reasons.get(fn);
                if (rs != null) {
                    for (String r : rs) {
                        pw.println("reason=" + r);
                    }
                }
                DecompileResults res = ifc.decompileFunction(fn, 45, monitor);
                if (res != null && res.decompileCompleted()) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    pw.println("<decompile failed>");
                }
                pw.println();
            }
            ifc.dispose();
        }

        println("Wrote " + out.getAbsolutePath());
    }
}
