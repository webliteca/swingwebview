@echo off

echo Prepare directories...
set script_dir=%~dp0
set src_dir=%script_dir%..
set build_dir=%script_dir%..\build
mkdir "%build_dir%"

echo Webview directory: %src_dir%
echo Build directory: %build_dir%

echo Looking for vswhere.exe...
set "vswhere=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%vswhere%" set "vswhere=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%vswhere%" (
	echo ERROR: Failed to find vswhere.exe
	exit 1
)
echo Found %vswhere%

echo Looking for VC...
for /f "usebackq tokens=*" %%i in (`"%vswhere%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
  set vc_dir=%%i
)
if not exist "%vc_dir%\Common7\Tools\vsdevcmd.bat" (
	echo ERROR: Failed to find VC tools x86/x64
	exit 1
)
echo Found %vc_dir%

call "%vc_dir%\Common7\Tools\vsdevcmd.bat" -arch=x86 -host_arch=x64

echo Building webview.dll (x86)
mkdir "%src_dir%\dll\x86"
cl /D "WEBVIEW_API=__declspec(dllexport)" ^
	/I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
	/I "%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\include" ^
	"%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\x86\WebView2Loader.dll.lib" ^
	"%JAVA_HOME%\lib\jawt.lib" ^
	/std:c++17 /EHsc "/Fo%build_dir%"\ ^
	"%src_dir%\webview.cc" "%src_dir%\webview_embed.cc" ^
	/link /DLL "/OUT:%build_dir%\webview.dll"
copy "%build_dir%\webview.dll" "%src_dir%\dll\x86"
copy "%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\x86\WebView2Loader.dll" "%src_dir%\dll\x86"

call "%vc_dir%\Common7\Tools\vsdevcmd.bat" -arch=x64 -host_arch=x64
echo Building webview.dll (x64)
mkdir "%src_dir%\dll\x64"
cl /D "WEBVIEW_API=__declspec(dllexport)" ^
    /I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
	/I "%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\include" ^
	"%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\x64\WebView2Loader.dll.lib" ^
	"%JAVA_HOME%\lib\jawt.lib" ^
	/std:c++17 /EHsc "/Fo%build_dir%"\ ^
	"%src_dir%\webview.cc" "%src_dir%\webview_embed.cc" ^
	/link /DLL "/OUT:%build_dir%\webview.dll"
copy "%build_dir%\webview.dll" "%src_dir%\dll\x64"
copy "%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\x64\WebView2Loader.dll" "%build_dir%"
copy "%src_dir%\script\Microsoft.Web.WebView2.0.8.355\build\native\x64\WebView2Loader.dll" "%src_dir%\dll\x64"

