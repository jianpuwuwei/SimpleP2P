@echo off
REM ============================================================
REM AllInOneP2P Mod 打包脚本 (CMD双击/命令行调用)
REM 用法:
REM   build-mod.bat                              → 交互输入版本和加载器
REM   build-mod.bat 1.12.2 forge                 → 指定版本 + 加载器
REM   build-mod.bat 1.21.1 fabric                → Fabric
REM   build-mod.bat 1.21.1 neoforge              → NeoForge
REM   build-mod.bat 1.16.5 forge -KeepTemp       → 保留中间产物
REM 支持版本: 1.7.10 / 1.8.9 / 1.12.2 / 1.16.5 / 1.18.2 / 1.19.4 / 1.20.1 / 1.20.4 / 1.21.1 (以及任意点号版本号)
REM ============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

set "MCVER=%~1"
set "LOADER=%~2"
set "EXTRA="

REM 收集除前两个参数外的剩余参数
SHIFT /1
if not "%~1"=="" SHIFT /1
:exloop
if "%~1"=="" goto donextra
set EXTRA=%EXTRA% %1
SHIFT /1
goto exloop
:donextra

REM 无参数 -> 交互
if "%MCVER%"=="" (
    echo ==============================================
    echo   AllInOneP2P Mod 打包向导
    echo ==============================================
    echo.
    set /p MCVER=请输入 Minecraft 版本号 ^(如 1.12.2 或 1.21.1^): 
    if "!MCVER!"=="" echo 版本号不能为空 & pause & exit /b 1
    :askloader
    echo.
    echo 请选择加载器 [1/2/3]:
    echo   [1] Forge
    echo   [2] Fabric
    echo   [3] NeoForge
    set /p _L=输入序号: 
    if "!_L!"=="1" set LOADER=forge
    if "!_L!"=="2" set LOADER=fabric
    if "!_L!"=="3" set LOADER=neoforge
    if "!LOADER!"=="" ( echo 无效序号 & goto askloader )
)

if "%LOADER%"=="" (
    echo 错误: 加载器参数缺失, 用法: build-mod.bat ^<MC版本^> ^<forge^|fabric^|neoforge^>
    exit /b 1
)

REM 校验加载器值
if /i not "%LOADER%"=="forge" if /i not "%LOADER%"=="fabric" if /i not "%LOADER%"=="neoforge" (
    echo 错误: 加载器必须是 forge / fabric / neoforge 其一, got: %LOADER%
    exit /b 1
)

cd /d "%~dp0"

REM 调用 PowerShell 脚本
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-mod.ps1" -MCVersion "%MCVER%" -Loader "%LOADER%" %EXTRA%

if errorlevel 1 (
    echo.
    echo [错误] 打包失败, 退出码=%errorlevel%
    pause
    exit /b %errorlevel%
)
echo.
pause
endlocal
