Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Continue"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$apkPath = Join-Path $rootDir "app\build\outputs\apk\debug\app-debug.apk"
$gradlePath = Join-Path $rootDir "gradlew.bat"

function Invoke-CommandLine {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [string] $WorkingDirectory = $rootDir
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $quotedArguments = foreach ($argument in $Arguments) {
        if ($argument -match '[\s"]') {
            '"' + $argument.Replace('"', '\"') + '"'
        } else {
            $argument
        }
    }

    $psi.Arguments = $quotedArguments -join " "

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    try {
        [void] $process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        $combined = ($stdout + $stderr).TrimEnd()

        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $combined
        }
    } catch {
        return [pscustomobject]@{
            ExitCode = 1
            Output = $_.Exception.Message
        }
    } finally {
        $process.Dispose()
    }
}

function Add-Log {
    param([string] $Message)

    $timestamp = Get-Date -Format "HH:mm:ss"
    $logBox.AppendText("[$timestamp] $Message`r`n")
    $logBox.SelectionStart = $logBox.Text.Length
    $logBox.ScrollToCaret()
}

function Get-SelectedDevice {
    if ($deviceCombo.SelectedItem -eq $null) {
        [System.Windows.Forms.MessageBox]::Show(
            "ADB 기기를 먼저 선택하세요.",
            "기기 선택 필요",
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Warning
        ) | Out-Null

        return $null
    }

    return $deviceCombo.SelectedItem.Serial
}

function Refresh-Devices {
    $deviceCombo.Items.Clear()

    Add-Log "ADB 기기 목록을 조회합니다."
    $result = Invoke-CommandLine "adb" @("devices", "-l")

    if ($result.Output.Length -gt 0) {
        Add-Log $result.Output
    }

    if ($result.ExitCode -ne 0) {
        Add-Log "adb devices 실패. adb가 PATH에 있는지 확인하세요."
        return
    }

    $lines = $result.Output -split "`r?`n"

    $firstInstalledIndex = -1

    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device\s*(.*)$") {
            $serial = $matches[1]
            $info = $matches[2].Trim()
            $packageResult = Invoke-CommandLine "adb" @(
                "-s", $serial,
                "shell", "pm", "list", "packages", "ks.cloud.printer"
            )
            $installed = $packageResult.Output -match "package:ks.cloud.printer"
            $model = ""

            if ($info -match "(^|\s)model:(\S+)") {
                $model = $matches[2]
            }

            if ($model.Length -gt 0) {
                $display = "$serial  model:$model"
            } else {
                $display = "$serial  model:unknown"
            }

            $index = $deviceCombo.Items.Add([pscustomobject]@{
                Serial = $serial
                Display = $display
                Model = $model
                Installed = $installed
            }) | Out-Null

            $installState = if ($installed) { "installed" } else { "not installed" }
            Add-Log "$display  [$installState]"

            if ($installed -and $firstInstalledIndex -lt 0) {
                $firstInstalledIndex = $deviceCombo.Items.Count - 1
            }
        }
    }

    if ($deviceCombo.Items.Count -gt 0) {
        if ($firstInstalledIndex -ge 0) {
            $deviceCombo.SelectedIndex = $firstInstalledIndex
        } else {
            $deviceCombo.SelectedIndex = 0
        }

        Add-Log "$($deviceCombo.Items.Count)개 기기를 찾았습니다."
    } else {
        Add-Log "연결된 ADB 기기가 없습니다."
    }
}

function Build-Apk {
    Add-Log "APK 빌드를 시작합니다."
    $result = Invoke-CommandLine $gradlePath @("assembleDebug")

    if ($result.Output.Length -gt 0) {
        Add-Log $result.Output
    }

    if ($result.ExitCode -eq 0) {
        Add-Log "APK 빌드 완료: $apkPath"
    } else {
        Add-Log "APK 빌드 실패."
    }
}

