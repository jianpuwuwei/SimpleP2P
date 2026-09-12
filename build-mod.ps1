#Requires -Version 5
<#
.SYNOPSIS
  SimpleP2P Mod 打包脚本 - 按 Minecraft 版本 + 加载器产出可直接放入 mods 的 mod jar
.DESCRIPTION
  实现 ReplayMod Preprocessor 风格的 //#if //#elif //#else //#endif //#ifdef //#ifndef 条件编译,
  按需产出 Forge / Fabric / NeoForge 各 MC 版本的 mod jar. 无外部 Gradle / MC SDK 依赖.
.PARAMETER MCVersion
  Minecraft 版本号, 例如 1.7.10, 1.8.9, 1.12.2, 1.16.5, 1.18.2, 1.19.4, 1.20.1, 1.20.4, 1.21.1
.PARAMETER Loader
  加载器: forge | fabric | neoforge
.PARAMETER OutputDir
  输出目录, 默认为 build\libs
.PARAMETER KeepTemp
  保留中间产物(预处理源码/存根), 便于调试
.EXAMPLE
  .\build-mod.ps1 1.12.2 forge
  .\build-mod.ps1 -MCVersion 1.21.1 -Loader fabric
  .\build-mod.ps1 1.7.10 forge -KeepTemp
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$MCVersion,
    [Parameter(Mandatory=$true, Position=1)]
    [ValidateSet('forge','fabric','neoforge')]
    [string]$Loader,
    [string]$OutputDir = "",
    [switch]$KeepTemp
)
if ([string]::IsNullOrEmpty($OutputDir)) {
    $base = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
    $OutputDir = Join-Path $base "build\libs"
}

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# ======== 1. 版本号映射 ========
function Convert-MCVersionToInt([string]$ver) {
    $parts = $ver -split '\.'
    $maj = [int]$parts[0]
    $min = if($parts.Count -ge 2){ [int]$parts[1] } else { 0 }
    $patch = if($parts.Count -ge 3){ [int]$parts[2] } else { 0 }
    return $maj * 10000 + $min * 100 + $patch
}
$MC_INT = Convert-MCVersionToInt $MCVersion

# 加载器宏
$FORGE = ($Loader -eq 'forge')
$FABRIC = ($Loader -eq 'fabric')
$NEOFORGE = ($Loader -eq 'neoforge')

Write-Host "===== SimpleP2P Mod Build =====" -ForegroundColor Cyan
Write-Host (" MC Version : {0} (int={1})" -f $MCVersion, $MC_INT)
Write-Host (" Loader     : {0}" -f $Loader.ToUpper())
Write-Host (" Macros     : FORGE=$FORGE, FABRIC=$FABRIC, NEOFORGE=$NEOFORGE, MC=$MC_INT")
Write-Host ""

# 版本目标Java: 1.7.10~1.16.5=Java 8; 1.17~1.20.4=Java 17; 1.21+=Java 21 (但我们代码只用到Java 8语法, 统一target 8即可, 高JDK向下兼容)
$JAVA_SOURCE_TARGET = "1.8"

# ======== 2. 路径准备 ========
if ([string]::IsNullOrEmpty($PSScriptRoot)) {
    $ProjectRoot = (Get-Location).Path
} else {
    $ProjectRoot = (Resolve-Path $PSScriptRoot).Path
}
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$SrcRoot     = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "src"))
$tempName    = "build\temp_{0}_{1}" -f ($MCVersion -replace '[^A-Za-z0-9._-]', '_'), $Loader
$TempRoot    = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $tempName))
$PrepRoot    = [System.IO.Path]::GetFullPath((Join-Path $TempRoot "preprocessed"))
$StubRoot    = [System.IO.Path]::GetFullPath((Join-Path $TempRoot "stubs"))
$ClassRoot   = [System.IO.Path]::GetFullPath((Join-Path $TempRoot "classes"))
$ResRoot     = [System.IO.Path]::GetFullPath((Join-Path $TempRoot "resources"))
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputDir))
}
foreach($d in @($TempRoot,$PrepRoot,$StubRoot,$ClassRoot,$ResRoot,$OutputDir)) {
    [void](New-Item -ItemType Directory -Force -Path $d)
}

# ======== 3. 条件表达式求值 ========
function Eval-Condition([string]$expr, [int]$mc, [bool]$forge, [bool]$fabric, [bool]$neoforge) {
    # tokenize & 简易递归求值; 支持 && || ! () 以及 MC>=N MC==N MC<N MC<=N MC>N MC!=N 以及 标识符宏 FORGE/FABRIC/NEOFORGE
    $s = $expr.Trim()
    function SkipWs([ref]$i,[string]$t){ while($i.Value -lt $t.Length -and [char]::IsWhiteSpace($t[$i.Value])){ $i.Value++ } }
    function ParseOr([ref]$i,[string]$t){
        $left = ParseAnd $i $t
        SkipWs $i $t
        while($i.Value -lt $t.Length -and $t[$i.Value] -eq '|' -and $i.Value+1 -lt $t.Length -and $t[$i.Value+1] -eq '|'){
            $i.Value += 2; $right = ParseAnd $i $t
            $left = ($left -or $right)
            SkipWs $i $t
        }
        return $left
    }
    function ParseAnd([ref]$i,[string]$t){
        $left = ParseNot $i $t
        SkipWs $i $t
        while($i.Value -lt $t.Length -and $t[$i.Value] -eq '&' -and $i.Value+1 -lt $t.Length -and $t[$i.Value+1] -eq '&'){
            $i.Value += 2; $right = ParseNot $i $t
            $left = ($left -and $right)
            SkipWs $i $t
        }
        return $left
    }
    function ParseNot([ref]$i,[string]$t){
        SkipWs $i $t
        if($i.Value -lt $t.Length -and $t[$i.Value] -eq '!'){
            $i.Value++; return -not (ParseNot $i $t)
        }
        return ParseAtom $i $t
    }
    function ParseAtom([ref]$i,[string]$t){
        SkipWs $i $t
        if($i.Value -lt $t.Length -and $t[$i.Value] -eq '('){
            $i.Value++; $v = ParseOr $i $t
            SkipWs $i $t
            if($i.Value -lt $t.Length -and $t[$i.Value] -eq ')'){ $i.Value++ }
            return $v
        }
        # 读标识符或数字
        $start = $i.Value
        while($i.Value -lt $t.Length -and ($t[$i.Value] -match '[A-Za-z0-9_]')){ $i.Value++ }
        $tok = $t.Substring($start, $i.Value - $start)
        SkipWs $i $t
        # 比较运算符
        $op = $null
        if($i.Value+1 -lt $t.Length){
            $two = $t.Substring($i.Value, 2)
            if($two -in @('>=','<=','==','!=')) { $op = $two; $i.Value += 2 }
        }
        if(-not $op -and $i.Value -lt $t.Length){
            $c = $t[$i.Value]
            if($c -in @('>','<')) { $op = [string]$c; $i.Value++ }
        }
        if($op){
            # 右侧数字
            SkipWs $i $t
            $start2 = $i.Value
            while($i.Value -lt $t.Length -and ($t[$i.Value] -match '[0-9]')){ $i.Value++ }
            $num = [int]$t.Substring($start2, $i.Value - $start2)
            $leftVal = if($tok -eq 'MC'){ $mc } else { 0 }
            switch($op){
                '>=' { return ($leftVal -ge $num) }
                '<=' { return ($leftVal -le $num) }
                '>'  { return ($leftVal -gt $num) }
                '<'  { return ($leftVal -lt $num) }
                '==' { return ($leftVal -eq $num) }
                '!=' { return ($leftVal -ne $num) }
            }
            return $false
        }
        # 单独标识符宏
        switch($tok){
            'FORGE'    { return $forge }
            'FABRIC'   { return $fabric }
            'NEOFORGE' { return $neoforge }
            'true'     { return $true }
            'false'    { return $false }
            default    { return $false }
        }
    }
    $idx = 0
    return [bool](ParseOr ([ref]$idx) $s)
}

