$device = "ZR7MTSCX8M"

Set-Location "E:\kscloudprinter"

.\gradlew clean
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

.\gradlew :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

adb -s $device install -r .\app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

adb -s $device shell am startservice -n ks.cloud.printer/.PrinterService -a ks.cloud.printer.action.PRINT_TICKET --es title 대기번호 --es number 1014 --es waiting_count 1 --ez use_test_logo true --ez use_test_layout true