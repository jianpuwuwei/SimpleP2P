#
#   EasyTier 组网连通性测试脚本（不依赖 Minecraft）

#   用途：在两台电脑上分别以 server / client 角色启动 easytier-core，加入同一个虚拟网络，
#         用来单独验证 EasyTier 组网是否可用 —— 从而区分"是网络问题"还是"是 mod 问题"。

#   网络名/密钥的派生规则与 mod 完全一致：
#         network-name   = sp2p-<房间号>
#         network-secret = sp2p:<房间号>

#   用法：
#     A 机（开服方 / 服务端，固定虚拟 IP）：
#         powershell -ExecutionPolicy Bypass -File .\test-easytier.ps1 -Role server -Room abc123456
#     B 机（加入方 / 客户端，自动分配虚拟 IP）：
#         powershell -ExecutionPolicy Bypass -File .\test-easytier.ps1 -Role client -Room abc123456

#   说明：
#     - 需要点一次 UAC（创建 TUN 虚拟网卡必须管理员权限）
#     - 两端 -Room 必须相同，否则不在同一虚拟网络
#     - 脚本会自动结束残留的 easytier-core，并从社区节点列表里挑延迟最低的几个节点
#
param(
    [Parameter(Mandatory = $true)][ValidateSet('server', 'client')][string]$Role,
    [Parameter(Mandatory = $true)][string]$Room,
    [string]$NodeDir = "",
    [string]$VirtualIp = "10.144.144.1",
    [switch]$Stop,
    [switch]$Status
)

$ErrorActionPreference = "Continue"

