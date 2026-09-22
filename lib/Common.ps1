# 2Ship-Android-RU v1.0.0 - shared helpers
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new() } catch {}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$script:Pkg     = Split-Path -Parent $PSScriptRoot
$script:Parent  = Split-Path -Parent $script:Pkg
$script:Files   = Join-Path $script:Pkg 'files'
$script:Tools   = Join-Path $script:Pkg 'tools'
$script:Build   = Join-Path $script:Pkg 'build'
$script:Logs    = Join-Path $script:Pkg 'logs'
$script:Reports = Join-Path $script:Pkg 'reports'
New-Item -ItemType Directory -Force -Path $script:Files,$script:Tools,$script:Build,$script:Logs,$script:Reports | Out-Null

$script:Version = '1.0.0'
$script:Package = 'com.twoshipfork.mm'
$script:Stamp   = Get-Date -Format 'yyyyMMdd_HHmmss'

# --- expected source hashes -------------------------------------------------
$script:CleanSha256 = 'EFB1365B3AE362604514C0F9A1A2D11F5DC8688BA5BE660A37DEBF5E3BE43F2B'
$script:CleanSha1   = 'D6133ACE5AFAA0882CF214CF88DABA39E266C078'
$script:RusSha256   = 'EEB99C2830A96E845B9CDC98334CAF8CCAD9E176AF6D0349442577FDA6730EAB'
$script:RusSha1     = 'F01BBD2D7F633DDE6581C4099A28A8F3FFF8ED07'
$script:MsgSha256   = '9A7A124C31A958F80E00F5018B9E86EAC347F02AC2F6C74659068B85166B4F99'
$script:OfficialApkSha256 = '3BF3406661F71A28F8D09DBDDFDC51A410AB44BC1284B7C7508DF70A0B70BC3F'
$script:SignerSha256      = 'E1299FD6FCF4DA527DD53735B56127E8EA922A321128123B9C32D619BBA1D835'

$script:CleanRom = Join-Path $script:Files 'MM_USA_v1.0.z64'
$script:RusRom   = Join-Path $script:Files 'MM_RUS_2.0b.z64'
$script:MsgMod   = Join-Path $script:Files 'Russian_MM_5.0.1.o2r'

$script:Patcher  = Join-Path $script:Tools 'apk-ru-compat-patcher.jar'
$script:Verifier = Join-Path $script:Tools 'apk-ru-compat-verifier.jar'
$script:Builder  = Join-Path $script:Tools 'o2r-graphics-overlay-builder.jar'
$script:TitleTool = Join-Path $script:Tools 'o2r-title-glyph-patcher.jar'
$script:TextTool  = Join-Path $script:Tools 'o2r-text-mod-builder.jar'
$script:VcdiffTool = Join-Path $script:Tools 'vcdiff-patcher.jar'
$script:PatchDelta = Join-Path $script:Pkg 'third_party\zelda64rus\Zelda_MM(U)_(V1.0)_Rus_2.0b.delta'

$script:OfficialApkUrl   = 'https://github.com/linkzenic/2ship2harkinian-Android/releases/download/v5.0.1-android.2/2Ship-Android-v5.0.1-android.2.apk'
$script:PlatformToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$script:SignerUrl        = 'https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar'
$script:AdoptiumJreUrl   = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse'

# --- device paths -----------------------------------------------------------
$script:DevRoot     = '/sdcard/2S2H'
$script:DevMods     = '/sdcard/2S2H/mods'
$script:DevProfiles = '/sdcard/2S2H/ru_profiles'
$script:GfxName     = '10_Zelda64Rus_MM_Graphics.o2r'
$script:TxtName     = '20_Zelda64Rus_MM_Text.o2r'

function Step([string]$t){
    Write-Host ''
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}
function Good([string]$t){ Write-Host "[OK]   $t" -ForegroundColor Green }
function Warn([string]$t){ Write-Host "[!]    $t" -ForegroundColor Yellow }
function Info([string]$t){ Write-Host "       $t" -ForegroundColor Gray }
function Fail([string]$t){ throw $t }

