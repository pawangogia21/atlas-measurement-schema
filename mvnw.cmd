@ECHO OFF
SETLOCAL
SET "MAVEN_VERSION=3.9.11"
REM AT-16 S9: the download is verified (SHA-256, same value as in mvnw) before it is unpacked.
SET "MAVEN_ARCHIVE_SHA256=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb"
IF NOT DEFINED MAVEN_USER_HOME SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"
SET "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_BINARY=%MAVEN_HOME%\bin\mvn.cmd"
IF EXIST "%MAVEN_BINARY%" GOTO runMaven
powershell -NoProfile -Command "$temp = Join-Path $env:TEMP 'maven-wrapper'; $distributionParent = Join-Path $env:MAVEN_USER_HOME 'wrapper\dists\apache-maven-%MAVEN_VERSION%'; New-Item -ItemType Directory -Force -Path $temp | Out-Null; New-Item -ItemType Directory -Force -Path $distributionParent | Out-Null; Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile (Join-Path $temp 'apache-maven.zip'); $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $temp 'apache-maven.zip')).Hash.ToLower(); if ($actual -ne $env:MAVEN_ARCHIVE_SHA256) { Write-Error ('mvnw.cmd: checksum mismatch (expected ' + $env:MAVEN_ARCHIVE_SHA256 + ', got ' + $actual + ')'); exit 1 }; Expand-Archive -Force (Join-Path $temp 'apache-maven.zip') $distributionParent"
IF ERRORLEVEL 1 EXIT /B 1
:runMaven
CALL "%MAVEN_BINARY%" %*
ENDLOCAL
