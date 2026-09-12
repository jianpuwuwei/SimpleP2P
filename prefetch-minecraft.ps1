param(
    [string]$Version = "1.20.1",
    [string]$GradleUserHome = $(Join-Path $env:USERPROFILE ".gradle")
)

$ErrorActionPreference = "Stop"

function Invoke-SafeDownload([string]$Url, [string]$OutFile) {
    if (Test-Path $OutFile) {
        $l = (Get-Item $OutFile).Length
        if ($l -gt 4096) {
            Write-Host ("    exists {0:N0} bytes -> SKIP" -f $l)
            return $true
        }
    }
    $dir = Split-Path -Parent $OutFile
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $fallbacks = @()
    foreach ($prefix in @("https://bmclapi2.bangbang93.com/","https://download.mcbbs.net/","https://piston-meta.mojang.com/","https://piston-data.mojang.com/")) {
        if ($Url -match "^https://(piston-meta|piston-data)\.mojang\.com/(.*)$") {
            $fallbacks += ($prefix + $Matches[2])
        }
    }
    $fallbacks += $Url
    foreach ($u in $fallbacks | Select-Object -Unique) {
        Write-Host ("    GET {0}" -f $u)
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $u -OutFile $OutFile -TimeoutSec 300 -ErrorAction Stop
            $l = (Get-Item $OutFile -ErrorAction SilentlyContinue).Length
            if ($l -gt 4096) {
                Write-Host ("    OK {0:N0} bytes" -f $l)
                return $true
            } else {
                Write-Host ("    TOO SHORT {0}" -f $l)
                Remove-Item $OutFile -ErrorAction SilentlyContinue
            }
        } catch {
            Write-Host ("    FAIL {0}" -f $_.Exception.Message)
        }
    }
    return $false
}

function New-DummyPom([string]$Group, [string]$Artifact, [string]$Version, [string]$JarFile) {
    $pom = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${Group}</groupId>
  <artifactId>${Artifact}</artifactId>
  <version>${Version}</version>
  <packaging>jar</packaging>
</project>
"@
    if ([string]::IsNullOrEmpty($JarFile)) { return $null }
    $base = Split-Path $JarFile
    $nameNoJar = [System.IO.Path]::GetFileNameWithoutExtension($JarFile)
    $pomFile = Join-Path $base ($nameNoJar + ".pom")
    if (-not (Test-Path $pomFile)) { Set-Content -Path $pomFile -Value $pom -Encoding ASCII }
    return $pomFile
}

function Place-IntoGradleCache([string]$Group, [string]$Artifact, [string]$Version, [string]$File) {
    if (-not (Test-Path $File)) { return }
    $cacheDir = Join-Path $GradleUserHome ("caches\modules-2\files-2.1\{0}\{1}\{2}" -f $Group,$Artifact,$Version)
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $hash = ([Guid]::NewGuid().ToString("N")).Substring(0, 32).ToLowerInvariant()
    $targetDir = Join-Path $cacheDir $hash
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    $target = Join-Path $targetDir ([System.IO.Path]::GetFileName($File))
    if (-not (Test-Path $target)) { Copy-Item $File $target -Force }
    Write-Host ("    cached -> {0}" -f $target)
}

Write-Host "=== 1) Minecraft version-manifest ==="
$manifestLocal = Join-Path $GradleUserHome "caches\minecraft_assets\version_manifest_v2.json"
Invoke-SafeDownload -Url "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -OutFile $manifestLocal | Out-Null
$manifest = Get-Content $manifestLocal -Raw | ConvertFrom-Json
$verMetaUrl = ($manifest.versions | Where-Object { $_.id -eq $Version }).url
Write-Host ("Version meta: {0}" -f $verMetaUrl)

$verMetaLocal = Join-Path $GradleUserHome ("caches\minecraft_assets\{0}\version.json" -f $Version)
Invoke-SafeDownload -Url $verMetaUrl -OutFile $verMetaLocal | Out-Null
$ver = Get-Content $verMetaLocal -Raw | ConvertFrom-Json

Write-Host "=== 2) client.jar / server.jar ==="
$clientJarLocal = Join-Path $GradleUserHome ("caches\minecraft_assets\{0}\client-{0}.jar" -f $Version)
$ok = Invoke-SafeDownload -Url $ver.downloads.client.url -OutFile $clientJarLocal
if (-not $ok) { throw "client.jar 下载失败: $($ver.downloads.client.url)" }
$clientPom = New-DummyPom -Group "net.minecraft" -Artifact "client" -Version $Version -JarFile $clientJarLocal

$serverJarLocal = Join-Path $GradleUserHome ("caches\minecraft_assets\{0}\server-{0}.jar" -f $Version)
Invoke-SafeDownload -Url $ver.downloads.server.url -OutFile $serverJarLocal | Out-Null
$serverPom = New-DummyPom -Group "net.minecraft" -Artifact "server" -Version $Version -JarFile $serverJarLocal

Write-Host "=== 3) 放入 Gradle modules cache ==="
Place-IntoGradleCache -Group "net.minecraft" -Artifact "client" -Version $Version -File $clientJarLocal
Place-IntoGradleCache -Group "net.minecraft" -Artifact "client" -Version $Version -File $clientPom
if (Test-Path $serverJarLocal) {
    Place-IntoGradleCache -Group "net.minecraft" -Artifact "server" -Version $Version -File $serverJarLocal
    Place-IntoGradleCache -Group "net.minecraft" -Artifact "server" -Version $Version -File $serverPom
}

Write-Host "=== 4) Mojang libraries (走 libraries.minecraft.net / bangbang93 镜像) ==="
$libraries = @()
foreach ($lib in $ver.libraries) {
    $name = $lib.name
    if ($name -match '^([^:]+):([^:]+):([^:]+)$') {
        $group = $Matches[1]
        $art = $Matches[2]
        $ver = $Matches[3]
    } else { continue }
    if ($lib.downloads -and $lib.downloads.artifact) {
        $relPath = $lib.downloads.artifact.path
        $url = $lib.downloads.artifact.url
        if ([string]::IsNullOrEmpty($relPath) -or [string]::IsNullOrEmpty($url)) { continue }
        $local = Join-Path $GradleUserHome ("caches\minecraft_assets\libraries\{0}" -f $relPath)
        $ok = Invoke-SafeDownload -Url $url -OutFile $local
        if ($ok) {
            Place-IntoGradleCache -Group $group -Artifact $art -Version $ver -File $local
            $pom = New-DummyPom -Group $group -Artifact $art -Version $ver -JarFile $local
            if ($pom) { Place-IntoGradleCache -Group $group -Artifact $art -Version $ver -File $pom }
        }
    }
}

# 清理失败的 metadata 缓存（否则Gradle会继续认为“没找到”）
$metaCache = Join-Path $GradleUserHome "caches\modules-2"
Get-ChildItem -LiteralPath $metaCache -Directory -Recurse -Filter "*.lock" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
foreach ($d in @("metadata-2.102","metadata-2.101","metadata-2.100","metadata-2.99")) {
    $p = Join-Path $metaCache $d
    if (Test-Path $p) { Remove-Item -Recurse -Force $p -ErrorAction SilentlyContinue; Write-Host ("Cleared {0}" -f $p) }
}
Write-Host "DONE"
