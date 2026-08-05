# Material assets

The runtime material package is compiled with the official `matc` tool from the
same Filament release as `filament-android` (`1.72.0`). Download the Windows
release archive from the Filament GitHub releases page, then run from the
repository root:

```powershell
<path-to-filament-1.72.0>\bin\matc.exe `
  -p mobile `
  -a all `
  -o app\src\main\assets\materials\sphere.filamat `
  app\src\main\materials\sphere.mat
```

The `.mat` source is authoritative. Regenerate the checked-in `.filamat` package
whenever the Filament runtime version changes because material package formats
are version-dependent.
