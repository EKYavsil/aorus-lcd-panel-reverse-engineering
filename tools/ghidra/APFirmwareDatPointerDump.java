// Ghidra headless script: dump selected DAT_* pointer/data values.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwareDatPointerDump extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_dat_pointer_dump_" + tag + ".txt");

        String[] names = new String[] {
            "DAT_00008bb0", "DAT_00008bb4", "DAT_00008bb8", "DAT_00008bbc",
            "DAT_00008bc0", "DAT_00008bc4", "DAT_00008bc8", "DAT_00008bcc",
            "DAT_000097d4", "DAT_000097d8", "DAT_000097dc", "DAT_000097e0", "DAT_000097e4",
            "DAT_00003b84", "DAT_00003b88", "DAT_00003b8c", "DAT_00003b90",
            "DAT_0000904c", "DAT_00009050", "DAT_00009054", "DAT_00009058",
            "DAT_000086a0", "DAT_00008700", "DAT_00008704"
        };

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("name,symbol_address,data_type,value,value_class");
            for (String name : names) {
                SymbolIterator it = currentProgram.getSymbolTable().getSymbols(name);
                Symbol sym = it.hasNext() ? it.next() : null;
                if (sym == null) {
                    pw.printf("%s,<missing>,,,%n", name);
                    continue;
                }
                Address a = sym.getAddress();
                Data d = currentProgram.getListing().getDataAt(a);
                Object value = d == null ? null : d.getValue();
                String type = d == null ? "<no-data>" : d.getDataType().getName();
                String klass = value == null ? "" : value.getClass().getName();
                pw.printf("%s,%s,%s,%s,%s%n", name, a, type, String.valueOf(value), klass);
            }
        }
        println("wrote " + out.getAbsolutePath());
    }
}