# ======== 4. Preprocessor 处理单个文件 ========
function Invoke-Preprocessor([string]$text, [int]$mc, [bool]$forge, [bool]$fabric, [bool]$neoforge) {
    $lines = $text -split "`r?`n"
    $stack = New-Object System.Collections.Generic.List[bool]
    $result = New-Object System.Collections.Generic.List[string]
    $defaultInclude = $true
    # 栈: 每个 //#if / //#ifdef / //#ifndef 压入一个 bool (当前分支是否active)
    for($i=0; $i -lt $lines.Count; $i++) {
        $origLine = $lines[$i]
        $trim = $origLine.TrimStart()
        $directive = $null
        $arg = $null
        if($trim.StartsWith("//#if "))    { $directive = "if";     $arg = $trim.Substring(6).Trim() }
        elseif($trim -cmatch '^//#if\s*\('){ $directive = "if";    $arg = $trim.Substring(5).Trim() }
        elseif($trim.StartsWith("//#ifdef "))  { $directive = "ifdef";  $arg = $trim.Substring(9).Trim() }
        elseif($trim.StartsWith("//#ifndef ")) { $directive = "ifndef"; $arg = $trim.Substring(10).Trim() }
        elseif($trim.StartsWith("//#elif "))   { $directive = "elif";   $arg = $trim.Substring(7).Trim() }
        elseif($trim.StartsWith("//#else"))    { $directive = "else";   $arg = "" }
        elseif($trim.StartsWith("//#endif"))   { $directive = "endif";  $arg = "" }

        # 当前整体是否应当保留? (栈中全true)
        $allTrue = $defaultInclude
        foreach($b in $stack) { if(-not $b){ $allTrue = $false; break } }

        if($directive) {
            switch($directive){
                "if"     {
                    # 如果上一层本身已经false, 本层及子层一律false, 不用求值
                    $parent = $allTrue
                    $cond = if($parent){ Eval-Condition $arg $mc $forge $fabric $neoforge } else { $false }
                    $stack.Add($cond)
                    $result.Add("") | Out-Null
                    # 记录: 这个if是否曾经有过任何一个true分支, 用于elif跳过
                    # 简化: 用Tuple? 直接包装为 object[]: [bool]active, [bool]everTrue
                }
                "ifdef"  {
                    $parent = $allTrue
                    $cond = if($parent) {
                        if($arg -eq 'FORGE'){ $forge } elseif($arg -eq 'FABRIC'){ $fabric }
                        elseif($arg -eq 'NEOFORGE'){ $neoforge } else { $false }
                    } else { $false }
                    $stack.Add($cond)
                    $result.Add("") | Out-Null
                }
                "ifndef" {
                    $parent = $allTrue
                    $cond = if($parent) {
                        if($arg -eq 'FORGE'){ -not $forge } elseif($arg -eq 'FABRIC'){ -not $fabric }
                        elseif($arg -eq 'NEOFORGE'){ -not $neoforge } else { $true }
                    } else { $false }
                    $stack.Add($cond)
                    $result.Add("") | Out-Null
                }
                "elif"   {
                    if($stack.Count -eq 0){ Write-Error "Misplaced //#elif at line $($i+1)"; exit 1 }
                    # 把栈顶改为: 上一层父active && (此前任何兄弟分支都没为true) && 当前条件
                    # 先找父: 弹出当前栈顶, 看父
                    # 简化实现: 当遇到 elif 时:
                    #   - 若当前top已经是true(说明已经有前分支命中): 保持top=true? 不对, 这个elif应该不进入
                    #   正确做法: 用栈元素含两个bool: [branchTaken, thisActive]
                    # 改为重定义stack element: @{Self=当前分支条件; BranchTaken=此if链是否已命中过某个分支}
                    # 这里我们重新用更精确结构. 但上面的if/ifdef/ifndef已经push简单bool. 所以为兼容, 改用更聪明方式:
                    # 每次 //#if push一个自定义对象, //#ifdef push自定义对象, //#elif修改它, //#endif pop
                    Write-Error "Preprocessor expects structured push objects but got simple bool; cannot handle elif. Rewrite preprocessor."
                    exit 1
                }
                "else"   {
                    # 同上: 需要分支命中跟踪
                    Write-Error "Preprocessor expects structured push objects but got simple bool; cannot handle else. Rewrite preprocessor."
                    exit 1
                }
                "endif"  {
                    if($stack.Count -eq 0){ Write-Error "Misplaced //#endif at line $($i+1)"; exit 1 }
                    $stack.RemoveAt($stack.Count - 1)
                    $result.Add("") | Out-Null
                }
            }
            continue
        }

        if($allTrue) {
            $result.Add($origLine) | Out-Null
        } else {
            # 替换为空行, 保持行号, 便于调试
            $result.Add("") | Out-Null
        }
    }
    if($stack.Count -ne 0){ Write-Error "Unterminated //#if directives left ($($stack.Count))"; exit 1 }
    return ($result -join "`r`n")
}

