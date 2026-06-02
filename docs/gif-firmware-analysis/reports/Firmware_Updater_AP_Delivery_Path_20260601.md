# Firmware Updater AP Delivery Path - 2026-06-01

Scope: offline analysis only. No updater was executed, no native DLL export was called, no I2C operation was performed, and no live file was modified.

## Purpose

The current AP firmware repair candidate depends on one practical question:

Can a patched AP payload be delivered through the GIGABYTE firmware updater path in a controlled and verifiable way?

This note documents what the updater appears to do with `AP`, `AP1`, `config.db`, and `GvLcdFwUpdate.dll`.

## Resource Extraction Behavior

Managed `FWUpgrade.exe` contains a `DisposeResource()` method that extracts bundled files into the application base directory.

Observed resource list:

```text
AP
AP1
config.db
GvDisplayA.dll
GvDisplay.dll
GvIntelI2C.dll
GvLcdFwUpdate.dll
Newtonsoft.Json.dll
SQLite.Interop.dll
System.Data.SQLite.dll
msvcp140.dll
vcruntime140_1.dll
vcruntime140.dll
```

Relevant IL behavior:

```text
pack://application:,,,/Gv_lcd_fw_update;component/resource/<name>
ApplicationDomain.BaseDirectory + <name>
File.Create(destination)
resource_stream.CopyTo(destination)
```

Implication:

- Running the original updater can overwrite loose `AP`, `AP1`, `config.db`, and native DLL files beside the EXE.
- Simply dropping a patched AP file next to the original EXE is not a reliable delivery method.
- A safe live design must either replace embedded resources, run a controlled harness, or intercept after extraction but before flashing.

## Config Database

`config.db` contains one table:

```text
config(info TEXT)
```

The F1.4 row contains JSON:

```json
{
  "Info": {
    "FW_Ver": "1.4",
    "FW_File": "AP",
    "FW_File1": "AP1",
    "FW_FileSize": "58328",
    "FW_FileSize1": "58328",
    "Model_SSID": "418C",
    "IAP_CODE_SIZE": "4096",
    "IAP_ADDRESS": "68",
    "IAP_ADDRESS1": "70",
    "AP_ADDRESS": "194",
    "AP_DataSize": "256",
    "I2C_Speed": "100"
  }
}
```

Decimal addresses map to:

| Field | Decimal | Hex |
|---|---:|---:|
| `IAP_ADDRESS` | 68 | `0x44` |
| `IAP_ADDRESS1` | 70 | `0x46` |
| `AP_ADDRESS` | 194 | `0xC2` |

## Managed Flash Selection Logic

The managed updater builds the active AP path as:

```text
BaseDirectory + FW_File
```

For F1.4:

```text
BaseDirectory + "AP"
```

Then it starts the IAP path:

```text
I2CInitial(IAP_CODE_SIZE)
Sleep 200
I2CAPChangeToIAP(AP_ADDRESS)
Sleep 500
I2CIAPChangeToErase12ByteMode(IAP_ADDRESS)
```

If that erase-mode transition fails, it tries the fallback address:

```text
I2CIAPChangeToErase12ByteMode(IAP_ADDRESS1)
```

If fallback succeeds:

```text
active IAP address = IAP_ADDRESS1
Delete(BaseDirectory + FW_File)
Copy(BaseDirectory + FW_File1, BaseDirectory + FW_File, overwrite=true)
expected size = FW_FileSize1
```

Then it validates:

```text
FileInfo(BaseDirectory + FW_File).Length == expected size
```

Finally:

```text
I2CIAPSetAPFlashTable(expected size)
Sleep 200
I2CIAPSubmitCRCAP(active IAP address)
Sleep 200
I2CIAPFlashAP12ByteMode(active IAP address)
I2CIAPChangeToAP(active IAP address)
```

## Important Delivery Consequence

Both `AP` and `AP1` must be patched consistently for a controlled package/harness.

Reason:

