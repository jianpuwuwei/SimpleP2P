param(
    [string]$GradleUserHome = $(Join-Path $env:USERPROFILE ".gradle")
)

$ErrorActionPreference = "Stop"

function Download-One([string]$Group, [string]$Artifact, [string]$Version, [string[]]$Classifiers) {
    $pathPart = ($Group -replace '\.', '/')
    foreach ($cls in $Classifiers) {
        $ext = if ($cls -eq "pom") { "pom" } else { "jar" }
        $file = if ([string]::IsNullOrEmpty($cls) -or ($cls -eq "pom")) {
                    "${Artifact}-${Version}.${ext}"
                } else {
                    "${Artifact}-${Version}-${cls}.${ext}"
                }
        $urls = @(
            "https://bmclapi2.bangbang93.com/libraries/${pathPart}/${Artifact}/${Version}/${file}",
            "https://libraries.minecraft.net/${pathPart}/${Artifact}/${Version}/${file}",
            "https://maven.minecraftforge.net/${pathPart}/${Artifact}/${Version}/${file}"
        )
        $cacheDir = Join-Path $GradleUserHome "caches\modules-2\files-2.1\${Group}\${Artifact}\${Version}"
        New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
        $hash = ([Guid]::NewGuid().ToString("N")).Substring(0, 32).ToLowerInvariant()
        $targetDir = Join-Path $cacheDir $hash
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        $targetFile = Join-Path $targetDir $file
        if (Test-Path $targetFile) {
            $len = (Get-Item $targetFile).Length
            if ($len -gt 1024) {
                Write-Host ("SKIP exists({5:N0} bytes)  {0}:{1}:{2} {3} -> {4}" -f $Group,$Artifact,$Version,$cls,$targetFile,$len)
                continue
            }
        }
        $ok = $false
        foreach ($u in $urls) {
            Write-Host ("GET {0}:{1}:{2} {3} FROM {4}" -f $Group,$Artifact,$Version,$cls,$u)
            try {
                Invoke-WebRequest -Uri $u -OutFile $targetFile -UseBasicParsing -TimeoutSec 180 -ErrorAction Stop
                $l = (Get-Item $targetFile -ErrorAction SilentlyContinue).Length
                if ($l -gt 1024) {
                    Write-Host ("   OK {0:N0} bytes" -f $l)
                    $ok = $true
                    break
                } else {
                    Write-Host ("   TOO SHORT size={0}" -f $l)
                    Remove-Item $targetFile -ErrorAction SilentlyContinue
                }
            } catch {
                Write-Host ("   FAIL {0}" -f $_.Exception.Message)
            }
        }
        if (-not $ok) {
            Write-Host ("WARN: unable to download {0}:{1}:{2} {3}" -f $Group,$Artifact,$Version,$cls)
        }
    }
}

$modulesMetadata = Join-Path $GradleUserHome "caches\modules-2\metadata-2.102"
if (Test-Path $modulesMetadata) {
    Remove-Item -Recurse -Force $modulesMetadata -ErrorAction SilentlyContinue
    Write-Host "Cleared stale modules metadata cache"
}

Download-One "net.minecraft" "client" "1.20.1" @("pom","")
Download-One "com.mojang" "logging" "1.1.1" @("pom","")
Download-One "com.mojang" "text2speech" "1.13.9" @("pom","")
Download-One "com.mojang" "authlib" "4.0.43" @("pom","")
Download-One "com.mojang" "brigadier" "1.1.8" @("pom","")
Download-One "com.mojang" "datafixerupper" "6.0.8" @("pom","")
Download-One "com.mojang" "javabridge" "1.2.30" @("pom","")
Download-One "com.mojang" "patchy" "2.2.10" @("pom","")
Download-One "com.mojang" "realms" "1.20.1" @("pom","")
Download-One "net.minecraftforge" "forge" "1.20.1-47.4.22" @("pom","universal","srg","server-active","client-active")
Download-One "net.minecraftforge" "fmlloader" "1.20.1-47.4.22" @("pom","")
Download-One "net.minecraftforge" "fmlcore" "1.20.1-47.4.22" @("pom","")
Download-One "net.minecraftforge" "javafmllanguage" "1.20.1-47.4.22" @("pom","")
Download-One "net.minecraftforge" "mclanguage" "1.20.1-47.4.22" @("pom","")
Download-One "net.minecraftforge" "lowcodelanguage" "1.20.1-47.4.22" @("pom","")
Download-One "net.minecraftforge" "forgeconfigapiport" "1.20.1-47.4.22" @("pom","common","forge","fml")
Download-One "net.minecraftforge" "eventbus" "6.2.14" @("pom","")
Download-One "net.minecraftforge" "forgespi" "6.0.18" @("pom","")
Download-One "net.minecraftforge" "coremods" "5.0.3" @("pom","")
Download-One "net.minecraftforge" "accesstransformers" "8.0.5" @("pom","")
Download-One "net.minecraftforge" "unsafe" "0.2.0" @("pom","")
Download-One "cpw.mods" "modlauncher" "10.0.11" @("pom","")
Download-One "cpw.mods" "grossjava9hacks" "1.3.3" @("pom","")
Download-One "cpw.mods" "securejarhandler" "2.1.16" @("pom","")
Download-One "net.jodah" "typetools" "0.6.3" @("pom","")
Download-One "net.sf.jopt-simple" "jopt-simple" "5.0.4" @("pom","")
Download-One "org.antlr" "antlr4-runtime" "4.11.1" @("pom","")

Write-Host "DONE"
