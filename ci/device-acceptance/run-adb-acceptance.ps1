param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][ValidatePattern("^[0-9A-Fa-f]{64}$")][string]$ExpectedSha256,
    [switch]$ConfirmInstall,
    [switch]$EnableAccessibilityViaAdb,
    [switch]$RunChatGptProbe,
    [switch]$RunUnknownOutcomeCase,
    [int]$AutomationWaitSeconds = 100
)

$ErrorActionPreference = "Stop"
$Package = "science.transductive.nudge"
$Activity = "$Package/.MainActivity"
$Service = "$Package/$Package.NudgeAccessibilityService"
$EvidenceDir = Join-Path $PSScriptRoot "evidence"
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

$defaultAdb = "E:\android\platform-tools\adb.exe"
if (Test-Path $defaultAdb) {
    $Adb = $defaultAdb
} else {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "ADB not found at $defaultAdb or PATH." }
    $Adb = $cmd.Source
}

function Invoke-Adb {
    $adbArgs = @($args)
    $out = & $Adb @adbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($adbArgs -join ' ') failed: $out" }
    return ($out -join "`n")
}

function Write-Receipt {
    param([hashtable]$Receipt)
    $path = Join-Path $EvidenceDir "device-acceptance-receipt.json"
    $Receipt | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $path
    Write-Host "RECEIPT=$path"
}

function Save-Text {
    param([string]$Name,[string]$Text)
    $path = Join-Path $EvidenceDir $Name
    $Text | Set-Content -Encoding UTF8 $path
    return $path
}

function Dump-Ui {
    param([string]$Name)
    $remote = "/sdcard/self-nudge-$Name.xml"
    $local = Join-Path $EvidenceDir "$Name.xml"
    Invoke-Adb shell uiautomator dump $remote | Out-Null
    Invoke-Adb pull $remote $local | Out-Null
    return [xml](Get-Content -Raw $local)
}

