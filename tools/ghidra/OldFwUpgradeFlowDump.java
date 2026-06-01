// Ghidra headless post-script.
// Offline-only: dumps FWUpgrade.exe flow around I2C wrapper callers and firmware strings.

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;

import java.io.*;
import java.util.*;

public class OldFwUpgradeFlowDump extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decomp;
    private Set<String> dumped = new LinkedHashSet<>();

    @Override
    public void run() throws Exception {
        String root = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output").getPath();
        String md5 = currentProgram.getExecutableMD5();
        String sig = (md5 == null || md5.length() < 8) ? "unknown" : md5.substring(0, 8);
        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(root, "old_fwupgrade_flow_" + currentProgram.getName() + "_" + sig + ".txt")), "UTF-8"));
        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);
        try {
            out.println("# Program");
            out.println("name=" + currentProgram.getName());
            out.println("md5=" + md5);
            out.println("path=" + currentProgram.getExecutablePath());
            out.println("imageBase=" + currentProgram.getImageBase());
            out.println();

            dumpTarget("read_wrapper", toAddr("00401be0"));
            dumpTarget("write_wrapper", toAddr("00401f20"));
            dumpStringRefs();
            dumpImportantConstants();
        } finally {
            decomp.dispose();
            out.close();
        }
        println("Wrote old FWUpgrade flow dump");
    }

    private void dumpTarget(String label, Address addr) {
        out.println();
        out.println("=== TARGET " + label + " " + addr + " ===");
        Function f = getFunctionContaining(addr);
        if (f != null) dumpFunction("target", f);
        dumpReferencesToAddress(addr, 2);
    }

    private void dumpReferencesToAddress(Address addr, int depth) {
        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(addr);
        int n = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Reference r = it.next();
            n++;
            Function caller = getFunctionContaining(r.getFromAddress());
            out.println();
            out.println("--- ref " + n + " to " + addr + " from=" + r.getFromAddress() + " type=" + r.getReferenceType() +
                " caller=" + (caller == null ? "<none>" : caller.getName() + " @ " + caller.getEntryPoint()));
            dumpWindow(r.getFromAddress(), 24, 36);
            if (caller != null) {
                dumpFunction("caller", caller);
                if (depth > 0) dumpReferencesToAddress(caller.getEntryPoint(), depth - 1);
            }
            if (n >= 80) {
                out.println("<refs truncated>");
                break;
            }
        }
        out.println("ref_count=" + n);
    }

    private void dumpStringRefs() {
        out.println();
        out.println("=== STRING REFS ===");
        String[] needles = {
            "Erasing", "Flash", "Flashing", "Upgrade", "Successfully", "Failed", "IAP", "AP", "ADC",
            "FW", "12345", "12365", "brightness", "test", "Firmware"
        };
        var dataIt = currentProgram.getListing().getDefinedData(true);
        int reported = 0;
        while (dataIt.hasNext() && !monitor.isCancelled()) {
            Data d = dataIt.next();
            if (!d.hasStringValue()) continue;
            String s = String.valueOf(d.getValue());
            boolean hit = false;
            for (String n : needles) {
                if (s.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(d.getAddress());
            int count = 0;
            while (refs.hasNext() && !monitor.isCancelled()) {
                Reference r = refs.next();
                count++;
                Function f = getFunctionContaining(r.getFromAddress());
                out.println();
                out.println("STRING @" + d.getAddress() + " = " + s.replace("\r","\\r").replace("\n","\\n"));
                out.println("  ref from=" + r.getFromAddress() + " caller=" + (f == null ? "<none>" : f.getName() + " @ " + f.getEntryPoint()));
                dumpWindow(r.getFromAddress(), 12, 22);
                if (f != null) dumpFunction("string-caller", f);
            }
            if (count > 0) reported++;
            if (reported >= 120) {
                out.println("<string output truncated>");
                break;
            }
        }
    }

    private void dumpImportantConstants() throws Exception {
        out.println();
        out.println("=== IMPORTANT BYTE PATTERN HITS ===");
        Map<String, byte[]> pats = new LinkedHashMap<>();
        pats.put("le_8101", new byte[]{0x01, (byte)0x81});
        pats.put("le_8203", new byte[]{0x03, (byte)0x82});
        pats.put("le_8104", new byte[]{0x04, (byte)0x81});
        pats.put("le_8204", new byte[]{0x04, (byte)0x82});
        pats.put("le_8102", new byte[]{0x02, (byte)0x81});
        pats.put("le_1000", new byte[]{0x00, 0x10, 0x00, 0x00});
        pats.put("le_EFFF", new byte[]{(byte)0xff, (byte)0xef, 0x00, 0x00});
        pats.put("addr_44", new byte[]{0x44});
        pats.put("addr_46", new byte[]{0x46});
        pats.put("addr_C2", new byte[]{(byte)0xc2});
        Memory mem = currentProgram.getMemory();
        for (Map.Entry<String, byte[]> e : pats.entrySet()) {
            out.println("## " + e.getKey());
            Address start = currentProgram.getMinAddress();
            int count = 0;
            while (start != null && count < 80 && !monitor.isCancelled()) {
                Address hit = mem.findBytes(start, currentProgram.getMaxAddress(), e.getValue(), null, true, monitor);
                if (hit == null) break;
                Function f = getFunctionContaining(hit);
                out.println("  " + hit + " fn=" + (f == null ? "<none>" : f.getName() + " @ " + f.getEntryPoint()));
                start = hit.add(1);
                count++;
            }
        }
    }

    private void dumpFunction(String label, Function f) {
        String key = label + ":" + f.getEntryPoint();
        if (!dumped.add(key)) return;
        out.println();
        out.println("### " + label + " " + f.getName() + " @ " + f.getEntryPoint());
        try {
            DecompileResults res = decomp.decompileFunction(f, 90, monitor);
            if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            } else {
                out.println("<decompile failed>");
            }
        } catch (Exception e) {
            out.println("<decompile exception: " + e.getMessage() + ">");
        }
    }

    private void dumpWindow(Address addr, int before, int after) {
        Instruction cur = currentProgram.getListing().getInstructionAt(addr);
        if (cur == null) cur = currentProgram.getListing().getInstructionBefore(addr);
        if (cur == null) return;
        for (int i = 0; i < before; i++) {
            Instruction p = currentProgram.getListing().getInstructionBefore(cur.getAddress());
            if (p == null) break;
            cur = p;
        }
        for (int i = 0; i < before + after && cur != null; i++) {
            out.println("  " + cur.getAddress() + ": " + cur);
            cur = currentProgram.getListing().getInstructionAfter(cur.getAddress());
        }
    }
}