# ---------- 需要管理员权限 ----------
# 残留的 EasyTier 是以管理员权限启动的，普通权限的 taskkill 清不掉。
# 残留会继续占用 10.144.144.1 与 11010 端口，导致测试结果失真
# （例如 easytier-cli 查到的是旧实例，ping 本机虚拟 IP "成功"其实是 ping 到自己）。
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "本脚本需要管理员权限（用于清理残留的 EasyTier 进程），正在请求提权，请在弹出的窗口中点“是”..." -ForegroundColor Yellow
    # -NoExit：让提权后的新窗口在脚本跑完后保持打开，否则结果一闪而过看不到
    $elevCmd = "-NoExit -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Role $Role -Room $Room"
    if ($NodeDir) { $elevCmd += " -NodeDir `"$NodeDir`"" }
    if ($VirtualIp) { $elevCmd += " -VirtualIp $VirtualIp" }
    if ($Stop) { $elevCmd += " -Stop" }
    if ($Status) { $elevCmd += " -Status" }
    Start-Process powershell -Verb RunAs -ArgumentList $elevCmd
    Write-Host "已发起提权，请在弹出的新窗口中查看运行结果。" -ForegroundColor Yellow
    exit
}

function Write-Step($m) { Write-Host "`n>>> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Write-Warn2($m){ Write-Host "  [警告] $m" -ForegroundColor Yellow }
function Write-Err2($m) { Write-Host "  [错误] $m" -ForegroundColor Red }

# ---------- 定位 easytier 目录 ----------
function Find-NodeDir {
    $list = @()
    if ($NodeDir) { $list += $NodeDir }
    $list += @(
        "E:\PCL\.minecraft\versions\1.20.1-Forge_47.4.23\mods\simplep2p\easytier",
        "E:\files1\pcl2\.minecraft\versions\1.20.1-Forge_47.4.22\mods\simplep2p\easytier"
    )
    foreach ($d in $list) {
        if ($d -and (Test-Path (Join-Path $d "easytier-core.exe"))) { return $d }
    }
    return $null
}

$dir = Find-NodeDir
if (-not $dir) {
    Write-Err2 "未找到 easytier-core.exe。请用 -NodeDir 指定 easytier 目录（含 easytier-core.exe 的文件夹）"
    exit 1
}
$coreExe = Join-Path $dir "easytier-core.exe"
$cliExe  = Join-Path $dir "easytier-cli.exe"
Write-Host "EasyTier 目录: $dir" -ForegroundColor Gray

# ---------- 仅查看状态 ----------
if ($Status) {
    if (-not (Get-Process -Name easytier-core -ErrorAction SilentlyContinue)) {
        Write-Warn2 "当前没有正在运行的 easytier-core 实例。请先用 -Role server / -Role client 启动，再查看状态"
        exit 0
    }
    Write-Step "当前虚拟网络成员（easytier-cli peer）"
    if (Test-Path $cliExe) {
        try { & $cliExe peer 2>&1 | Out-String | Write-Host } catch { Write-Warn2 "easytier-cli 查询失败: $($_.Exception.Message)" }
        Write-Step "本机节点信息（easytier-cli node）"
        try { & $cliExe node 2>&1 | Select-Object -First 16 | Out-String | Write-Host } catch { }
    } else {
        Write-Err2 "未找到 easytier-cli.exe"
    }
    exit 0
}

# ---------- 仅停止 ----------
if ($Stop) {
    Write-Step "结束所有 easytier-core 进程"
    $procs = Get-Process -Name easytier-core -ErrorAction SilentlyContinue
    if (-not $procs) { Write-Ok "没有正在运行的 easytier-core"; exit 0 }
    foreach ($p in $procs) {
        taskkill /PID $p.Id /F /T 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 1
    $still = Get-Process -Name easytier-core -ErrorAction SilentlyContinue
    if ($still) {
        Write-Warn2 "仍有进程未结束（多为管理员权限进程）。请用管理员身份运行本脚本，或手动在任务管理器结束 easytier-core.exe"
        Write-Warn2 "残留 PID: $($still.Id -join ', ')"
    } else {
        Write-Ok "已全部结束"
    }
    exit 0
}

# ---------- 1. 清理残留（残留会占用 11010 端口与 10.144.144.1，导致组网异常） ----------
Write-Step "1/5 清理残留的 easytier-core 进程"
$procs = Get-Process -Name easytier-core -ErrorAction SilentlyContinue
if ($procs) {
    foreach ($p in $procs) {
        Write-Warn2 "发现残留 PID=$($p.Id)，尝试结束"
        taskkill /PID $p.Id /F /T 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 1
    $still = Get-Process -Name easytier-core -ErrorAction SilentlyContinue
    if ($still) {
        Write-Warn2 "仍有残留（管理员权限进程），建议以管理员身份重跑本脚本"
    } else {
        Write-Ok "已清理干净"
    }
} else {
    Write-Ok "无残留"
}

# ---------- 2. 挑选公共节点 ----------
Write-Step "2/5 从社区节点列表挑选延迟最低的节点"
$nodes = @()
try {
    $api = "https://info.qtet.cn/uptime/api/status-page/easytier"
    $data = Invoke-RestMethod -Uri $api -TimeoutSec 15
    $cand = @()
    foreach ($g in $data.publicGroupList) {
        foreach ($m in $g.monitorList) {
            $n = [string]$m.name
            if ($n.Contains("*")) { continue }          # 打码地址（需加群）跳过
            if ($n -match '(tcp://[A-Za-z0-9\.\-]+:\d{1,5})') { $cand += $matches[1] }
        }
    }
    $cand = $cand | Select-Object -Unique
    Write-Host "  候选 $($cand.Count) 个，正在实测延迟..." -ForegroundColor Gray

    $lat = @()
    foreach ($n in $cand) {
        $hp = $n -replace '^tcp://', ''
        $parts = $hp.Split(':')
        $h = $parts[0]
        $pt = [int]$parts[1]
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $ok = $false
        try {
            $c = New-Object System.Net.Sockets.TcpClient
            $iar = $c.BeginConnect($h, $pt, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne(1500, $false)) { $c.EndConnect($iar); $ok = $true }
            $c.Close()
        } catch { $ok = $false }
        if ($ok) { $sw.Stop(); $lat += [pscustomobject]@{ Node = $n; Ms = $sw.ElapsedMilliseconds } }
    }
    if ($lat.Count -gt 0) {
        $nodes = ($lat | Sort-Object Ms | Select-Object -First 3).Node
    }
} catch {
    Write-Warn2 "节点列表获取失败: $($_.Exception.Message)"
}
if (-not $nodes -or $nodes.Count -eq 0) {
    $nodes = @("tcp://easytier.weiai.org.cn:11010")
    Write-Warn2 "改用默认节点: $($nodes -join ', ')"
} else {
    Write-Ok "选用节点: $($nodes -join ', ')"
}

# ---------- 3. 组装参数 ----------
Write-Step "3/5 组装 EasyTier 启动参数"
$netName   = "sp2p-$Room"
$netSecret = "sp2p:$Room"
$argList = @("--network-name", $netName, "--network-secret", $netSecret, "-p")
$argList += $nodes
$argList += @("-l", "11010")            # 固定监听端口，便于 easytier-cli 查询
if ($Role -eq 'server') {
    $argList += @("-i", $VirtualIp)     # 服务端固定虚拟 IP
} else {
    # 客户端显式指定同网段的随机 IP 并创建 TUN。
    # 实测 --dhcp true 在该版本不会创建虚拟网卡（easytier-cli node 的 Virtual IP 为空），
    # 会导致流量进不了隧道；这里显式给一个 .2~.254 的地址，避开服务端固定的 .1。
    $prefix = ($VirtualIp -replace '\.\d+$', '.')
    $hostPart = Get-Random -Minimum 2 -Maximum 255
    $clientIp = "$prefix$hostPart"
    $argList += @("-i", $clientIp)
}
Write-Host "  角色:     $Role" -ForegroundColor Gray
Write-Host "  网络名:   $netName" -ForegroundColor Gray
Write-Host "  网络密钥: $netSecret" -ForegroundColor Gray
Write-Host "  完整参数: $($argList -join ' ')" -ForegroundColor Gray

# ---------- 4. 提权启动 ----------
Write-Step "4/5 启动 easytier-core（会弹 UAC，请点“是”）"
try {
    $proc = Start-Process -FilePath $coreExe -ArgumentList $argList -WorkingDirectory $dir -Verb RunAs -PassThru -WindowStyle Hidden
} catch {
    Write-Err2 "启动失败（可能取消了 UAC）: $($_.Exception.Message)"
    exit 1
}
Write-Ok "已发起启动，PID=$($proc.Id)"

# ---------- 5. 等待就绪并输出诊断 ----------
Write-Step "5/5 等待组网就绪（最多约 30 秒）"
$ready = $false
for ($i = 1; $i -le 15; $i++) {
    Start-Sleep -Seconds 2
    $alive = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
    if (-not $alive) {
        Write-Err2 "EasyTier 进程已退出。常见原因：未点 UAC 同意 / 11010 端口被占用 / 无法连接任何公共节点"
        break
    }
    if (Test-Path $cliExe) {
        $peerOut = & $cliExe peer 2>&1 | Out-String
        if ($peerOut -match "PublicServer") {
            $ready = $true
            break
        }
    }
    Write-Host "  ...等待中 ($($i*2)s)" -ForegroundColor DarkGray
}
if ($ready) { Write-Ok "已连上公共节点" } else { Write-Warn2 "暂未在 peer 列表看到公共节点" }

Write-Step "当前虚拟网络成员"
if (Test-Path $cliExe) {
    & $cliExe peer 2>&1 | Out-String | Write-Host
    Write-Step "本机节点信息"
    & $cliExe node 2>&1 | Select-Object -First 16 | Out-String | Write-Host
} else {
    Write-Warn2 "未找到 easytier-cli.exe，跳过状态查询"
}

# 客户端额外做一次到服务端的连通性验证
if ($Role -eq 'client') {
    Write-Step "验证与对端服务端（$VirtualIp）的连通性"
    $pingOk = Test-Connection -ComputerName $VirtualIp -Count 3 -Quiet -ErrorAction SilentlyContinue
    if ($pingOk) {
        Write-Ok "ping $VirtualIp 成功 —— 组网已打通！"
    } else {
        Write-Warn2 "ping $VirtualIp 失败。请确认：对方已用 -Role server 且 -Room 与你相同；对方 peer 列表里能看到你的节点"
    }
}

Write-Host ""
Write-Host "EasyTier 已留在后台运行。" -ForegroundColor DarkGray
Write-Host "  查看状态: .\test-easytier.ps1 -Role client -Room $Room -Status" -ForegroundColor DarkGray
Write-Host "  结束进程: .\test-easytier.ps1 -Role server -Room $Room -Stop" -ForegroundColor DarkGray
