@echo off
REM ============================================================================
REM run-windows-download-demo.bat
REM
REM One-shot build & launch of WebViewDownloadDemo on Windows.  Builds the
REM WebView2-backed native DLL, packages WebView.jar, compiles the demo, and
REM runs it.  No Ant required.
REM
REM Exercises HTTP responses the engine classifies as downloads
REM (Content-Disposition: attachment) across three handler modes
REM (Default ~/Downloads policy, Custom temp-dir routing, Drop / null
REM = cancel all).  See demos\WebViewDownloadDemo\README.md for the
REM AC-mapped manual test checklist.
REM
REM Requires a modern Evergreen WebView2 Runtime that exposes the
REM ICoreWebView2_4 interface.  Older runtimes lack the
REM DownloadStarting event and downloads silently drop.
REM
REM Requires:
REM   - JAVA_HOME set to a JDK 8+ install.
REM   - Visual Studio 2019 or newer with "Desktop development with C++".
REM   - The WebView2 runtime (ships with Windows 11; install Evergreen on
REM     older Windows: https://aka.ms/webview2 ).
REM ============================================================================
setlocal enabledelayedexpansion

set "REPO_DIR=%~dp0"
if "%REPO_DIR:~-1%"=="\" set "REPO_DIR=%REPO_DIR:~0,-1%"
echo Repo: %REPO_DIR%

REM ---------------------------------------------------------------------------
REM 1. Resolve JAVA_HOME
REM ---------------------------------------------------------------------------
if "%JAVA_HOME%"=="" (
    echo.
    echo JAVA_HOME is not set.  Set it to your JDK install and re-run, e.g.:
    echo.
    echo     set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk1.8.0_xxx"
    echo     run-windows-download-demo.bat
    echo.
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: javac.exe not found in "%JAVA_HOME%\bin".
    echo Check that JAVA_HOME points at the JDK root, not the JRE.
    exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%

REM ---------------------------------------------------------------------------
REM 2. Locate Visual Studio via vswhere
REM ---------------------------------------------------------------------------
set "vswhere=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%vswhere%" set "vswhere=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%vswhere%" (
    echo ERROR: vswhere.exe not found.
    echo Install Visual Studio 2019 or newer with the
    echo "Desktop development with C++" workload.
    exit /b 1
)
set "vc_dir="
for /f "usebackq tokens=*" %%i in (`"%vswhere%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
    set "vc_dir=%%i"
)
if "%vc_dir%"=="" (
    echo ERROR: Could not locate a Visual Studio installation with VC tools x86/x64.
    exit /b 1
)
echo Visual Studio: %vc_dir%

REM ---------------------------------------------------------------------------
REM 3. Activate the MSVC x64 environment
REM ---------------------------------------------------------------------------
call "%vc_dir%\Common7\Tools\vsdevcmd.bat" -arch=x64 -host_arch=x64
if errorlevel 1 (
    echo ERROR: vsdevcmd.bat failed.
    exit /b 1
)

set "SDK_OK="
for %%I in ("%INCLUDE:;=";"%") do (
    if exist "%%~I\windows.h" set "SDK_OK=1"
)

if defined SDK_OK goto :sdk_check_done
set "KITS_ROOT=C:\Program Files (x86)\Windows Kits\10"
set "SDK_VER="
if not exist "%KITS_ROOT%\Include" goto :sdk_check_done
for /f "delims=" %%V in ('dir /b /ad /on "%KITS_ROOT%\Include" 2^>nul') do (
    if exist "%KITS_ROOT%\Include\%%V\um\windows.h" set "SDK_VER=%%V"
)
if "%SDK_VER%"=="" goto :sdk_check_done
echo NOTE: vsdevcmd did not register a Windows SDK, but one is present
echo       on disk.  Manually adding Windows SDK %SDK_VER% at %KITS_ROOT%.
set "INCLUDE=%INCLUDE%;%KITS_ROOT%\Include\%SDK_VER%\ucrt;%KITS_ROOT%\Include\%SDK_VER%\um;%KITS_ROOT%\Include\%SDK_VER%\shared;%KITS_ROOT%\Include\%SDK_VER%\winrt;%KITS_ROOT%\Include\%SDK_VER%\cppwinrt"
set "LIB=%LIB%;%KITS_ROOT%\Lib\%SDK_VER%\ucrt\x64;%KITS_ROOT%\Lib\%SDK_VER%\um\x64"
set "SDK_OK=1"
:sdk_check_done

if defined SDK_OK goto :build_dll
echo.
echo ERROR: Windows SDK headers not found on the cl.exe INCLUDE path.
echo        vsdevcmd activated successfully but no SDK provides windows.h.
echo.
echo Fix: open the Visual Studio Installer, click Modify on your VS install,
echo and on the "Individual components" tab tick a "Windows 11 SDK" or
echo "Windows 10 SDK" entry, then re-run this script.
echo.
exit /b 1
:build_dll

REM ---------------------------------------------------------------------------
REM 4. Build webview.dll (x64)
REM ---------------------------------------------------------------------------
set "NATIVE_DIR=windows_64"
set "DLL_OUT=%REPO_DIR%\src\%NATIVE_DIR%"
if not exist "%DLL_OUT%" mkdir "%DLL_OUT%"
set "BUILD_DIR=%REPO_DIR%\build"
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
set "WEBVIEW2_VERSION=1.0.2592.51"
set "WEBVIEW2_DIR=%REPO_DIR%\windows\script\Microsoft.Web.WebView2.%WEBVIEW2_VERSION%"
set "WEBVIEW2_NUPKG=%WEBVIEW2_DIR%\Microsoft.Web.WebView2.%WEBVIEW2_VERSION%.nupkg"
set "WEBVIEW2_URL=https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/%WEBVIEW2_VERSION%"

set "PWSH=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PWSH%" set "PWSH=powershell.exe"

if exist "%WEBVIEW2_DIR%\build\native\include\WebView2.h" goto :webview2_ready
if not exist "%WEBVIEW2_DIR%" mkdir "%WEBVIEW2_DIR%"
if exist "%WEBVIEW2_NUPKG%" goto :webview2_extract
echo Downloading Microsoft.Web.WebView2 %WEBVIEW2_VERSION% from NuGet ...
"%PWSH%" -NoProfile -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%WEBVIEW2_URL%' -OutFile '%WEBVIEW2_NUPKG%' -UseBasicParsing"
if not exist "%WEBVIEW2_NUPKG%" (
    echo ERROR: failed to download WebView2 SDK from %WEBVIEW2_URL%.
    exit /b 1
)
:webview2_extract
echo Extracting Microsoft.Web.WebView2.%WEBVIEW2_VERSION%.nupkg ...
"%PWSH%" -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('%WEBVIEW2_NUPKG%', '%WEBVIEW2_DIR%')"
if not exist "%WEBVIEW2_DIR%\build\native\include\WebView2.h" (
    echo ERROR: failed to extract WebView2 SDK.
    exit /b 1
)
:webview2_ready

echo Building webview.dll (x64) ...
cl /nologo ^
    /D "WEBVIEW_API=__declspec(dllexport)" ^
    /D "_SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS" ^
    /I "%JAVA_HOME%\include" ^
    /I "%JAVA_HOME%\include\win32" ^
    /I "%REPO_DIR%\windows" ^
    /I "%WEBVIEW2_DIR%\build\native\include" ^
    /std:c++17 /EHsc ^
    /Fo"%BUILD_DIR%\\" ^
    "%REPO_DIR%\windows\webview.cc" ^
    "%REPO_DIR%\windows\webview_embed.cc" ^
    /link /DLL ^
    "%WEBVIEW2_DIR%\build\native\x64\WebView2LoaderStatic.lib" ^
    "%JAVA_HOME%\lib\jawt.lib" ^
    advapi32.lib ole32.lib oleaut32.lib shlwapi.lib shell32.lib user32.lib version.lib winhttp.lib ^
    /OUT:"%BUILD_DIR%\webview.dll"
if errorlevel 1 (
    echo ERROR: cl.exe build failed.
    exit /b 1
)
copy /Y "%BUILD_DIR%\webview.dll" "%DLL_OUT%\webview.dll" >nul
echo Built %DLL_OUT%\webview.dll

REM ---------------------------------------------------------------------------
REM 5. Compile WebView Java sources
REM ---------------------------------------------------------------------------
set "WV_CLASSES=%BUILD_DIR%\classes-webview"
if exist "%WV_CLASSES%" rmdir /s /q "%WV_CLASSES%"
mkdir "%WV_CLASSES%"
echo Compiling WebView Java sources ...
dir /s /b "%REPO_DIR%\src\*.java" > "%BUILD_DIR%\wv-sources.txt"
"%JAVA_HOME%\bin\javac.exe" -d "%WV_CLASSES%" -classpath "%REPO_DIR%\lib\*" @"%BUILD_DIR%\wv-sources.txt"
if errorlevel 1 (
    echo ERROR: javac failed.
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM 6. Build WebView.jar
REM ---------------------------------------------------------------------------
set "WV_JAR=%REPO_DIR%\dist\WebView.jar"
if not exist "%REPO_DIR%\dist" mkdir "%REPO_DIR%\dist"
set "STAGE=%BUILD_DIR%\stage-webview"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\%NATIVE_DIR%"
"%SystemRoot%\System32\xcopy.exe" /e /q /y "%WV_CLASSES%\*" "%STAGE%\" >nul
if errorlevel 1 (
    echo ERROR: xcopy of compiled classes into the jar staging dir failed.
    exit /b 1
)
copy /Y "%DLL_OUT%\webview.dll" "%STAGE%\%NATIVE_DIR%\webview.dll" >nul
pushd "%STAGE%"
"%JAVA_HOME%\bin\jar.exe" cf "%WV_JAR%" .
popd
echo Built %WV_JAR%

REM ---------------------------------------------------------------------------
REM 7. Compile and launch the download demo
REM ---------------------------------------------------------------------------
set "DEMO_DIR=%REPO_DIR%\demos\WebViewDownloadDemo"
set "DEMO_CLASSES=%BUILD_DIR%\classes-download-demo"
if not exist "%DEMO_CLASSES%" mkdir "%DEMO_CLASSES%"
echo Compiling demo ...
"%JAVA_HOME%\bin\javac.exe" -d "%DEMO_CLASSES%" -classpath "%WV_JAR%" "%DEMO_DIR%\src\ca\weblite\webview\demos\WebViewDownloadDemo.java"
if errorlevel 1 (
    echo ERROR: javac failed on demo.
    exit /b 1
)

echo Launching WebViewDownloadDemo ...
"%JAVA_HOME%\bin\java.exe" -cp "%DEMO_CLASSES%;%WV_JAR%" ca.weblite.webview.demos.WebViewDownloadDemo

endlocal
