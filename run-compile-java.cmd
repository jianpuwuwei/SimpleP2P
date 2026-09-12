@echo off
setlocal
cd /d "e:\files1\pycharm\javajob\all_in_one_p2p"
if exist compile.log del /q compile.log
call gradlew.bat --no-daemon compileJava > compile.log 2>&1
echo EXIT=%ERRORLEVEL% >> compile.log
endlocal
exit /b 0