# ==============================================================
# 为了支持 //#elif / //#else, 重写一个带结构化栈元素的Preprocessor
# ==============================================================
function Invoke-PreprocessorV2([string]$text, [int]$mc, [bool]$forge, [bool]$fabric, [bool]$neoforge) {
    $lines = $text -split "`r?`n"
    # stack element: PSObject with:
    #   - Type: 'if'
    #   - ParentActive: bool (进入此if前是否整体active)
    #   - BranchTaken: bool (此if链中是否已有任一条件成功)
    #   - SelfActive: bool (当前分支的条件结果, 注意还需要合ParentActive)
    $stack = New-Object System.Collections.Generic.List[object]
    $result = New-Object System.Collections.Generic.List[string]

    function Get-OverallActive() {
        # 整体active = 当前外层都active AND 当前栈顶SelfActive
        foreach($fr in $stack){
            $parentOk = $fr.ParentActive
            # 注意每个frame的ParentActive是其外层整体active (它被压入时的值)
            # 但嵌套if时, 外一层可能因另一个elif变了: 我们简化——每帧ParentActive是其被压入时的"栈上方整体active".
            # 这样嵌套会正确.
            if($parentOk -eq $false -or $fr.SelfActive -eq $false){ return $false }
        }
        return $true
    }

    function Eval([string]$expr){ return Eval-Condition $expr $mc $forge $fabric $neoforge }

    for($i=0; $i -lt $lines.Count; $i++) {
        $origLine = $lines[$i]
        $trim = $origLine.TrimStart()
        $directive = $null; $arg = $null
        if($trim.StartsWith("//#if "))    { $directive = "if";    $arg = $trim.Substring(6).Trim() }
        elseif($trim -match '^//#if\s*\('){ $directive = "if";    $arg = $trim.Substring(5).Trim() }
        elseif($trim.StartsWith("//#ifdef "))  { $directive = "ifdef";  $arg = $trim.Substring(9).Trim() }
        elseif($trim.StartsWith("//#ifndef ")) { $directive = "ifndef"; $arg = $trim.Substring(10).Trim() }
        elseif($trim.StartsWith("//#elif "))   { $directive = "elif";   $arg = $trim.Substring(7).Trim() }
        elseif($trim -cmatch '^//#elif\s*\(')  { $directive = "elif";   $arg = $trim.Substring(7).Trim() }
        elseif($trim -match '^//#else\b')      { $directive = "else";   $arg = "" }
        elseif($trim -match '^//#endif\b')     { $directive = "endif";  $arg = "" }

        $outerActive = Get-OverallActive

        if($directive){
            switch($directive){
                "if"     {
                    $self = if($outerActive){ Eval $arg } else { $false }
                    $o = New-Object PSObject -Property @{
                        Type='if'; ParentActive=$outerActive; BranchTaken=$self; SelfActive=$self
                    }
                    $stack.Add($o) | Out-Null
                    $result.Add("") | Out-Null
                }
                "ifdef"  {
                    $val = switch($arg){ 'FORGE'{$forge} 'FABRIC'{$fabric} 'NEOFORGE'{$neoforge} default {$false} }
                    $self = if($outerActive){ $val } else { $false }
                    $o = New-Object PSObject -Property @{
                        Type='if'; ParentActive=$outerActive; BranchTaken=$self; SelfActive=$self
                    }
                    $stack.Add($o) | Out-Null
                    $result.Add("") | Out-Null
                }
                "ifndef" {
                    $val = switch($arg){ 'FORGE'{(-not $forge)} 'FABRIC'{(-not $fabric)} 'NEOFORGE'{(-not $neoforge)} default {$true} }
                    $self = if($outerActive){ $val } else { $false }
                    $o = New-Object PSObject -Property @{
                        Type='if'; ParentActive=$outerActive; BranchTaken=$self; SelfActive=$self
                    }
                    $stack.Add($o) | Out-Null
                    $result.Add("") | Out-Null
                }
                "elif"   {
                    if($stack.Count -eq 0){ Write-Error "Misplaced //#elif at line $($i+1)"; exit 1 }
                    $top = $stack[$stack.Count-1]
                    # self active = 父active AND 没命中过之前分支 AND 当前条件
                    $canEnter = (-not $top.BranchTaken)
                    $condNow = if($top.ParentActive -and $canEnter){ Eval $arg } else { $false }
                    $top.SelfActive = $condNow
                    if($condNow){ $top.BranchTaken = $true }
                    $result.Add("") | Out-Null
                }
                "else"   {
                    if($stack.Count -eq 0){ Write-Error "Misplaced //#else at line $($i+1)"; exit 1 }
                    $top = $stack[$stack.Count-1]
                    $enter = $top.ParentActive -and (-not $top.BranchTaken)
                    $top.SelfActive = $enter
                    if($enter){ $top.BranchTaken = $true }
                    $result.Add("") | Out-Null
                }
                "endif"  {
                    if($stack.Count -eq 0){ Write-Error "Misplaced //#endif at line $($i+1)"; exit 1 }
                    $stack.RemoveAt($stack.Count - 1)
                    $result.Add("") | Out-Null
                }
            }
            continue
        }

        if($outerActive){
            $result.Add($origLine) | Out-Null
        } else {
            $result.Add("") | Out-Null
        }
    }
    if($stack.Count -ne 0){ Write-Error "Unterminated //#if directives ($($stack.Count) left)"; exit 1 }
    return ($result -join "`r`n")
}