function Start-Log([string]$name){
    $p = Join-Path $script:Logs ("{0}_{1}.log" -f $name, $script:Stamp)
    try { Start-Transcript -LiteralPath $p -Force | Out-Null } catch {}
    return $p
}
function Stop-Log { try { Stop-Transcript | Out-Null } catch {} }

function Test-Hash([string]$Path,[string]$Alg,[string]$Expected){
    if(-not (Test-Path -LiteralPath $Path)){ return $false }
    try { return ((Get-FileHash -LiteralPath $Path -Algorithm $Alg).Hash.ToUpperInvariant() -eq $Expected.ToUpperInvariant()) }
    catch { return $false }
}
function Assert-Hash([string]$Path,[string]$Alg,[string]$Expected,[string]$Label){
    if(-not (Test-Path -LiteralPath $Path)){ Fail "$Label не найден: $Path" }
    $a = (Get-FileHash -LiteralPath $Path -Algorithm $Alg).Hash.ToUpperInvariant()
    if($a -ne $Expected.ToUpperInvariant()){
        Fail "${Label}: контрольная сумма не совпадает.`nОжидалось: $Expected`nПолучено:  $a"
    }
    Good "$Label ($Alg $a)"
}
function Get-SiblingRoots {
    if(-not (Test-Path -LiteralPath $script:Parent)){ return @() }
    return @(Get-ChildItem -LiteralPath $script:Parent -Directory -ErrorAction SilentlyContinue |
             Where-Object { ($_.Name -like '2Ship-Android-RU*' -or $_.Name -like 'MM_Thor_Pro_RU*') -and $_.FullName -ne $script:Pkg })
}
function Download-FileResilient([string]$Url,[string]$Dest,[string]$Label){
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dest) | Out-Null
    $errs = New-Object System.Collections.Generic.List[string]
    for($i=1; $i -le 3; $i++){
        Info "Скачивание: $Label (попытка $i/3)"
        if(Test-Path -LiteralPath $Dest){ Remove-Item -LiteralPath $Dest -Force -ErrorAction SilentlyContinue }
        try {
            Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing -Headers @{ 'User-Agent'='Mozilla/5.0 2S2H-RU-Toolkit' }
            if((Test-Path -LiteralPath $Dest) -and ((Get-Item -LiteralPath $Dest).Length -gt 0)){ return }
        } catch { $errs.Add("Invoke-WebRequest: $($_.Exception.Message)") }
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if($curl){
            $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            & $curl.Source -L --fail --retry 2 --connect-timeout 20 --max-time 600 -o $Dest $Url 2>&1 | Out-Host
            $ec = $LASTEXITCODE; $ErrorActionPreference = $old
            if(($ec -eq 0) -and (Test-Path -LiteralPath $Dest) -and ((Get-Item -LiteralPath $Dest).Length -gt 0)){ return }
            $errs.Add("curl exit=$ec")
        }
        Start-Sleep -Seconds (2*$i)
    }
    Fail "Не удалось скачать $Label.`n$($errs -join "`n")"
}
function Test-JavaExe([string]$p){
    if(-not (Test-Path -LiteralPath $p)){ return $false }
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; $t = ((& $p -version 2>&1) | Out-String); $ec = $LASTEXITCODE }
    catch { return $false } finally { $ErrorActionPreference = $old }
    if($ec -ne 0){ return $false }
    $maj = 0
    if($t -match 'version\s+"1\.(\d+)'){ $maj = [int]$Matches[1] }
    elseif($t -match 'version\s+"(\d+)'){ $maj = [int]$Matches[1] }
    return ($maj -ge 11)
}
function Get-Java {
    $c = Get-Command java.exe -ErrorAction SilentlyContinue
    if($c -and (Test-JavaExe $c.Source)){ Good "Java: $($c.Source)"; return $c.Source }
    $local = Get-ChildItem -LiteralPath (Join-Path $script:Tools 'jre17') -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if($local -and (Test-JavaExe $local.FullName)){ Good "Java (локальная): $($local.FullName)"; return $local.FullName }
    foreach($r in (Get-SiblingRoots)){
        $cand = Get-ChildItem -LiteralPath (Join-Path $r.FullName 'tools\jre17') -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if($cand -and (Test-JavaExe $cand.FullName)){ Good "Java из прошлого комплекта: $($cand.FullName)"; return $cand.FullName }
    }
    Step 'Загрузка portable Java 17'
    $jr = Join-Path $script:Tools 'jre17'
    $zip = Join-Path $script:Tools 'temurin-jre17.zip'
    Download-FileResilient $script:AdoptiumJreUrl $zip 'Eclipse Temurin JRE 17'
    if(Test-Path -LiteralPath $jr){ Remove-Item -LiteralPath $jr -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $jr | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $jr -Force
    $j = Get-ChildItem -LiteralPath $jr -Filter java.exe -Recurse | Select-Object -First 1
    if(-not $j -or -not (Test-JavaExe $j.FullName)){ Fail 'java.exe не найдена после распаковки JRE.' }
    Good "Java: $($j.FullName)"; return $j.FullName
}
function Test-AdbExe([string]$p){
    if(-not (Test-Path -LiteralPath $p)){ return $false }
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; & $p version 2>&1 | Out-Null; $ec = $LASTEXITCODE }
    catch { return $false } finally { $ErrorActionPreference = $old }
    return ($ec -eq 0)
}
function Get-Adb {
    $c = Get-Command adb.exe -ErrorAction SilentlyContinue
    if($c -and (Test-AdbExe $c.Source)){ Good "ADB: $($c.Source)"; return $c.Source }
    $local = Join-Path $script:Tools 'platform-tools\adb.exe'
    if(Test-AdbExe $local){ Good "ADB: $local"; return $local }
    foreach($r in (Get-SiblingRoots)){
        $cand = Join-Path $r.FullName 'tools\platform-tools\adb.exe'
        if(Test-AdbExe $cand){ Good "ADB из прошлого комплекта: $cand"; return $cand }
    }
    Step 'Загрузка Android platform-tools'
    $zip = Join-Path $script:Tools 'platform-tools.zip'
    Download-FileResilient $script:PlatformToolsUrl $zip 'Google Android platform-tools'
    $pt = Join-Path $script:Tools 'platform-tools'
    if(Test-Path -LiteralPath $pt){ Remove-Item -LiteralPath $pt -Recurse -Force }
    Expand-Archive -LiteralPath $zip -DestinationPath $script:Tools -Force
    if(-not (Test-AdbExe $local)){ Fail 'adb.exe не найдена после распаковки.' }
    Good "ADB: $local"; return $local
}
function Adb-Capture([string[]]$a){
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; $o = @(& $script:Adb -s $script:Serial @a 2>&1); $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0){ return '' }
    return (($o | ForEach-Object { [string]$_ }) -join "`n").Trim()
}
function Invoke-AdbChecked {
    param([Parameter(Mandatory=$true,Position=0)][string[]]$A,[switch]$AllowFailure,[switch]$Quiet)
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; $o = @(& $script:Adb -s $script:Serial @A 2>&1); $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if((-not $Quiet) -and $o.Count -gt 0){ $o | ForEach-Object { Write-Host ([string]$_) } }
    if(($ec -ne 0) -and (-not $AllowFailure)){
        $d = ($o | ForEach-Object { [string]$_ } | Out-String).Trim()
        Fail "ADB завершился с кодом ${ec}: adb $($A -join ' ')`n$d"
    }
    return $ec
}
# exit codes from "adb shell" are unreliable on some builds - probe by output instead
function Test-RemotePath([string]$p){
    $o = Adb-Capture @('shell', "if [ -e '$p' ]; then echo YES; else echo NO; fi")
    return ($o -match 'YES')
}
function Remote-Size([string]$p){
    $s = Adb-Capture @('shell','wc','-c',$p)
    if($s -match '^\s*(\d+)'){ return [long]$Matches[1] }
    return [long]0
}
function Connect-Device {
    $script:Adb = Get-Adb
    & $script:Adb start-server | Out-Null
    $lines = @(& $script:Adb devices | Select-String -Pattern "\tdevice$")
    if($lines.Count -eq 0){
        Warn 'Thor Pro пока не виден. Подтвердите USB debugging - ожидание до 90 секунд.'
        for($i=0; $i -lt 45 -and $lines.Count -eq 0; $i++){
            Start-Sleep 2
            $lines = @(& $script:Adb devices | Select-String -Pattern "\tdevice$")
        }
    }
    if($lines.Count -ne 1){ Fail 'Нужно ровно одно авторизованное Android-устройство.' }
    $script:Serial = ($lines[0].Line -split "\t")[0].Trim()
    $model = (Adb-Capture @('shell','getprop','ro.product.model')).Trim()
    $abi   = (Adb-Capture @('shell','getprop','ro.product.cpu.abi')).Trim()
    Good "Устройство: $model / $abi / serial $($script:Serial)"
    if($abi -notmatch 'arm64-v8a'){ Fail 'Подключено не ARM64-устройство.' }
    return @{ Model = $model; Abi = $abi }
}
function Invoke-AdbIO([string[]]$a){
    # adb prints transfer progress to stderr; show it as plain grey text instead of red NativeCommandError noise
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $script:Adb -s $script:Serial @a 2>&1 | ForEach-Object { Write-Host ('       ' + [string]$_) -ForegroundColor DarkGray }
        $ec = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
    return $ec
}
function Push-File([string]$local,[string]$remote,[string]$label){
    if((Invoke-AdbIO @('push',$local,$remote)) -ne 0){ Fail "Не удалось скопировать $label на устройство." }
}
function Pull-File([string]$remote,[string]$local){
    return (Invoke-AdbIO @('pull',$remote,$local))
}
function Remove-OldGraphics {
    # removes every generation of the graphics overlay (v4.x, v5, v51...) from mods and the profile store
    $pat = "00_Zelda64Rus_Graphics*.o2r 10_Zelda64Rus_MM_Graphics*.o2r"
    $cmd = "cd $($script:DevMods) && rm -f $pat ; cd $($script:DevProfiles)/graphics && rm -f $pat"
    Invoke-AdbChecked @('shell', $cmd) -AllowFailure -Quiet | Out-Null
}
function Test-GraphicsActive {
    return ((Adb-Capture @('shell','ls',$script:DevMods)) -match 'Zelda64Rus.*Graphics')
}

