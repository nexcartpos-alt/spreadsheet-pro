@echo off
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -d out *.java || exit /b 1
echo Main-Class: SpreadsheetApp> manifest.txt
jar cfm SpreadsheetPro.jar manifest.txt -C out . || exit /b 1
echo Built SpreadsheetPro.jar