# ======== 5. 复制 & 预处理 src 到 PrepRoot ========
Write-Host "[1/6] Preprocessing sources..." -ForegroundColor Yellow
$srcFiles = Get-ChildItem -Recurse -File -Path $SrcRoot -Filter *.java
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
foreach($f in $srcFiles) {
    $rel = $f.FullName.Substring($SrcRoot.Length).TrimStart('\','/')
    $dst = Join-Path $PrepRoot $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
    # 读时自动尝试识别编码 (BOM), 写时强制无BOM UTF-8, 避免javac '\ufeff' 错误
    $content = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $newContent = Invoke-PreprocessorV2 $content $MC_INT $FORGE $FABRIC $NEOFORGE
    [System.IO.File]::WriteAllText($dst, $newContent, $utf8NoBom)
}
Write-Host ("   Processed {0} Java files" -f $srcFiles.Count)

# ======== 6. 生成 MC API 存根(无真MC库也能编译过) ========
Write-Host "[2/6] Generating minimal MC API stubs..." -ForegroundColor Yellow
function Write-Stub([string]$relPath, [string]$javaCode) {
    $full = Join-Path $StubRoot $relPath
    New-Item -ItemType Directory -Force -Path (Split-Path $full) | Out-Null
    # 必须写无BOM UTF-8, 否则javac首字符报 '\ufeff' 非法字符
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($full, $javaCode, $enc)
}

# 1.7.10/1.8.9 老Forge注解 (@cpw.mods.fml.common.Mod)
Write-Stub "cpw/mods/fml/common/Mod.java" @'
package cpw.mods.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Mod {
    String modid();
    String name() default "";
    String version() default "";
    String dependencies() default "";
    String acceptedMinecraftVersions() default "";
    String acceptableRemoteVersions() default "";
}
'@
Write-Stub "cpw/mods/fml/common/event/FMLInitializationEvent.java" 'package cpw.mods.fml.common.event; public class FMLInitializationEvent {}'
Write-Stub "cpw/mods/fml/common/event/FMLPreInitializationEvent.java" 'package cpw.mods.fml.common.event; public class FMLPreInitializationEvent {}'
Write-Stub "cpw/mods/fml/common/event/FMLPostInitializationEvent.java" 'package cpw.mods.fml.common.event; public class FMLPostInitializationEvent {}'
Write-Stub "cpw/mods/fml/common/event/FMLServerStartingEvent.java" 'package cpw.mods.fml.common.event; public class FMLServerStartingEvent {}'
Write-Stub "cpw/mods/fml/common/Mod$EventHandler.java" @'
package cpw.mods.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Mod$EventHandler {}
'@
# 兼容写法: Mod.EventHandler vs Mod$EventHandler. 存根用Mod$配合包内生成
# 正确写法: 使用 Mod 类的内部注解. 重写Mod.java带内部类
Write-Stub "cpw/mods/fml/common/Mod.java" @'
package cpw.mods.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Mod {
    String modid();
    String name() default "";
    String version() default "";
    String dependencies() default "";
    String acceptedMinecraftVersions() default "";
    String acceptableRemoteVersions() default "";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD})
    public @interface EventHandler {}
}
'@

# Forge 1.12+ 命令
Write-Stub "net/minecraft/command/ICommand.java" @'
package net.minecraft.command;
import java.util.*;
import net.minecraft.server.MinecraftServer;
public interface ICommand {
    String getName();
    String getUsage(ICommandSender sender);
    List<String> getAliases();
    void execute(MinecraftServer server, ICommandSender sender, String[] args);
    boolean checkPermission(MinecraftServer server, ICommandSender sender);
    List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args);
    boolean isUsernameIndex(String[] args, int index);
}
'@
Write-Stub "net/minecraft/command/CommandBase.java" @'
package net.minecraft.command;
import java.util.*;
import net.minecraft.server.MinecraftServer;
public class CommandBase implements ICommand {
    public String getName(){ return ""; }
    public String getUsage(ICommandSender s){ return ""; }
    public List<String> getAliases(){ return Collections.emptyList(); }
    public void execute(MinecraftServer s, ICommandSender se, String[] a){}
    public boolean checkPermission(MinecraftServer s, ICommandSender se){ return true; }
    public List<String> getTabCompletions(MinecraftServer s, ICommandSender se, String[] a){ return Collections.emptyList(); }
    public boolean isUsernameIndex(String[] a, int i){ return false; }
    protected void notifyCommandListener(ICommandSender s, ICommand c, String fmt, Object... p){}
}
'@
Write-Stub "net/minecraft/command/ICommandSender.java" 'package net.minecraft.command; public interface ICommandSender { String getName(); }'
Write-Stub "net/minecraft/server/MinecraftServer.java" 'package net.minecraft.server; public abstract class MinecraftServer { public static MinecraftServer getServer(){ return null; } }'

# Forge: 单一 "全属性+内部注解"的 Mod.java 存根，兼容 1.12/1.13-1.16/1.17+ 三种调用方式。
# 兼容性关键:
#   - value() 有 default=""  → 1.17+ 能写 @Mod("xxx") (仅value参数)
#   - 同时声明 modid/name/version/dependencies... → 1.13-1.16 / 1.12 能写 @Mod(modid=.., name=.., version=..)
#   - 内部注解 @Mod.Instance 和 @Mod.EventHandler → 老Forge生命周期所需
# 注: 真Forge在运行时会把这个注解替换掉(用自身的net.minecraftforge.fml.common.Mod), javac编译时只要签名能匹配注解常量池写入就OK.
Write-Stub "net/minecraftforge/fml/common/Mod.java" @'
package net.minecraftforge.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Mod {
    String value() default "";
    String modid() default "";
    String name() default "";
    String version() default "";
    String dependencies() default "";
    String acceptedMinecraftVersions() default "";
    String acceptableRemoteVersions() default "";
    String acceptableSaveVersions() default "";
    String guiFactory() default "";
    String certificateFingerprint() default "";
    String updateJSON() default "";
    String customProperties() default "";
    boolean clientSideOnly() default false;
    boolean serverSideOnly() default false;
    boolean useMetadata() default false;
    boolean canBeDeactivated() default false;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public static @interface Instance {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD})
    public static @interface EventHandler {}
}
'@

Write-Stub "net/minecraftforge/eventbus/api/SubscribeEvent.java" @'
package net.minecraftforge.eventbus.api;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface SubscribeEvent {}
'@
Write-Stub "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent.java" @'
package net.minecraftforge.fml.event.lifecycle;
import java.util.function.Supplier;
public class FMLCommonSetupEvent extends net.minecraftforge.eventbus.api.Event {
    /** Minimal stub: real API returns a CompletableFuture<Void>; we mimic the enqueueWork() shape only */
    public <T> T enqueueWork(Supplier<T> work){ if(work!=null) work.get(); return null; }
    public void enqueueWork(Runnable work){ if(work!=null) work.run(); }
}
'@
Write-Stub "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.java" @'
package net.minecraftforge.fml.javafmlmod;
public class FMLJavaModLoadingContext {
    public net.minecraftforge.eventbus.api.IEventBus getModEventBus(){ return null; }
    public static FMLJavaModLoadingContext get(){ return new FMLJavaModLoadingContext(); }
}
'@
Write-Stub "net/minecraftforge/eventbus/api/IEventBus.java" @'
package net.minecraftforge.eventbus.api;
import java.util.function.Consumer;
public interface IEventBus {
    void register(Object target);
    /** 真实 Forge eventbus API (泛型 addListener): <T extends Event> void addListener(Consumer<T>) */
    <T extends Event> void addListener(Consumer<T> listener);
}
'@
Write-Stub "net/minecraftforge/eventbus/api/Event.java" 'package net.minecraftforge.eventbus.api; public class Event {}'

