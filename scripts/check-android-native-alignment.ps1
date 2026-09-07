param([Parameter(Mandatory)][string[]]$Archive)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($path in $Archive) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $path))
    $count = 0
    try {
        foreach ($entry in $zip.Entries | Where-Object FullName -Match '\.so$') {
            $stream = $entry.Open()
            $memory = [System.IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $data = $memory.ToArray()
            } finally { $stream.Dispose(); $memory.Dispose() }
            if ($data.Length -lt 64 -or [BitConverter]::ToUInt32($data, 0) -ne 0x464c457f -or $data[5] -ne 1) {
                throw "Unsupported ELF header: $($entry.FullName)"
            }
            if ($data[4] -eq 2) {
                $offset = [BitConverter]::ToUInt64($data, 32)
                $size = [BitConverter]::ToUInt16($data, 54)
                $segments = [BitConverter]::ToUInt16($data, 56)
                $alignOffset = 48
            } elseif ($data[4] -eq 1) {
                $offset = [BitConverter]::ToUInt32($data, 28)
                $size = [BitConverter]::ToUInt16($data, 42)
                $segments = [BitConverter]::ToUInt16($data, 44)
                $alignOffset = 28
            } else { throw "Unsupported ELF class: $($entry.FullName)" }
            $loads = 0
            for ($i = 0; $i -lt $segments; $i++) {
                $header = [int]($offset + $i * $size)
                if ([BitConverter]::ToUInt32($data, $header) -ne 1) { continue }
                $alignment = if ($data[4] -eq 2) { [BitConverter]::ToUInt64($data, $header + $alignOffset) } else { [BitConverter]::ToUInt32($data, $header + $alignOffset) }
                if ($alignment -lt 16384) { throw "$($entry.FullName): PT_LOAD alignment $alignment is below 16384" }
                $loads++
            }
            if ($loads -eq 0) { throw "No PT_LOAD segments: $($entry.FullName)" }
            $count++
        }
        Write-Output "$path : $count ELF libraries pass 16 KB PT_LOAD alignment"
    } finally { $zip.Dispose() }
}
