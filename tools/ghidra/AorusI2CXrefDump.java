// Ghidra headless post-script. Static analysis only; does not execute target binaries.
// Dumps imports/symbol xrefs related to GIGABYTE LCD I2C paths and decompiled callers.

import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class AorusI2CXrefDump extends GhidraScript {
    private PrintWriter out;
    private Listing listing;
    private ReferenceManager refs;
    private DecompInterface decomp;

    @Override
    public void run() throws Exception {
        String base = new File(System.getProperty("user.home"), "aorus_lcd_ghidra_output/ghidra_i2c_xrefs").getPath();
        File dir = new File(base);
        dir.mkdirs();

        File report = new File(dir, sanitize(currentProgram.getName()) + "_i2c_xrefs.txt");
        out = new PrintWriter(report, "UTF-8");
        listing = currentProgram.getListing();
        refs = currentProgram.getReferenceManager();

        decomp = new DecompInterface();
        decomp.setOptions(new DecompileOptions());
        decomp.openProgram(currentProgram);

        out.println("Program: " + currentProgram.getName());
        out.println("Executable path: " + currentProgram.getExecutablePath());
        out.println("Language: " + currentProgram.getLanguageID());
        out.println("Compiler: " + currentProgram.getCompilerSpec().getCompilerSpecID());
        out.println();

        dumpSymbolXrefs();
        dumpPatternHits();
        dumpKeywordStringRefs();

        decomp.dispose();
        out.close();
        println("Wrote " + report.getAbsolutePath());
    }

    private void dumpSymbolXrefs() throws Exception {
        String[] needles = new String[] {
            "GvWriteI2C", "GvReadI2C", "GvGetActivity", "NvAPI_I2C", "ADL2_Adapter_I2C",
            "I2C", "WriteI2C", "ReadI2C"
        };

        out.println("=== Symbol/Xref Scan ===");
        SymbolTable table = currentProgram.getSymbolTable();
        SymbolIterator it = table.getAllSymbols(true);
        Set<String> dumpedFuncs = new HashSet<String>();

        while (it.hasNext() && !monitor.isCancelled()) {
            Symbol s = it.next();
            String name = s.getName(true);
            if (!containsAny(name, needles)) {
                continue;
            }

            out.println();
            out.println("SYMBOL " + name + " @ " + s.getAddress() + " source=" + s.getSource());
            ReferenceIterator rIt = refs.getReferencesTo(s.getAddress());
            int count = 0;
            while (rIt.hasNext() && !monitor.isCancelled()) {
                Reference r = rIt.next();
                count++;
                Address from = r.getFromAddress();
                Function f = listing.getFunctionContaining(from);
                out.println("  xref from " + from + " type=" + r.getReferenceType() +
                    " function=" + (f == null ? "<none>" : f.getName() + " @ " + f.getEntryPoint()));
                dumpInstructionsAround(from, 10);
                if (f != null) {
                    String key = f.getEntryPoint().toString();
                    if (!dumpedFuncs.contains(key)) {
                        dumpedFuncs.add(key);
                        dumpDecompile(f);
                    }
                }
            }
            out.println("  xref_count=" + count);
        }
        out.println();
    }

    private void dumpPatternHits() throws Exception {
        out.println("=== Binary Pattern Scan ===");
        byte[][] pats = new byte[][] {
            {(byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xD6, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xDE, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xE7, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xE5, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xE1, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xF3, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xF2, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xF1, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {(byte)0xAA, (byte)0xCB, 0x55, (byte)0xAC, 0x38},
            {0x14, 0x01, 0x01},
            {0x14, 0x01, 0x02},
            {(byte)0x81, 0x01},
            {(byte)0x82, 0x03},
            {(byte)0x81, 0x04},
            {(byte)0x82, 0x04},
            {(byte)0x81, 0x02}
        };
        String[] names = new String[] {
            "CB55AC38", "D6_CB55AC38", "DE_CB55AC38", "E7_CB55AC38", "E5_CB55AC38",
            "E1_CB55AC38", "F3_CB55AC38", "F2_CB55AC38", "F1_CB55AC38", "AA_CB55AC38",
            "EX_RESET_TRUE_140101", "EX_RESET_FALSE_140102",
            "IAP_8101", "IAP_8203", "IAP_8104", "IAP_8204", "IAP_8102"
        };

        MemoryBlock[] blocks = currentProgram.getMemory().getBlocks();
        for (int p = 0; p < pats.length; p++) {
            int hits = 0;
            out.println("Pattern " + names[p] + " len=" + pats[p].length);
            for (MemoryBlock b : blocks) {
                if (!b.isInitialized()) {
                    continue;
                }
                Address start = b.getStart();
                long size = b.getSize();
                for (long off = 0; off <= size - pats[p].length && !monitor.isCancelled(); off++) {
                    Address a = start.add(off);
                    if (matches(a, pats[p])) {
                        hits++;
                        Function f = listing.getFunctionContaining(a);
                        out.println("  hit " + a + " block=" + b.getName() +
                            " function=" + (f == null ? "<none>" : f.getName() + " @ " + f.getEntryPoint()));
                        if (hits >= 50) {
                            out.println("  ... truncated after 50 hits");
                            break;
                        }
                    }
                }
                if (hits >= 50) {
                    break;
                }
            }
            out.println("  total_reported=" + hits);
        }
        out.println();
    }

    private void dumpKeywordStringRefs() throws Exception {
        out.println("=== Keyword String Symbol Refs ===");
        String[] needles = new String[] {
            "reset", "erase", "factory", "format", "flash", "iap", "lcd", "image", "static",
            "animation", "gif", "firmware", "update", "slot", "cache", "clear"
        };
        SymbolIterator it = currentProgram.getSymbolTable().getAllSymbols(true);
        int reported = 0;
        while (it.hasNext() && !monitor.isCancelled()) {
            Symbol s = it.next();
            String n = s.getName(true);
            if (!containsAnyIgnoreCase(n, needles)) {
                continue;
            }
            ReferenceIterator rIt = refs.getReferencesTo(s.getAddress());
            boolean printed = false;
            int xrefs = 0;
            while (rIt.hasNext() && !monitor.isCancelled()) {
                Reference r = rIt.next();
                xrefs++;
                if (!printed) {
                    out.println();
                    out.println("SYMBOL " + n + " @ " + s.getAddress());
                    printed = true;
                    reported++;
                }
                Address from = r.getFromAddress();
                Function f = listing.getFunctionContaining(from);
                out.println("  xref from " + from + " function=" +
                    (f == null ? "<none>" : f.getName() + " @ " + f.getEntryPoint()));
            }
            if (printed) {
                out.println("  xref_count=" + xrefs);
            }
            if (reported >= 120) {
                out.println("... keyword symbol output truncated after 120 symbols");
                break;
            }
        }
        out.println();
    }

    private boolean matches(Address a, byte[] pat) throws Exception {
        for (int i = 0; i < pat.length; i++) {
            if (currentProgram.getMemory().getByte(a.add(i)) != pat[i]) {
                return false;
            }
        }
        return true;
    }

    private void dumpInstructionsAround(Address addr, int beforeAfter) {
        try {
            out.println("    instructions around " + addr + ":");
            Instruction inst = listing.getInstructionAt(addr);
            if (inst == null) {
                inst = listing.getInstructionBefore(addr);
            }
            if (inst == null) {
                out.println("      <no instruction>");
                return;
            }
            Instruction cur = inst;
            for (int i = 0; i < beforeAfter; i++) {
                Instruction prev = listing.getInstructionBefore(cur.getAddress());
                if (prev == null) break;
                cur = prev;
            }
            for (int i = 0; i < beforeAfter * 2 + 1 && cur != null; i++) {
                out.println("      " + cur.getAddress() + ": " + cur);
                cur = listing.getInstructionAfter(cur.getAddress());
            }
        } catch (Exception e) {
            out.println("      <instruction dump failed: " + e.getMessage() + ">");
        }
    }

    private void dumpDecompile(Function f) {
        try {
            out.println("    decompile " + f.getName() + " @ " + f.getEntryPoint() + ":");
            DecompileResults res = decomp.decompileFunction(f, 30, monitor);
            if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                out.println(res.getDecompiledFunction().getC());
            } else {
                out.println("      <decompile failed>");
            }
        } catch (Exception e) {
            out.println("      <decompile exception: " + e.getMessage() + ">");
        }
    }

    private boolean containsAny(String text, String[] needles) {
        for (String n : needles) {
            if (text.indexOf(n) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyIgnoreCase(String text, String[] needles) {
        String t = text.toLowerCase();
        for (String n : needles) {
            if (t.indexOf(n.toLowerCase()) >= 0) {
                return true;
            }
        }
        return false;
    }

    private String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