# Forge 1.13-1.16 legacy 生命周期事件
Write-Stub "net/minecraftforge/fml/common/event/FMLPreInitializationEvent.java" 'package net.minecraftforge.fml.common.event; public class FMLPreInitializationEvent {}'
Write-Stub "net/minecraftforge/fml/common/event/FMLInitializationEvent.java"   'package net.minecraftforge.fml.common.event; public class FMLInitializationEvent {}'
Write-Stub "net/neoforged/fml/common/Mod.java" @'
package net.neoforged.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Mod {
    String value();
}
'@
Write-Stub "net/neoforged/bus/api/IEventBus.java" @'
package net.neoforged.bus.api;
import java.util.function.Consumer;
public interface IEventBus {
    void register(Object target);
    <T extends Event> void addListener(Consumer<T> listener);
}
'@
Write-Stub "net/neoforged/bus/api/Event.java" 'package net.neoforged.bus.api; public class Event {}'
Write-Stub "net/neoforged/bus/api/SubscribeEvent.java" @'
package net.neoforged.bus.api;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface SubscribeEvent {}
'@
Write-Stub "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext.java" @'
package net.neoforged.fml.javafmlmod;
public class FMLJavaModLoadingContext {
    public net.neoforged.bus.api.IEventBus getModEventBus(){ return null; }
    public static FMLJavaModLoadingContext get(){ return new FMLJavaModLoadingContext(); }
}
'@
Write-Stub "net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent.java" 'package net.neoforged.fml.event.lifecycle; public class FMLCommonSetupEvent {}'

# NeoForge: 构造参数 net.neoforged.neoforge.common.NeoForge (单例 + 泛型 addListener)
Write-Stub "net/neoforged/neoforge/common/NeoForge.java" @'
package net.neoforged.neoforge.common;
import java.util.function.Consumer;
public final class NeoForge {
    public <T> void addListener(Class<T> eventType, Consumer<? super T> listener) {}
    public <T> void addListener(Consumer<T> listener) {}
    public <T> void addListener(int priority, Class<T> eventType, Consumer<? super T> listener) {}
}
'@
Write-Stub "net/neoforged/neoforge/event/AddReloadListenerEvent.java" @'
package net.neoforged.neoforge.event;
public class AddReloadListenerEvent {}
'@

# Fabric
Write-Stub "net/fabricmc/api/ModInitializer.java" @'
package net.fabricmc.api;
public interface ModInitializer {
    void onInitialize();
}
'@
Write-Stub "net/fabricmc/api/ClientModInitializer.java" @'
package net.fabricmc.api;
public interface ClientModInitializer {
    void onInitializeClient();
}
'@
Write-Stub "net/fabricmc/api/DedicatedServerModInitializer.java" @'
package net.fabricmc.api;
public interface DedicatedServerModInitializer {
    void onInitializeServer();
}
'@
Write-Stub "net/fabricmc/api/Environment.java" @'
package net.fabricmc.api;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface Environment {
    EnvType value();
}
'@
Write-Stub "net/fabricmc/api/EnvType.java" @'
package net.fabricmc.api;
public enum EnvType { CLIENT, SERVER }
'@

# Fabric 命令 API (存根)
Write-Stub "net/fabricmc/fabric/api/command/v1/CommandRegistrationCallback.java" @'
package net.fabricmc.fabric.api.command.v1;
import net.fabricmc.fabric.api.event.Event;
public interface CommandRegistrationCallback {
    static Event<CommandRegistrationCallback> EVENT = null;
    void register(Object dispatcher, boolean dedicated);
}
'@
Write-Stub "net/fabricmc/fabric/api/event/Event.java" @'
package net.fabricmc.fabric.api.event;
public interface Event<T> {
    void register(T listener);
}
'@

# Forge 注册命令事件存根 (RegisterCommandsEvent + 内部类 Environment)
$rcsCode = "package net.minecraftforge.event;`r`n"
$rcsCode += "public class RegisterCommandsEvent {`r`n"
$rcsCode += "    public Object getDispatcher(){ return null; }`r`n"
$rcsCode += "    public RegisterCommandsEvent.Environment getEnvironment(){ return null; }`r`n"
$rcsCode += "    public static class Environment {}`r`n"
$rcsCode += "}`r`n"
Write-Stub "net/minecraftforge/event/RegisterCommandsEvent.java" $rcsCode

Write-Host "   Stubs generated."

# ======== 7. 生成对应版本/加载器的资源文件 ========
Write-Host "[3/6] Generating versioned mod metadata..." -ForegroundColor Yellow
# 复制 src/main/resources 基础资源
$resBase = Join-Path $SrcRoot "main\resources"
if(Test-Path $resBase){
    Copy-Item -Path (Join-Path $resBase "*") -Destination $ResRoot -Recurse -Force
}

# mods.toml 仅 Forge 1.13+ 或 NeoForge 用; 老Forge删掉避免干扰
$modsToml = Join-Path $ResRoot "META-INF\mods.toml"
$mcmodInfo = Join-Path $ResRoot "mcmod.info"
$fabricJson = Join-Path $ResRoot "fabric.mod.json"
$packMcmeta = Join-Path $ResRoot "pack.mcmeta"