- Primary path flashes `AP`.
- Fallback path copies `AP1` over `AP`, then flashes the resulting `AP`.
- If only `AP` is patched, fallback can silently replace it with unpatched `AP1`.
- If only `AP1` is patched, primary path can flash unpatched `AP`.

The offline staged candidates therefore patched both:

```text
AP_option3_16x4k.bin
AP1_option3_16x4k.bin
```

Both have identical SHA256:

```text
821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643
```

## Native AP File Loading

Native `GvLcdFwUpdate.dll` export:

```text
I2CIAPSetAPFlashTable
```

Observed native behavior:

- Calls `GetModuleFileNameW`.
- Derives the DLL/application directory.
- Uses a literal `AP` string.
- Opens/loads the AP file.
- Stores AP buffer pointer and size in global state.

This matches the managed flow: managed validates the `AP` file size, then native loads `AP` again for CRC and flashing.

## Native CRC Flow

`I2CIAPSubmitCRCAP` wraps native RVA `0x3E90`.

Observed behavior:

- Uses the AP buffer populated by `I2CIAPSetAPFlashTable`.
- Skips the first `0x28` bytes.
- Computes CRC16 with polynomial `0x1021`.
- Sends command `0x8104`.

Offline computed CRCs:

| AP | CRC16 over `0x28..EOF` |
|---|---|
| Original F1.4 AP | `0x4560` |
| Option3 patched AP | `0x4BDD` |

Implication:

- If native code loads the patched AP file, it should submit the patched CRC automatically.
- No separate AP header CRC edit was identified.

## Native Erase Window Issue

The native updater has a separate erase-window problem:

```text
I2CIAPChangeToErase12ByteMode fixed erase: 0x1000..0xEFFF
F1.4 AP program range:                 0x1000..0xF3D7
```

That means F1.4 can program beyond the erased range.

Offline patch candidates were staged:

| Candidate | Native end address |
|---|---:|
| exact AP end | `0xF3D7` |
| sector end | `0xFFFF` |

The exact end is narrower. The sector end may be more correct if the bootloader expects sector-aligned erase ranges. This is still unresolved.

## Controlled Delivery Options

### Option A: Managed Resource Repack

Replace embedded resources inside `FWUpgrade.exe`:

- `AP`
- `AP1`
- optionally `GvLcdFwUpdate.dll`

Pros:

- Preserves the vendor application's managed call order.
- `DisposeResource()` naturally extracts the intended patched files.

Cons:

- EXE signature is invalidated.
- WPF resource packing must be handled correctly.
- Requires careful proof that only intended resources changed.

### Option B: Native Harness

Write a small controlled program that calls native exports directly from a staged directory.

Pros:

- Avoids managed resource extraction overwrite problem.
- Can log exact loaded AP hash, path, size, CRC, and selected DLL.
- Can force all files to come from a staging folder.

Cons:

- Bypasses managed card/model checks unless reimplemented.
- Live version would call firmware-update exports directly, which is high risk.
- Needs careful calling convention and dependency loading verification.

### Option C: Extraction-Time Interception

Run original updater, wait for extraction, replace files before flashing.

Pros:

- Minimal package editing.

Cons:

- Race-prone.
- Hard to prove safe.
- Not recommended.

## Current Recommendation

Do not live flash yet.

For the next offline stage, Option B is the cleanest research path if kept as a dry-run first:

1. Build a harness that loads nothing and calls nothing at first.
2. It should only resolve paths, compute hashes, parse `config.db`, and compute the same AP CRC.
3. Then add a strict "would-call" trace of the managed sequence.
4. Only after that consider whether a live-capable harness is worth designing.

This lets us prove the delivery model without touching the panel.

## Current Blockers Before Any Live Firmware Test

1. Decide whether native erase end should be `0xF3D7` or `0xFFFF`.
2. Confirm no bootloader-side signature check beyond submitted CRC.
3. Ensure both `AP` and `AP1` are patched in any delivery path.
4. Ensure the selected native DLL is exactly the intended staged DLL.
5. Ensure official firmware recovery process is documented and available.
6. Keep this separate from the already-working static host DLL fix.


