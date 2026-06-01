// Ghidra headless script: dump every DAT_* scalar pointer-like value.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwareAllDatPointerDump extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_all_dat_pointer_dump_" + tag + ".csv");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("name,symbol_address,data_type,value_hex,value_dec,interesting");
            SymbolIterator it = currentProgram.getSymbolTable().getAllSymbols(true);
            while (it.hasNext()) {
                Symbol sym = it.next();
                String name = sym.getName();
                if (!name.startsWith("DAT_")) {
                    continue;
                }
                Address a = sym.getAddress();
                Data d = currentProgram.getListing().getDataAt(a);
                Object value = d == null ? null : d.getValue();
                if (!(value instanceof Scalar)) {
                    continue;
                }
                long v = ((Scalar)value).getUnsignedValue();
                String interesting = "";
                if (v == 0x20000053L) {
                    interesting = "MATCH_state_plus_0x43";
                }
                else if (v >= 0x20000010L && v <= 0x20000200L) {
                    interesting = "state_or_config_ram";
                }
                pw.printf("%s,%s,%s,0x%08x,%d,%s%n",
                    name, a, d.getDataType().getName(), v, v, interesting);
            }
        }
        println("wrote " + out.getAbsolutePath());
    }
}