if($FORGE -or $NEOFORGE){
    if($MC_INT -lt 11300){
        # 1.12.2及以下: 删除 mods.toml / fabric.mod.json; 重写 mcmod.info (只留目标mcversion)
        if(Test-Path $modsToml){ Remove-Item $modsToml -Force }
        if(Test-Path $fabricJson){ Remove-Item $fabricJson -Force }
        if(Test-Path $packMcmeta){ Remove-Item $packMcmeta -Force }
        $json = ConvertTo-Json -Depth 5 -InputObject @(
            @{
                modid = "simplep2p"
                name = "SimpleP2P"
                description = "P2P hole punching mod: EasyTier (no token) + OpenP2P (with token). Direct connect via room code; relay fallback on failure."
                version = "1.0.0"
                mcversion = $MCVersion
                url = "https://github.com/simple_p2p"
                updateUrl = ""
                authorList = @("SimpleP2P Team")
                credits = "P2P Hole Punching Cross-Version Build"
                logoFile = ""
                screenshots = @()
                dependencies = @()
            }
        ) -Compress
        [System.IO.File]::WriteAllText($mcmodInfo, $json, $utf8NoBom)
    } else {
        # 1.13+: 删除 mcmod.info / fabric.mod.json; 重写 mods.toml (Forge规范)
        # 对NeoForge, 还额外写 META-INF/neoforge.mods.toml (1.20.5+ NeoForge强制要求)
        if(Test-Path $mcmodInfo){ Remove-Item $mcmodInfo -Force }
        if(Test-Path $fabricJson){ Remove-Item $fabricJson -Force }
        # —— 精确映射 MC_INT -> Forge javafml 版本号 ——
        # 每次MC的次版本升级Forge javafml都会+1, 我们取每个次版本最低那个Forge版本:
        #  1.13    MC_INT=11300 -> 25
        #  1.14    11400 -> 28
        #  1.15    11500 -> 31
        #  1.16.1  11601 -> 32 ; 1.16.2/3 11603->34 ; 1.16.4/5 11605->36
        #  1.17    11701 -> 37
        #  1.18    11800 -> 38 ; 1.18.1 11801->39 ; 1.18.2 11802->40
        #  1.19    11900 -> 41 ; 1.19.1 11901->42 ; 1.19.2 11902->43
        #  1.19.3  11903 -> 44 ; 1.19.4 11904->45
        #  1.20    12000 -> 46 ; 1.20.1 12001->47 ; 1.20.2 12002->48
        #  1.20.3  12003 -> 49 ; 1.20.4 12004->49
        #  1.20.5  12005 -> 50 ; 1.20.6 12006->50
        #  1.21    12100 -> 51 ; 1.21.1 12101->51
        # NeoForge: loaderVersion neoforge版 1.20.1->[1,) ; 1.20.4+->[2,) ; 1.21+->[3,)
        function Get-ForgeLoaderMin([int]$mci){
            if($mci -lt 11400){ return 25 }
            if($mci -lt 11500){ return 28 }
            if($mci -lt 11601){ return 31 }
            if($mci -lt 11603){ return 32 }
            if($mci -lt 11605){ return 34 }
            if($mci -lt 11700){ return 36 }
            if($mci -lt 11800){ return 37 }
            if($mci -lt 11801){ return 38 }
            if($mci -lt 11802){ return 39 }
            if($mci -lt 11900){ return 40 }
            if($mci -lt 11901){ return 41 }
            if($mci -lt 11902){ return 42 }
            if($mci -lt 11903){ return 43 }
            if($mci -lt 11904){ return 44 }
            if($mci -lt 12000){ return 45 }
            if($mci -lt 12001){ return 46 }
            if($mci -lt 12002){ return 47 }
            if($mci -lt 12003){ return 48 }
            if($mci -lt 12005){ return 49 }
            if($mci -lt 12100){ return 50 }
            return 51
        }
        $forgeMin = Get-ForgeLoaderMin $MC_INT
        if($NEOFORGE){
            # NeoForge版本范围映射: 1.20.1 NeoForge 最早版本是1.x; 1.20.4+/1.21要求>=2; 1.21要求>=3
            if($MC_INT -le 12001){ $neoforgeMin = 1 } elseif($MC_INT -lt 12100){ $neoforgeMin = 2 } else { $neoforgeMin = 3 }
        }
        $loaderVerForge = "[" + $forgeMin + ",)"
        $loaderVerNeo   = if($NEOFORGE){ "[" + $neoforgeMin + ",)" } else { "" }
        # Minecraft versionRange: 比如1.20.1就写[1.20.1,1.20.2), 表示兼容该补丁本的所有Forge
        $verParts = $MCVersion -split '\.'
        $mcMajorMinor = ($verParts[0],$verParts[1]) -join '.'
        if($verParts.Count -ge 3){ $mcPatch = [int]$verParts[2] } else { $mcPatch = 0 }
        $mcRangeUpper = ($verParts[0],$verParts[1],($mcPatch+1)) -join '.'
        $mcRange = "[" + $MCVersion + "," + $mcRangeUpper + ")"

        $descLine1 = "P2P hole punching mod: EasyTier (token-free) + OpenP2P (token-required). Direct connect via room code; relay fallback when punching fails."
        $descLine2 = "Build: MC " + $MCVersion + " (" + ($Loader.ToUpper()) + ")."

        function Build-Toml([string]$loaderMod, [string]$loaderVer, [string]$forgeOrNeoDepId, [string]$forgeDepVer){
            $sb = New-Object System.Text.StringBuilder
            [void]$sb.AppendLine('modLoader="' + $loaderMod + '"')
            [void]$sb.AppendLine('loaderVersion="' + $loaderVer + '"')
            [void]$sb.AppendLine('license="MIT"')
            [void]$sb.AppendLine('issueTrackerURL="https://github.com/simple_p2p/issues"')
            [void]$sb.AppendLine('')
            [void]$sb.AppendLine('[[mods]]')
            [void]$sb.AppendLine('  modId="simplep2p"')
            [void]$sb.AppendLine('  version="1.0.0"')
            [void]$sb.AppendLine('  displayName="SimpleP2P"')
            [void]$sb.AppendLine('  authors="SimpleP2P Team"')
            [void]$sb.AppendLine("  description='''")
            [void]$sb.AppendLine($descLine1)
            [void]$sb.AppendLine($descLine2)
            [void]$sb.AppendLine("  '''")
            [void]$sb.AppendLine('  displayURL="https://github.com/simple_p2p"')
            [void]$sb.AppendLine('  updateJSONURL=""')
            [void]$sb.AppendLine('  logoFile=""')
            [void]$sb.AppendLine('  credits="P2P Hole Punching Cross-Version Build"')
            [void]$sb.AppendLine('')
            # —— 标准TOML依赖表格数组 (Forge 1.17+强制格式, 不兼容老的多行"dependencies"字符串) ——
            # 每个依赖必填字段: modId / mandatory / versionRange / ordering(NONE/BEFORE/AFTER) / side(BOTH/CLIENT/SERVER)
            [void]$sb.AppendLine('[[dependencies.simplep2p]]')
            [void]$sb.AppendLine('  modId="minecraft"')
            [void]$sb.AppendLine('  mandatory=true')
            [void]$sb.AppendLine('  versionRange="' + $script:mcRange + '"')
            [void]$sb.AppendLine('  ordering="NONE"')
            [void]$sb.AppendLine('  side="BOTH"')
            [void]$sb.AppendLine('')
            [void]$sb.AppendLine('[[dependencies.simplep2p]]')
            [void]$sb.AppendLine('  modId="' + $forgeOrNeoDepId + '"')
            [void]$sb.AppendLine('  mandatory=true')
            [void]$sb.AppendLine('  versionRange="' + $forgeDepVer + '"')
            [void]$sb.AppendLine('  ordering="NONE"')
            [void]$sb.AppendLine('  side="BOTH"')
            return $sb.ToString()
        }

        New-Item -ItemType Directory -Force -Path (Split-Path $modsToml) | Out-Null
        if($NEOFORGE){
            # NeoForge: 写 META-INF/mods.toml (javafml兼容) + META-INF/neoforge.mods.toml (新规范专属)
            $tomlForge = Build-Toml "javafml" $loaderVerForge "forge" $loaderVerForge
            [System.IO.File]::WriteAllText($modsToml, $tomlForge, $utf8NoBom)
            $neotoml = Join-Path $ResRoot "META-INF\neoforge.mods.toml"
            $tomlNeo = Build-Toml "neoforge" $loaderVerNeo "neoforge" $loaderVerNeo
            [System.IO.File]::WriteAllText($neotoml, $tomlNeo, $utf8NoBom)
        } else {
            $tomlForge = Build-Toml "javafml" $loaderVerForge "forge" $loaderVerForge
            [System.IO.File]::WriteAllText($modsToml, $tomlForge, $utf8NoBom)
        }
    }
}

