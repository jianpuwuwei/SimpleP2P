@echo off
setlocal
cd /d "e:\files1\pycharm\javajob\all_in_one_p2p"
if exist build.log del /q build.log
call gradlew.bat --no-daemon assemble > build.log 2>&1
echo EXIT=%ERRORLEVEL% >> build.log
endlocal
exit /b 0
