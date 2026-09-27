@ECHO OFF
SETLOCAL
SET "MAVEN_VERSION=3.9.11"
IF NOT DEFINED MAVEN_USER_HOME SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"
SET "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_BINARY=%MAVEN_HOME%\bin\mvn.cmd"
IF EXIST "%MAVEN_BINARY%" GOTO runMaven
powershell -NoProfile -Command "$temp = Join-Path $env:TEMP 'maven-wrapper'; $distributionParent = Join-Path $env:MAVEN_USER_HOME 'wrapper\dists\apache-maven-%MAVEN_VERSION%'; New-Item -ItemType Directory -Force -Path $temp | Out-Null; New-Item -ItemType Directory -Force -Path $distributionParent | Out-Null; Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile (Join-Path $temp 'apache-maven.zip'); Expand-Archive -Force (Join-Path $temp 'apache-maven.zip') $distributionParent"
:runMaven
CALL "%MAVEN_BINARY%" %*
ENDLOCAL