if($FABRIC){
    # Fabric: 删掉 mods.toml / mcmod.info; 写 fabric.mod.json
    if(Test-Path $modsToml){ Remove-Item $modsToml -Force }
    $neotoml = Join-Path $ResRoot "META-INF\neoforge.mods.toml"
    if(Test-Path $neotoml){ Remove-Item $neotoml -Force }
    if(Test-Path $mcmodInfo){ Remove-Item $mcmodInfo -Force }
    if(Test-Path $packMcmeta){ Remove-Item $packMcmeta -Force }
    $depends = [ordered]@{
        "fabricloader" = ">=0.15.0"
        "minecraft" = $MCVersion
    }
    if($MC_INT -ge 12000){ $depends["fabric-api"] = "*" }
    $obj = [ordered]@{
        schemaVersion = 1
        id = "simplep2p"
        version = "1.0.0"
        name = "SimpleP2P"
        description = "P2P hole punching mod: EasyTier (token-free) + OpenP2P (token-required). Direct connect via room code; relay fallback on failure. Built for MC $MCVersion Fabric."
        authors = @("SimpleP2P Team")
        contact = [ordered]@{
            homepage = "https://github.com/simple_p2p"
            sources = "https://github.com/simple_p2p"
        }
        license = "MIT"
        icon = ""
        environment = "*"
        entrypoints = [ordered]@{
            main = @("com.simple_p2p.SimpleP2PMod")
            client = @("com.simple_p2p.SimpleP2PMod")
        }
        depends = $depends
        custom = [ordered]@{
            modmenu = [ordered]@{
                links = [ordered]@{
                    "OpenP2P Register for Token" = "https://openp2p.cn/register"
                }
            }
        }
    }
    $json = $obj | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($fabricJson, $json, $utf8NoBom)
}

# ======== 8. 编译: 预处理src + stubs ========
Write-Host "[4/6] Compiling (javac -source/target $JAVA_SOURCE_TARGET)..." -ForegroundColor Yellow
Remove-Item -Recurse -Force $ClassRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $ClassRoot | Out-Null

$allJava = @(Get-ChildItem -Recurse -File -Path $PrepRoot,$StubRoot -Filter *.java | % { $_.FullName })
if($allJava.Count -eq 0){ Write-Error "No .java files to compile!"; exit 1 }
Write-Host ("   Files: prep={0}, stubs=..., total={1}" -f $srcFiles.Count, $allJava.Count)
Write-Host ("   Invoking javac via ProcessStartInfo.ArgumentList (async I/O, no deadlock)...") -ForegroundColor Gray

# 直接用 ProcessStartInfo.ArgumentList 传每个参数, 不经过 @argfile (JDK解析UTF-8 argfile 容易出各种编码问题),
# 同时异步读取 stdout/stderr, 避免缓冲区填满导致javac卡死 (典型管道死锁).
# 最可靠的传参方式: 把所有参数拼成一个数组, 通过 & 直接传给 javac.exe.
# 不使用 ProcessStartInfo (ArgumentList 在PS5.1/.NET Framework 环境里常为null). 不再使用 @argfile.
# 使用 2>&1 | Out-String 合并输出并避免管道死锁.
$params = @(
    "-encoding", "UTF-8",
    "-source", $JAVA_SOURCE_TARGET,
    "-target", $JAVA_SOURCE_TARGET,
    "-Xlint:none",
    "-nowarn",
    "-d", $ClassRoot
) + $allJava
Write-Host ("   Running: javac " + $params.Count + " args")

$collector = New-Object System.Text.StringBuilder
$errCollector = New-Object System.Text.StringBuilder
$consoleErr = {
    param($data, $isErr)
    if($null -ne $data){
        if($isErr){ [void]$script:errCollector.AppendLine($data) }
        else     { [void]$script:collector.AppendLine($data) }
    }
}

# PowerShell 5.1 安全方式: & 程序名 (参数数组)  2>&1 合并, 然后判断 $LASTEXITCODE
$merged = & javac.exe @params 2>&1
$exitCode = $LASTEXITCODE
$errLines = @()
$outLines = @()
foreach($item in $merged) {
    if($item -is [System.Management.Automation.ErrorRecord]) {
        $errLines += $item.ToString()
    } else {
        $outLines += $item.ToString()
    }
}
if ($outLines.Count -gt 0) { $outLines | ForEach-Object { Write-Host $_ } }
if ($errLines.Count -gt 0) {
    Write-Host ("--- javac stderr ({0} lines, first 80) ---" -f $errLines.Count) -ForegroundColor DarkRed
    $errLines | Select-Object -First 80 | ForEach-Object { Write-Host $_ -ForegroundColor DarkRed }
}
if ($exitCode -ne 0) {
    Write-Error ("javac failed (exit={0})" -f $exitCode)
    exit 1
}
Write-Host ("   javac OK (exit=0)") -ForegroundColor Green

