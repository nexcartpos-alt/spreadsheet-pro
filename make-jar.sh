#!/usr/bin/env bash
# Builds SpreadsheetPro.jar (needs a JDK 17+). Run it with: java -jar SpreadsheetPro.jar
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -encoding UTF-8 -d out *.java
printf 'Main-Class: SpreadsheetApp\n' > manifest.txt
jar cfm SpreadsheetPro.jar manifest.txt -C out .
echo "Built SpreadsheetPro.jar"