function Install-Apk {
    $serial = Get-SelectedDevice

    if ($serial -eq $null) {
        return
    }

    if (!(Test-Path $apkPath)) {
        Add-Log "APK 파일이 없습니다. 먼저 APK 빌드를 실행하세요."
        return
    }

    Add-Log "APK를 설치합니다. device=$serial"
    $result = Invoke-CommandLine "adb" @("-s", $serial, "install", "-r", $apkPath)

    if ($result.Output.Length -gt 0) {
        Add-Log $result.Output
    }

    if ($result.ExitCode -eq 0) {
        Add-Log "APK 설치 완료."
    } else {
        Add-Log "APK 설치 실패."
    }
}

function Open-Permission-Screen {
    $serial = Get-SelectedDevice

    if ($serial -eq $null) {
        return
    }

    $packageResult = Invoke-CommandLine "adb" @(
        "-s", $serial,
        "shell", "pm", "list", "packages", "ks.cloud.printer"
    )

    if ($packageResult.Output -notmatch "package:ks.cloud.printer") {
        Add-Log "ks.cloud.printer가 선택한 기기에 설치되어 있지 않습니다. APK 설치를 먼저 실행하세요."
        return
    }

    Add-Log "프린터 권한 확인 화면을 실행합니다. device=$serial"
    $result = Invoke-CommandLine "adb" @(
        "-s", $serial,
        "shell", "am", "start",
        "-n", "ks.cloud.printer/.MainActivity"
    )

    if ($result.Output.Length -gt 0) {
        Add-Log $result.Output
    }

    if ($result.ExitCode -ne 0 -or $result.Output -match "Error") {
        Add-Log "권한 화면 실행 실패. APK 설치 상태와 선택한 기기를 확인하세요."
        return
    }

    Start-Sleep -Milliseconds 700

    $logResult = Invoke-CommandLine "adb" @(
        "-s", $serial,
        "logcat", "-d", "-s", "KS_PRINTER"
    )

    if ($logResult.Output.Length -gt 0) {
        $recentLogs = ($logResult.Output -split "`r?`n") | Select-Object -Last 20
        Add-Log ($recentLogs -join "`r`n")
    }

    Add-Log "USB 권한 팝업이 표시되면 승인하세요. 'Already has USB permission' 로그가 보이면 이미 권한이 있습니다."
}

