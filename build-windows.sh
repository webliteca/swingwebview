#!/bin/bash
set -e
cd windows
script/build.bat
cd ..
# windows_32 is no longer shipped on Maven Central (the CI matrix builds
# only x64 + arm64). The legacy build.bat still emits windows/dll/x86/
# for any local consumer that wants it, but we don't bundle it into the
# jar by default.
mkdir -p natives/windows_64
cp windows/dll/x64/*.dll natives/windows_64/
mvn -DskipTests package