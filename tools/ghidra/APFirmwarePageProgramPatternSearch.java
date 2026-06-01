import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;

import java.io.File;
import java.io.PrintWriter;

public class APFirmwarePageProgramPatternSearch extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = getScriptArgs().length > 0 ? getScriptArgs()[0] : ".";
        String tag = getScriptArgs().length > 1 ? getScriptArgs()[1] : currentProgram.getName();
        File out = new File(outDir, "ap_page_program_pattern_search_" + tag + ".txt");

        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.println("# AP page-program/status-poll pattern search");
            pw.println("program=" + currentProgram.getName());
            pw.println("imageBase=" + currentProgram.getImageBase());
            pw.println();

            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Function f = it.next();
                DecompileResults res = ifc.decompileFunction(f, 20, monitor);
                if (!res.decompileCompleted() || res.getDecompiledFunction() == null) {
                    continue;
                }

                String c = res.getDecompiledFunction().getC();
                boolean sendsPageProgram = c.contains("FUN_0000b990(2)") || c.contains("FUN_0000b990(0x2)");
                boolean sendsSectorErase = c.contains("FUN_0000b990(0x20)") || c.contains("FUN_0000b990(0x00000020)");
                boolean sendsBlockErase = c.contains("FUN_0000b990(0xd8)") || c.contains("FUN_0000b990(0xD8)");
                boolean callsPoll = c.contains("FUN_0000ba44()");
                boolean ignoredPollShape = c.contains("FUN_0000ba44();") && c.contains("uVar") && c.contains("= 1;");
                boolean checkedPollShape = c.contains("= FUN_0000ba44();") || c.contains("if (") && c.contains("FUN_0000ba44()");

                if (sendsPageProgram || sendsSectorErase || sendsBlockErase || callsPoll) {
                    pw.println("### " + f.getName() + " @ " + f.getEntryPoint() + " body=" + f.getBody());
                    pw.println("sendsPageProgram=" + sendsPageProgram);
                    pw.println("sendsSectorErase=" + sendsSectorErase);
                    pw.println("sendsBlockErase=" + sendsBlockErase);
                    pw.println("callsPoll=" + callsPoll);
                    pw.println("ignoredPollShape=" + ignoredPollShape);
                    pw.println("checkedPollShape=" + checkedPollShape);
                    pw.println(c);
                    pw.println();
                }
            }

            ifc.dispose();
        }

        println("wrote " + out.getAbsolutePath());
    }
}