function Send-Test-Print {
    $serial = Get-SelectedDevice

    if ($serial -eq $null) {
        return
    }

    $title = $titleBox.Text
    $number = $numberBox.Text
    $waitingCount = $waitingCountBox.Text

    if ([string]::IsNullOrWhiteSpace($title)) {
        $title = "대기번호"
    }

    if ([string]::IsNullOrWhiteSpace($number)) {
        $number = "14"
    }

    if ([string]::IsNullOrWhiteSpace($waitingCount)) {
        $waitingCount = "13"
    }

    Add-Log "테스트 출력을 전송합니다. device=$serial, number=$number, waiting_count=$waitingCount"
    $result = Invoke-CommandLine "adb" @(
        "-s", $serial,
        "shell", "am", "start",
        "-n", "ks.cloud.printer/.MainActivity",
        "--ez", "run_test_print", "true",
        "--es", "title", $title,
        "--es", "number", $number,
        "--es", "waiting_count", $waitingCount,
        "--ez", "use_test_logo", "true",
        "--ez", "use_test_layout", "true"
    )

    if ($result.Output.Length -gt 0) {
        Add-Log $result.Output
    }

    if ($result.ExitCode -eq 0) {
        Add-Log "테스트 출력 요청 완료."
    } else {
        Add-Log "테스트 출력 요청 실패."
    }
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "KS Cloud Printer Test"
$form.StartPosition = "CenterScreen"
$form.Size = New-Object System.Drawing.Size(760, 560)
$form.MinimumSize = New-Object System.Drawing.Size(760, 560)

$labelDevice = New-Object System.Windows.Forms.Label
$labelDevice.Text = "ADB Device"
$labelDevice.Location = New-Object System.Drawing.Point(16, 18)
$labelDevice.Size = New-Object System.Drawing.Size(100, 24)
$form.Controls.Add($labelDevice)

$deviceCombo = New-Object System.Windows.Forms.ComboBox
$deviceCombo.Location = New-Object System.Drawing.Point(116, 14)
$deviceCombo.Size = New-Object System.Drawing.Size(450, 28)
$deviceCombo.DropDownStyle = "DropDownList"
$deviceCombo.DisplayMember = "Display"
$form.Controls.Add($deviceCombo)

$refreshButton = New-Object System.Windows.Forms.Button
$refreshButton.Text = "기기 새로고침"
$refreshButton.Location = New-Object System.Drawing.Point(580, 12)
$refreshButton.Size = New-Object System.Drawing.Size(140, 32)
$refreshButton.Add_Click({ Refresh-Devices })
$form.Controls.Add($refreshButton)

$labelTitle = New-Object System.Windows.Forms.Label
$labelTitle.Text = "제목"
$labelTitle.Location = New-Object System.Drawing.Point(16, 64)
$labelTitle.Size = New-Object System.Drawing.Size(100, 24)
$form.Controls.Add($labelTitle)

$titleBox = New-Object System.Windows.Forms.TextBox
$titleBox.Text = "대기번호"
$titleBox.Location = New-Object System.Drawing.Point(116, 60)
$titleBox.Size = New-Object System.Drawing.Size(180, 28)
$form.Controls.Add($titleBox)

$labelNumber = New-Object System.Windows.Forms.Label
$labelNumber.Text = "번호"
$labelNumber.Location = New-Object System.Drawing.Point(320, 64)
$labelNumber.Size = New-Object System.Drawing.Size(70, 24)
$form.Controls.Add($labelNumber)

$numberBox = New-Object System.Windows.Forms.TextBox
$numberBox.Text = "14"
$numberBox.Location = New-Object System.Drawing.Point(390, 60)
$numberBox.Size = New-Object System.Drawing.Size(100, 28)
$form.Controls.Add($numberBox)

$labelWaiting = New-Object System.Windows.Forms.Label
$labelWaiting.Text = "대기인수"
$labelWaiting.Location = New-Object System.Drawing.Point(510, 64)
$labelWaiting.Size = New-Object System.Drawing.Size(80, 24)
$form.Controls.Add($labelWaiting)

$waitingCountBox = New-Object System.Windows.Forms.TextBox
$waitingCountBox.Text = "13"
$waitingCountBox.Location = New-Object System.Drawing.Point(590, 60)
$waitingCountBox.Size = New-Object System.Drawing.Size(80, 28)
$form.Controls.Add($waitingCountBox)

$buildButton = New-Object System.Windows.Forms.Button
$buildButton.Text = "APK 빌드"
$buildButton.Location = New-Object System.Drawing.Point(16, 110)
$buildButton.Size = New-Object System.Drawing.Size(130, 38)
$buildButton.Add_Click({ Build-Apk })
$form.Controls.Add($buildButton)

$installButton = New-Object System.Windows.Forms.Button
$installButton.Text = "APK 설치"
$installButton.Location = New-Object System.Drawing.Point(160, 110)
$installButton.Size = New-Object System.Drawing.Size(130, 38)
$installButton.Add_Click({ Install-Apk })
$form.Controls.Add($installButton)

$permissionButton = New-Object System.Windows.Forms.Button
$permissionButton.Text = "권한 화면 열기"
$permissionButton.Location = New-Object System.Drawing.Point(304, 110)
$permissionButton.Size = New-Object System.Drawing.Size(150, 38)
$permissionButton.Add_Click({ Open-Permission-Screen })
$form.Controls.Add($permissionButton)

$printButton = New-Object System.Windows.Forms.Button
$printButton.Text = "테스트 출력"
$printButton.Location = New-Object System.Drawing.Point(468, 110)
$printButton.Size = New-Object System.Drawing.Size(150, 38)
$printButton.Add_Click({ Send-Test-Print })
$form.Controls.Add($printButton)

$logBox = New-Object System.Windows.Forms.TextBox
$logBox.Location = New-Object System.Drawing.Point(16, 170)
$logBox.Size = New-Object System.Drawing.Size(704, 330)
$logBox.Multiline = $true
$logBox.ScrollBars = "Vertical"
$logBox.ReadOnly = $true
$logBox.Font = New-Object System.Drawing.Font("Consolas", 9)
$form.Controls.Add($logBox)

$form.Add_Shown({
    Add-Log "테스트 UI 시작. root=$rootDir"
    Refresh-Devices
})

[void] $form.ShowDialog()

