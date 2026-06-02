# Byte Command Scanner

Small Mono.Cecil helper used during the GIF investigation to scan managed host assemblies for LCD command-byte constants near `SendData` / `ReadData` calls.

It was used to support the report:

- `docs/gif-firmware-analysis/reports/Host_DLL_Command_Byte_Scanner_20260601.md`

The tool reads local assemblies supplied by the researcher. No GIGABYTE DLLs or generated scan outputs are included here.

## Usage

```powershell
dotnet run --project .\tools\host-analysis\byte-command-scanner\ByteCommandScanner.csproj -- `
  .\host-command-scan.md `
  "C:\path\to\local\assembly-or-folder"
```
