#!/bin/bash
set -e
cd windows
script/build.bat
cp dll/x64/*.dll ../src/windows_64/
cp dll/x86/*.dll ../src/windows_32/
cd ..
mvn -DskipTests package