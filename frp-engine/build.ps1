# Requires Go 1.26, JDK 17 or later, Android SDK Platform 34 and Android NDK 27.
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$previousGoBin = $env:GOBIN
$previousPath = $env:PATH
$previousGoOS = $env:GOOS
$previousGoArch = $env:GOARCH
try {
    $buildDirectory = (New-Item -ItemType Directory -Force build).FullName
    $env:GOBIN = (New-Item -ItemType Directory -Force (Join-Path $buildDirectory 'tools')).FullName
    $env:PATH = "$env:GOBIN$([IO.Path]::PathSeparator)$env:PATH"
    & go install golang.org/x/mobile/cmd/gobind golang.org/x/mobile/cmd/gomobile
    if ($LASTEXITCODE) { throw 'Could not build the pinned Go mobile tools.' }

    $temporaryAar = Join-Path $buildDirectory 'locus-frp-build.aar'
    $flags = '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384'
    & gomobile bind '-target=android/arm64,android/amd64' '-androidapi=28' $flags '-trimpath' -o $temporaryAar .
    if ($LASTEXITCODE) { throw 'Could not build the Locus FRP Android library.' }

    $modules = @()
    $env:GOOS = 'android'
    foreach ($arch in @('arm64', 'amd64')) {
        $env:GOARCH = $arch
        $modules += & go list -deps -f '{{with .Module}}{{.Path}}|{{.Version}}|{{.Dir}}{{end}}' .
        if ($LASTEXITCODE) { throw 'Could not list the native FRP dependencies.' }
    }
    $modules += & go list -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' golang.org/x/mobile
    if ($LASTEXITCODE) { throw 'Could not locate the Go mobile runtime license.' }
    $goRoot = (& go env GOROOT).Trim()

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($temporaryAar, 'Update')
    try {
        $licenseRoot = 'assets/licenses/frp/'
        foreach ($module in ($modules | Where-Object { $_ } | Sort-Object -Unique)) {
            $name, $version, $directory = $module -split '\|', 3
            if (!$version) { continue }
            $licenses = @(Get-ChildItem -LiteralPath $directory -File |
                    Where-Object { $_.Name -match '^(LICENSE|NOTICE|COPYING|COPYRIGHT|PATENTS)(?:$|[._-])' })
            if (!$licenses.Count) { throw "Missing license for $name $version" }
            foreach ($license in $licenses) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive, $license.FullName, "$licenseRoot$name@$version/$($license.Name)") | Out-Null
            }
        }
        foreach ($name in @('LICENSE', 'PATENTS')) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, (Join-Path $goRoot $name), "${licenseRoot}go/$name") | Out-Null
        }
        # Yamux is unmodified and covered by MPL 2.0.
        $source = $archive.CreateEntry("${licenseRoot}yamux-source.txt")
        $writer = [IO.StreamWriter]::new($source.Open())
        try {
            $writer.WriteLine('Unmodified corresponding source for github.com/fatedier/yamux v0.3.0')
            $writer.WriteLine('https://github.com/fatedier/yamux/tree/v0.3.0')
        } finally {
            $writer.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
    $output = Join-Path $buildDirectory 'locus-frp.aar'
    [IO.File]::Move($temporaryAar, $output, $true)
    Get-Item -LiteralPath $output
} finally {
    $env:GOBIN = $previousGoBin
    $env:PATH = $previousPath
    $env:GOOS = $previousGoOS
    $env:GOARCH = $previousGoArch
    Pop-Location
}