# 把资源文件复制到 class 输出目录(以便jar直接从那打)
if(Test-Path $ResRoot){
    Copy-Item -Path (Join-Path $ResRoot "*") -Destination $ClassRoot -Recurse -Force
}

# ======== 9. jar 打包 ========
Write-Host "[5/6] Packaging jar..." -ForegroundColor Yellow
$JarName = "SimpleP2P-{0}-{1}-1.0.0.jar" -f $MCVersion, $Loader
$JarFull = Join-Path $OutputDir $JarName
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Remove-Item -Force $JarFull -ErrorAction SilentlyContinue

$manifest = Join-Path $TempRoot "MANIFEST.MF"
# MANIFEST.MF 必须: 1) 开头无BOM, 2) 每行最多72字节, 3) 续行开头加空格, 4) 文件末尾必须有1个空行.
# JDK jar工具非常严格: UTF-8 BOM 会报 "invalid header field name: ?Manifest-Version".
$manifestLines = New-Object System.Collections.Generic.List[string]
$manifestLines.Add("Manifest-Version: 1.0")
$manifestLines.Add("Automatic-Module-Name: simple_p2p")
$manifestLines.Add("Implementation-Title: SimpleP2P")
$manifestLines.Add("Implementation-Version: 1.0.0")
$manifestLines.Add("Implementation-Vendor: SimpleP2P Team")
$manifestLines.Add("Built-By: build-mod.ps1")
$manifestLines.Add(("Build-MC-Version: " + $MCVersion))
$manifestLines.Add(("Build-Loader: " + $Loader))
$manifestLines.Add("Main-Class: Main")
$manifestLines.Add("")  # 末尾空行(必填)
$asciiEnc = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines($manifest, $manifestLines.ToArray(), $asciiEnc)

# ======== 5.5 从最终jar里剔除所有API stub的.class ========
# 脚本里为了无MC SDK能javac, 生成了一堆空类当存根:
#   net/minecraftforge/fml/common/*, net/minecraftforge/eventbus/api/*, net/neoforged/*,
#   net/fabricmc/*, net/minecraft/command/*, net/minecraft/server/*, cpw/mods/fml/common/* ...
# 如果把它们一起打进mod jar, 在 Java 9+ JPMS 下会跟真Forge/Fabric库的同名包冲突:
#   java.lang.module.ResolutionException: Modules net.minecraftforge.eventbus and simple_p2p export package net.minecraftforge.eventbus.api to module xxx
# 而且可能在类加载时被优先加载空类导致运行时NoSuchMethodError。
# 因此打包前必须把这些"编译占位包"从$ClassRoot整体剥离，只保留我们自己的类和资源。
Write-Host "[5.5/6] Stripping stub packages from staging class root..." -ForegroundColor DarkGray
$stubPrefixes = @(
    "net\minecraftforge\", "net\neoforged\", "net\fabricmc\", "net\minecraft\", "cpw\mods\"
)
$stubAbs = foreach($p in $stubPrefixes){
    $fullPrefix = Join-Path $ClassRoot $p
    $existing = Get-ChildItem -Directory $fullPrefix -ErrorAction SilentlyContinue
    if($existing){ $existing.FullName }
}
$keptClassCount = 0
$strippedCount = 0
if(Test-Path $ClassRoot){
    Get-ChildItem -Recurse $ClassRoot -Filter "*.class" | ForEach-Object {
        $rel = $_.FullName.Substring($ClassRoot.Length + [bool]$($ClassRoot.EndsWith('\')))
        if($rel.StartsWith("com\simple_p2p\")){ $keptClassCount++ } else { $strippedCount++ }
    }
}
Write-Host ("  Classes: keep={0} (com/simple_p2p/*) , stub-stripped={1}" -f $keptClassCount, $strippedCount) -ForegroundColor Gray
foreach($s in $stubAbs){
    if($s -and (Test-Path $s)){
        # Remove-Item -Recurse sometimes can't clear deeply nested on PS5; fallback to cmd /c rmdir /s /q
        Remove-Item -Recurse -Force $s -ErrorAction SilentlyContinue
        if(Test-Path $s){ cmd /c rmdir /s /q $s 2>$null | Out-Null }
    }
}

Push-Location $ClassRoot
try {
    # 只打包 com/ (我们自己类) + META-INF/ (MANIFEST.MF 及 清单元数据) +
    #        mcmod.info / fabric.mod.json / pack.mcmeta 等根目录资源
    $jarArgs = @("cfm", "$JarFull", "$manifest")
    foreach($top in (Get-ChildItem $ClassRoot)){
        $name = $top.Name
        if($name -eq "com" -or $name -eq "META-INF" -or
           $name -in @("mcmod.info","fabric.mod.json","pack.mcmeta","CHANGELOG.txt","README.txt")){
            $jarArgs += $name
        }
    }
    & jar @jarArgs 2>&1
    if($LASTEXITCODE -ne 0){
        Pop-Location
        Write-Error "jar failed (exit=$LASTEXITCODE)"; exit 1
    }
} finally { Pop-Location }

# ======== 10. 清理 ========
Write-Host "[6/6] Finalizing..." -ForegroundColor Yellow
if(-not $KeepTemp) {
    Remove-Item -Recurse -Force $TempRoot -ErrorAction SilentlyContinue
}

$sizeKB = [math]::Round((Get-Item $JarFull).Length / 1KB, 1)
Write-Host ""
Write-Host ("=== Build SUCCESS ===") -ForegroundColor Green
Write-Host (" Output     : {0}" -f $JarFull)
Write-Host (" Size       : {0} KB" -f $sizeKB)
Write-Host (" MC Version : {0}" -f $MCVersion)
Write-Host (" Loader     : {0}" -f $Loader.ToUpper())
Write-Host (" Temp kept  : {0}" -f [bool]$KeepTemp)
Write-Host ""
Write-Host ("安装: 复制到 .minecraft\mods\ 即可. 直接运行 P2P 控制台: java -jar $JarName") -ForegroundColor Cyan
return $JarFull