# ---------------- APK build (shared by 01_INSTALL and 07_UPDATE_APP) ----------------
$FinalApk     = Join-Path $script:Build '2Ship-RU-patched.apk'
$OfficialApk  = Join-Path $script:Build '2Ship-Android-v5.0.1-android.2-OFFICIAL.apk'
$UnsignedApk  = Join-Path $script:Build '2Ship-RU-UNSIGNED.apk'
$SignedDir    = Join-Path $script:Build 'signed'
$script:TitleTextMod = Join-Path $script:Build $script:TxtName
$script:BaseTextMod  = Join-Path $script:Build 'Zelda64Rus_MM_Text_base.o2r'

function Test-RuApk([string]$p){
    if(-not (Test-Path -LiteralPath $p)){ return $false }
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; & $script:Java -jar $script:Verifier $p 2>&1 | Out-Host; $ec = $LASTEXITCODE }
    catch { return $false } finally { $ErrorActionPreference = $old }
    return ($ec -eq 0)
}
function Build-ApkFromOfficial {
    Step 'Сборка RU APK из официального билда'
    if(-not (Test-Hash $OfficialApk 'SHA256' $script:OfficialApkSha256)){
        $got = $false
        foreach($r in (Get-SiblingRoots)){
            $cand = Join-Path $r.FullName 'build\2Ship-Android-v5.0.1-android.2-OFFICIAL.apk'
            if(Test-Hash $cand 'SHA256' $script:OfficialApkSha256){
                Copy-Item -LiteralPath $cand -Destination $OfficialApk -Force
                Good "Официальный APK взят из локального кэша: $cand"; $got = $true; break
            }
        }
        if(-not $got){
            Download-FileResilient $script:OfficialApkUrl $OfficialApk '2Ship Android 5.0.1-android.2'
            Assert-Hash $OfficialApk 'SHA256' $script:OfficialApkSha256 'Официальный APK'
        }
    } else { Good 'Официальный APK уже есть и проверен.' }

    $signer = Join-Path $script:Tools 'uber-apk-signer-1.3.0.jar'
    if(-not (Test-Hash $signer 'SHA256' $script:SignerSha256)){
        $got = $false
        foreach($r in (Get-SiblingRoots)){
            $cand = Join-Path $r.FullName 'tools\uber-apk-signer-1.3.0.jar'
            if(Test-Hash $cand 'SHA256' $script:SignerSha256){ Copy-Item -LiteralPath $cand -Destination $signer -Force; $got = $true; break }
        }
        if(-not $got){
            Download-FileResilient $script:SignerUrl $signer 'Uber APK Signer 1.3.0'
            Assert-Hash $signer 'SHA256' $script:SignerSha256 'Uber APK Signer'
        }
    }

    if(Test-Path -LiteralPath $UnsignedApk){ Remove-Item -LiteralPath $UnsignedApk -Force }
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; & $script:Java -jar $script:Patcher $OfficialApk $UnsignedApk 2>&1 | Out-Host; $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0 -or -not (Test-Path -LiteralPath $UnsignedApk)){ Fail 'APK patcher не смог собрать сборку.' }

    if(Test-Path -LiteralPath $SignedDir){ Remove-Item -LiteralPath $SignedDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $SignedDir | Out-Null
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; & $script:Java -jar $signer --apks $UnsignedApk --out $SignedDir 2>&1 | Out-Host; $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0){ Fail 'Подпись APK не удалась.' }
    $s = Get-ChildItem -LiteralPath $SignedDir -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(-not $s){ Fail 'Подписанный APK не найден.' }
    Copy-Item -LiteralPath $s.FullName -Destination $FinalApk -Force
    if(-not (Test-RuApk $FinalApk)){ Fail 'Новый APK не прошёл RU-проверку.' }
    Good "RU APK собран: $FinalApk"
}

