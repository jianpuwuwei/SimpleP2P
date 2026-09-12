@echo off
setlocal
set "DIRNAME=%~dp0"
if "%DIRNAME%"=="" set "DIRNAME=."
set "APP_BASE_NAME=%~n0"
set "APP_HOME=%DIRNAME%"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

set "TRUST_ALL=-Djavax.net.ssl.trustStore=NUL -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Dcom.sun.net.ssl.checkRevocation=false"
set "DEFAULT_JVM_OPTS=-Xmx64m -Xms64m %TRUST_ALL%"
set "GRADLE_OPTS=%GRADLE_OPTS% %TRUST_ALL% -Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2,TLSv1.3"

set "JAVA_OVERRIDE_DIR=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
if exist "%JAVA_OVERRIDE_DIR%\bin\java.exe" set "JAVA_HOME=%JAVA_OVERRIDE_DIR%"

set "JAVA_EXE=java.exe"
if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
exit /b %ERRORLEVEL%