function Get-BoundsCenter {
    param([string]$Bounds)
    if ($Bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Bad bounds $Bounds" }
    $x = [int]( ([int]$matches[1] + [int]$matches[3]) / 2 )
    $y = [int]( ([int]$matches[2] + [int]$matches[4]) / 2 )
    return @($x,$y)
}

function Find-NodeByText {
    param([xml]$Xml,[string]$Text)
    $nodes = $Xml.SelectNodes('//*[@text]')
    foreach ($node in $nodes) { if ($node.text -eq $Text) { return $node } }
    return $null
}

function Tap-NodeText {
    param([string]$Text,[string]$DumpName)
    $xml = Dump-Ui $DumpName
    $node = Find-NodeByText $xml $Text
    if (-not $node) { throw "UI node not found: $Text" }
    $xy = Get-BoundsCenter $node.bounds
    Invoke-Adb shell input tap $xy[0] $xy[1] | Out-Null
}

function All-UiText {
    param([string]$Name)
    $xml = Dump-Ui $Name
    return (($xml.SelectNodes('//*[@text]') | ForEach-Object { $_.text }) -join "`n")
}

function Bring-SelfNudgeToFront {
    Invoke-Adb shell am start -W -n $Activity | Out-Null
    Start-Sleep -Milliseconds 900
}

$receipt = [ordered]@{
    schema = "SELF_NUDGE_ADB_DEVICE_ACCEPTANCE/1"
    status = "STARTED"
    at = (Get-Date).ToString("o")
    adb = $Adb
    apk = [ordered]@{}
    device = [ordered]@{}
    gates = [ordered]@{}
    evidence = [ordered]@{}
    acceptance_boundary = "IN_PROGRESS"
}

try {
    $deviceLines = (Invoke-Adb devices -l) -split "`n" | Where-Object { $_ -match '\sdevice\b' -and $_ -notmatch '^List of devices' }
    $receipt.evidence.adb_devices = Save-Text "adb-devices.txt" ((Invoke-Adb devices -l) + "`n")
    if ($deviceLines.Count -ne 1) {
        $receipt.status = "BLOCKED"
        $receipt.acceptance_boundary = "REQUIRES_EXACTLY_ONE_ADB_DEVICE"
        Write-Receipt $receipt
        throw "Expected exactly one ADB device; got $($deviceLines.Count)."
    }
    $serial = ($deviceLines[0] -split '\s+')[0]
    $receipt.device.serial = $serial
    $receipt.device.model = (Invoke-Adb -s $serial shell getprop ro.product.model).Trim()
    $receipt.device.sdk = (Invoke-Adb -s $serial shell getprop ro.build.version.sdk).Trim()
    $receipt.device.build = (Invoke-Adb -s $serial shell getprop ro.build.fingerprint).Trim()

    if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
    $resolvedApk = (Resolve-Path $ApkPath).Path
    $hash = (Get-FileHash -Algorithm SHA256 $resolvedApk).Hash.ToLowerInvariant()
    $receipt.apk.path = $resolvedApk
    $receipt.apk.sha256 = $hash
    $receipt.apk.bytes = (Get-Item $resolvedApk).Length
    if ($hash -ne $ExpectedSha256.ToLowerInvariant()) {
        $receipt.status = "BLOCKED"
        $receipt.acceptance_boundary = "APK_HASH_MISMATCH"
        Write-Receipt $receipt
        throw "APK hash mismatch."
    }
    $receipt.gates.apk_hash = "PASS"

    if (-not $ConfirmInstall) {
        $receipt.status = "BLOCKED"
        $receipt.acceptance_boundary = "INSTALL_CONFIRMATION_REQUIRED"
        Write-Receipt $receipt
        throw "No install performed. Re-run with -ConfirmInstall."
    }

    Invoke-Adb -s $serial install -r $resolvedApk | Out-Null
    $receipt.gates.install = "PASS"
    $pmPath = Invoke-Adb -s $serial shell pm path $Package
    if ($pmPath -notmatch '^package:') { throw "Installed package path not found." }
    $receipt.gates.package = "PASS"

    Invoke-Adb -s $serial logcat -c | Out-Null
    Invoke-Adb -s $serial shell am force-stop $Package | Out-Null
    Invoke-Adb -s $serial shell am start -W -n $Activity | Out-Null
    Start-Sleep -Seconds 2

    $enabled = (Invoke-Adb -s $serial shell settings get secure enabled_accessibility_services).Trim()
    if ($enabled -notlike "*$Service*") {
        if (-not $EnableAccessibilityViaAdb) {
            Invoke-Adb -s $serial shell am start -a android.settings.ACCESSIBILITY_SETTINGS | Out-Null
            $receipt.status = "BLOCKED"
            $receipt.acceptance_boundary = "USER_MUST_ENABLE_ACCESSIBILITY_OR_PASS_EXPLICIT_ADB_FLAG"
            $receipt.gates.accessibility = "BLOCKED_USER_ACTION_REQUIRED"
            Write-Receipt $receipt
            throw "Accessibility is not enabled. Enable it in Android Settings, or explicitly pass -EnableAccessibilityViaAdb on an owner/developer device."
        }
        $list = if ($enabled -and $enabled -ne "null") { "$enabled`:$Service" } else { $Service }
        Invoke-Adb -s $serial shell settings put secure enabled_accessibility_services $list | Out-Null
        Invoke-Adb -s $serial shell settings put secure accessibility_enabled 1 | Out-Null
        Start-Sleep -Seconds 2
    }
    $enabled2 = (Invoke-Adb -s $serial shell settings get secure enabled_accessibility_services).Trim()
    if ($enabled2 -notlike "*$Service*") { throw "Accessibility enable verification failed." }
    $receipt.gates.accessibility = "PASS"

    Bring-SelfNudgeToFront

    Tap-NodeText "Accessibility tree probe" "tree-button"
    Start-Sleep -Seconds 2
    $treeText = All-UiText "tree-result"
    $receipt.evidence.tree_text = Save-Text "tree-result.txt" ($treeText + "`n")
    if ($treeText -notmatch 'TREE_PROBE APPLIED') { throw "Tree probe did not pass: $treeText" }
    $receipt.gates.tree_probe = "PASS"

    Tap-NodeText "Accessibility screenshot probe" "screenshot-button"
    Start-Sleep -Seconds 3
    $shotText = All-UiText "screenshot-result"
    $receipt.evidence.screenshot_text = Save-Text "screenshot-result.txt" ($shotText + "`n")
    if ($shotText -notmatch 'SCREENSHOT_PROBE APPLIED' -or $shotText -notmatch 'sha256=[0-9a-f]{64}') {
        throw "Screenshot probe did not pass: $shotText"
    }
    $receipt.gates.screenshot_probe = "PASS"

    Tap-NodeText "Signed behavior fixture probe" "behavior-fixture-button"
    Start-Sleep -Seconds 3
    $behaviorText = All-UiText "behavior-fixture-result"
    $receipt.evidence.signed_behavior_text = Save-Text "signed-behavior-result.txt" ($behaviorText + "`n")
    if ($behaviorText -notmatch 'SIGNED_BEHAVIOR_DEVICE_PROBE APPLIED') {
        throw "Signed behavior device fixture did not pass: $behaviorText"
    }
    $receipt.gates.signed_behavior_fixture = "PASS"

    $fixture = Join-Path $EvidenceDir "self-nudge-context-fixture.txt"
    "Self-Nudge context fixture $(Get-Date -Format o)" | Set-Content -Encoding UTF8 $fixture
    Invoke-Adb -s $serial push $fixture "/sdcard/Download/self-nudge-context-fixture.txt" | Out-Null
    Bring-SelfNudgeToFront
    Tap-NodeText "Import diary/context document…" "context-import-button"
    Start-Sleep -Seconds 2
    $picker = Dump-Ui "context-picker"
    $fileNode = Find-NodeByText $picker "self-nudge-context-fixture.txt"
    if (-not $fileNode) {
        $receipt.gates.context_import = "BLOCKED_FILE_PICKER_VARIANT"
        $receipt.status = "BLOCKED"
        $receipt.acceptance_boundary = "CONTEXT_FILE_PICKER_VARIANT_REQUIRES_MANUAL_SELECTION"
        Write-Receipt $receipt
        throw "ACTION_OPEN_DOCUMENT opened, but the fixture was not directly visible in this device picker. Select it manually and rerun."
    }
    $xy = Get-BoundsCenter $fileNode.bounds
    Invoke-Adb -s $serial shell input tap $xy[0] $xy[1] | Out-Null
    Start-Sleep -Seconds 3
    $contextText = All-UiText "context-result"
    $receipt.evidence.context_text = Save-Text "context-result.txt" ($contextText + "`n")
    if ($contextText -notmatch 'Imported self-nudge-context-fixture.txt' -or $contextText -notmatch 'SHA-256 [0-9a-f]{64}' -or $contextText -notmatch 'sourceUri=content://') {
        throw "Context import did not prove hash + provenance: $contextText"
    }
    $receipt.gates.context_import = "PASS"

    if ($RunChatGptProbe) {
        $chatPath = Invoke-Adb -s $serial shell pm path com.openai.chatgpt
        if ($chatPath -notmatch '^package:') { throw "ChatGPT Android package com.openai.chatgpt is not installed." }
        $receipt.gates.chatgpt_package = "PASS"

        Bring-SelfNudgeToFront
        Tap-NodeText "Run ChatGPT automation probe" "chatgpt-probe-button"
        Start-Sleep -Seconds $AutomationWaitSeconds
        Invoke-Adb -s $serial shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
        Bring-SelfNudgeToFront
        $autoText = All-UiText "chatgpt-probe-result"
        $receipt.evidence.chatgpt_text = Save-Text "chatgpt-result.txt" ($autoText + "`n")
        if ($autoText -match 'UNKNOWN_OUTCOME') {
            $receipt.gates.chatgpt_automation = "UNKNOWN_OUTCOME"
            throw "ChatGPT probe ended UNKNOWN_OUTCOME: $autoText"
        }
        if ($autoText -notmatch 'APPLIED' -or $autoText -notmatch 'ACCESSIBILITY AUTOMATION WORKS') {
            throw "ChatGPT automation did not pass: $autoText"
        }
        $receipt.gates.chatgpt_automation = "PASS"

        if ($RunUnknownOutcomeCase) {
            Bring-SelfNudgeToFront
            Tap-NodeText "Run ChatGPT automation probe" "unknown-button"
            Start-Sleep -Seconds 5
            Invoke-Adb -s $serial shell am force-stop com.openai.chatgpt | Out-Null
            Start-Sleep -Seconds $AutomationWaitSeconds
            Bring-SelfNudgeToFront
            $unknownText = All-UiText "unknown-result"
            $receipt.evidence.unknown_outcome_text = Save-Text "unknown-outcome-result.txt" ($unknownText + "`n")
            if ($unknownText -match 'UNKNOWN_OUTCOME') {
                $receipt.gates.unknown_outcome_case = "PASS"
            } else {
                $receipt.gates.unknown_outcome_case = "NOT_PROVEN"
                throw "Interruption case did not mechanically produce UNKNOWN_OUTCOME: $unknownText"
            }
        }
    }

    $beforeRestart = All-UiText "restart-before"
    Invoke-Adb -s $serial shell am force-stop $Package | Out-Null
    Invoke-Adb -s $serial shell am start -W -n $Activity | Out-Null
    Start-Sleep -Seconds 3
    $afterRestart = All-UiText "restart-after"
    if ($afterRestart -match 'Running package-fenced automation') {
        throw "Automation appears to have automatically replayed after restart."
    }
    $receipt.gates.restart_no_autoreplay = "PASS"
    $receipt.evidence.restart_before = Save-Text "restart-before.txt" ($beforeRestart + "`n")
    $receipt.evidence.restart_after = Save-Text "restart-after.txt" ($afterRestart + "`n")

    $logcat = Invoke-Adb -s $serial logcat -d -v threadtime
    $receipt.evidence.logcat = Save-Text "device-logcat.txt" ($logcat + "`n")

    $receipt.status = "PASS"
    if ($RunChatGptProbe) {
        $receipt.acceptance_boundary = "PHYSICAL_DEVICE_CORE_ACCESSIBILITY_SCREENSHOT_SIGNED_BEHAVIOR_CONTEXT_CHATGPT_ACCEPTED"
    } else {
        $receipt.acceptance_boundary = "PHYSICAL_DEVICE_CORE_ACCESSIBILITY_SCREENSHOT_SIGNED_BEHAVIOR_CONTEXT_ACCEPTED_CHATGPT_NOT_REQUESTED"
    }
    Write-Receipt $receipt
    Write-Host "DEVICE_ACCEPTANCE_PASS=1"
} catch {
    if ($receipt.status -eq "STARTED") {
        $receipt.status = "FAIL"
        $receipt.acceptance_boundary = "DEVICE_ACCEPTANCE_FAILED"
        $receipt.error = $_.Exception.Message
        try {
            $logcat = Invoke-Adb logcat -d -v threadtime
            $receipt.evidence.logcat = Save-Text "device-logcat.txt" ($logcat + "`n")
        } catch {}
        Write-Receipt $receipt
    }
    throw
}