function Build-TitleTextMod([string]$StockO2r){
    # 1) собрать текстовый мод (диалоги + шрифт) прямо из русского образа
    # 2) положить в слоты Q и W глифы Ж и И - для надписи "НАЖМИ СТАРТ"
    if(-not (Test-Path -LiteralPath $StockO2r)){ Fail "Нет stock mm.o2r: $StockO2r" }
    foreach($f in @($script:BaseTextMod,$script:TitleTextMod)){ if(Test-Path -LiteralPath $f){ Remove-Item -LiteralPath $f -Force } }
    $txtReport = Join-Path $script:Reports ("textmod_" + $script:Stamp + ".txt")
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'
          & $script:Java -jar $script:TextTool $script:CleanRom $script:RusRom $StockO2r $script:BaseTextMod $txtReport 2>&1 |
              ForEach-Object { Write-Host ('       ' + [string]$_) -ForegroundColor DarkGray }
          $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0 -or -not (Test-Path -LiteralPath $script:BaseTextMod)){ Fail 'Не удалось собрать текстовый мод из образа.' }
    Good "Текстовый мод собран из вашего образа: $((Get-Item -LiteralPath $script:BaseTextMod).Length) байт"
    Info "Отчёт: $txtReport"

    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'
          & $script:Java -jar $script:TitleTool $script:BaseTextMod $script:TitleTextMod 2>&1 |
              ForEach-Object { Write-Host ('       ' + [string]$_) -ForegroundColor DarkGray }
          $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0 -or -not (Test-Path -LiteralPath $script:TitleTextMod)){ Fail 'Не удалось добавить глифы титульного экрана.' }
    Good "Готовый текстовый мод: $($script:TxtName)"
}
function Remove-OldText {
    # removes every generation of the text mod (original and RU_Title) from mods and the profile store
    $pat = "Russian_MM_5.0.1*.o2r 20_Zelda64Rus_MM_Text*.o2r"
    $cmd = "cd $($script:DevMods) && rm -f $pat ; cd $($script:DevProfiles)/text && rm -f $pat"
    Invoke-AdbChecked @('shell', $cmd) -AllowFailure -Quiet | Out-Null
}
