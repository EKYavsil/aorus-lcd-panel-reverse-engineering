from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor

patterns = [
    "I2C", "DDC", "NVAPI", "NvAPI", "Boost", "Intel", "ctlI2CAccess",
    "GvIntelI2C", "GvWriteI2C", "GvReadI2C", "nvapi_Direct", "Direct_Get"
]

targets = [
    "GvWriteI2C", "GvReadI2C", "GvInitDispLib", "GvFreeDispLib",
    "GvIsNvBoost5", "nvapi_Direct_Get", "nvapi_Direct_GetMethod"
]

def println(s=""):
    print(s)

def decompile_function(func, ifc):
    res = ifc.decompileFunction(func, 90, ConsoleTaskMonitor())
    if res and res.decompileCompleted():
        return res.getDecompiledFunction().getC()
    return "<decompile failed>"

def get_called_names(func):
    names = []
    body = func.getBody()
    refs = currentProgram.getReferenceManager()
    it = currentProgram.getListing().getInstructions(body, True)
    seen = set()
    for ins in it:
        for ref in refs.getReferencesFrom(ins.getAddress()):
            if ref.getReferenceType().isCall():
                sym = getSymbolAt(ref.getToAddress())
                if sym is not None:
                    name = sym.getName(True)
                else:
                    f = getFunctionAt(ref.getToAddress())
                    name = f.getName(True) if f is not None else str(ref.getToAddress())
                if name not in seen:
                    seen.add(name)
                    names.append(name)
    return names

def print_exports():
    println("## EXPORTS")
    syms = currentProgram.getSymbolTable().getSymbols("Exports")
    for s in syms:
        println("%s %s" % (s.getAddress(), s.getName(True)))
    println("")

def print_imports():
    println("## IMPORTS/EXTERNALS")
    ext = currentProgram.getExternalManager()
    for lib in ext.getExternalLibraryNames():
        println("LIB %s" % lib)
        for loc in ext.getExternalLocations(lib):
            println("  %s %s" % (loc.getAddress(), loc.getLabel()))
    println("")

def print_strings():
    println("## MATCHED STRINGS")
    listing = currentProgram.getListing()
    data = listing.getDefinedData(True)
    for d in data:
        if not d.hasStringValue():
            continue
        try:
            val = str(d.getValue())
        except:
            continue
        for p in patterns:
            if p.lower() in val.lower():
                println("%s %s" % (d.getAddress(), val))
                break
    println("")

def print_target_functions():
    ifc = DecompInterface()
    ifc.openProgram(currentProgram)
    fm = currentProgram.getFunctionManager()
    println("## TARGET FUNCTIONS")
    for name in targets:
        funcs = getGlobalFunctions(name)
        if not funcs:
            continue
        for func in funcs:
            println("### %s %s" % (func.getName(), func.getEntryPoint()))
            println("CALLS: %s" % ", ".join(get_called_names(func)))
            println(decompile_function(func, ifc))
            println("")
    println("")

println("# PROGRAM %s" % currentProgram.getName())
println("IMAGE_BASE %s" % currentProgram.getImageBase())
println("LANGUAGE %s" % currentProgram.getLanguageID())
println("COMPILER %s" % currentProgram.getCompilerSpec().getCompilerSpecID())
println("")
print_exports()
print_imports()
print_strings()
print_target_functions()
