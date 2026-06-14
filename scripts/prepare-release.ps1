param(
    [string]$ExpectedVersion = "",
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

if (-not (Test-Path "pom.xml")) {
    throw "pom.xml not found from $root"
}

[xml]$pom = Get-Content "pom.xml"
$ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
$ns.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")

$groupId = $pom.SelectSingleNode("/m:project/m:groupId", $ns).InnerText
$artifactId = $pom.SelectSingleNode("/m:project/m:artifactId", $ns).InnerText
$version = $pom.SelectSingleNode("/m:project/m:version", $ns).InnerText

if ($ExpectedVersion -and $ExpectedVersion -ne $version) {
    throw "Expected version '$ExpectedVersion' but pom.xml is '$version'"
}

if (-not $SkipVerify) {
    mvn -B -Prelease-artifacts clean verify
} else {
    mvn -B -Prelease-artifacts -DskipTests verify
}

$releaseDir = Join-Path "target" "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

$jar = Join-Path "target" "$artifactId-$version.jar"
$sources = Join-Path "target" "$artifactId-$version-sources.jar"

if (-not (Test-Path $jar)) {
    throw "Release jar not found: $jar"
}

Copy-Item $jar $releaseDir -Force
if (Test-Path $sources) {
    Copy-Item $sources $releaseDir -Force
}

foreach ($noticeFile in @("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "readme.md", "build_release.md")) {
    if (Test-Path $noticeFile) {
        Copy-Item $noticeFile $releaseDir -Force
    }
}

foreach ($file in Get-ChildItem $releaseDir -File -Filter "*.jar") {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName
    $line = "$($hash.Hash.ToLowerInvariant())  $($file.Name)"
    Set-Content -NoNewline -Path "$($file.FullName).sha256" -Value $line
}

$gitCommit = git rev-parse HEAD
$gitStatus = git status --porcelain
$dirtyState = if ($gitStatus) { "dirty" } else { "clean" }

$summary = @"
# Release Artifact Summary

- Group: ``$groupId``
- Artifact: ``$artifactId``
- Version: ``$version``
- Commit: ``$gitCommit``
- Working tree: ``$dirtyState``
- Built at UTC: ``$((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))``

## Files

$((Get-ChildItem $releaseDir -File | Sort-Object Name | ForEach-Object { "- ``$($_.Name)``" }) -join "`n")

## Verification

This directory was produced by:

````powershell
./scripts/prepare-release.ps1
````

Run ``mvn -B -Prelease-artifacts clean verify`` from a clean checkout to
rebuild and compare checksums.
"@

Set-Content -Path (Join-Path $releaseDir "RELEASE_SUMMARY.md") -Value $summary

Write-Host "Release artifacts written to $releaseDir"
Get-ChildItem $releaseDir -File | Sort-Object Name | ForEach-Object {
    Write-Host " - $($_.Name)"
}
