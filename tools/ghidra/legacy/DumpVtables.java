// @category Aorus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

public class DumpVtables extends GhidraScript {
    @Override
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        SymbolIterator it = currentProgram.getSymbolTable().getAllSymbols(true);
        while (it.hasNext()) {
            Symbol s = it.next();
            String name = s.getName(true);
            if (!name.contains("vftable")) {
                continue;
            }
            if (!(name.contains("CNvDisplay") || name.contains("CIADisplay") || name.contains("CAtiDisplay"))) {
                continue;
            }
            Address base = s.getAddress();
            println("## " + name + " " + base);
            int[] slots = {0x00,0x08,0x10,0x28,0x218,0x220};
            for (int off : slots) {
                Address ea = base.add(off);
                if (!mem.contains(ea)) {
                    continue;
                }
                long ptr = mem.getLong(ea);
                Address to = toAddr(ptr);
                Function f = getFunctionAt(to);
                Symbol target = getSymbolAt(to);
                String label = f != null ? f.getName(true) : (target != null ? target.getName(true) : "");
                println(String.format("+0x%03x %s %s", off, to, label));
            }
        }
    }
}
