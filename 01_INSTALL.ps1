param(
    [ValidateSet('none','text','graphics','both')] [string]$ModProfile = 'both',
    [switch]$NoSaveRestore
)
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
$log = Start-Log 'install'

$PulledApkDir = Join-Path $script:Build 'pulled_apk'
$PulledO2r    = Join-Path $script:Build 'stock_mm_from_thor.o2r'
$GraphicsMod  = Join-Path $script:Build $script:GfxName
$SaveBackup   = Join-Path $script:Build ("saves_backup_" + $script:Stamp)

function Try-PullInstalledApk {
    $raw = Adb-Capture @('shell','pm','path',$script:Package)
    if([string]::IsNullOrWhiteSpace($raw)){ return $false }
    $paths = @()
    foreach($l in ($raw -split "`n")){ if($l -match 'package:(\S+)'){ $paths += $Matches[1].Trim() } }
    if($paths.Count -eq 0){ return $false }
    if($paths.Count -gt 1){
        Warn "Установлено $($paths.Count) APK (split-сборка). Переиспользовать такой набор небезопасно - соберу APK заново."
        return $false
    }
    if(Test-Path -LiteralPath $PulledApkDir){ Remove-Item -LiteralPath $PulledApkDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $PulledApkDir | Out-Null
    $dst = Join-Path $PulledApkDir 'base.apk'
    if((Pull-File $paths[0] $dst) -ne 0){ return $false }
    if(Test-RuApk $dst){
        Copy-Item -LiteralPath $dst -Destination $FinalApk -Force
        Good 'Рабочий RU APK снят с устройства и прошёл проверку.'
        return $true
    }
    Warn 'APK на устройстве есть, но RU-проверку он не прошёл.'
    return $false
}
function Try-SiblingApk {
    foreach($r in (Get-SiblingRoots)){
        $dir = Join-Path $r.FullName 'build'
        if(-not (Test-Path -LiteralPath $dir)){ continue }
        $c = @(Get-ChildItem -LiteralPath $dir -Filter '*2Ship-RU*.apk' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
        foreach($f in $c){
            if(Test-RuApk $f.FullName){
                Copy-Item -LiteralPath $f.FullName -Destination $FinalApk -Force
                Good "Проверенный RU APK взят из прошлого комплекта: $($f.FullName)"
                return $true
            }
        }
    }
    return $false
}
function Wait-O2R([int]$seconds){
    $last = [long]0; $stable = 0
    for($e = 0; $e -lt $seconds; $e += 2){
        $sz = Remote-Size "$($script:DevRoot)/mm.o2r"
        if(($sz -gt 1048576) -and ($sz -eq $last)){ $stable++ } else { $stable = 0 }
        if(($sz -gt 0) -and (($e % 10) -eq 0)){ Info "mm.o2r: $sz байт" }
        if($stable -ge 3){ Good "mm.o2r создан и стабилен: $sz байт"; return $true }
        $last = $sz; Start-Sleep 2
    }
    return $false
}

try {
    Info "Комплект v$($script:Version)"
    Step '1/11 Проверка исходных файлов'
    if(-not (Test-Path -LiteralPath $script:CleanRom)){ Fail 'Нет files\MM_USA_v1.0.z64. Сначала запустите 00_PREPARE_FILES.cmd' }
    Assert-Hash $script:CleanRom 'SHA256' $script:CleanSha256 'Чистый MM USA v1.0'
    Assert-Hash $script:CleanRom 'SHA1'   $script:CleanSha1   'Чистый MM USA v1.0'
    Assert-Hash $script:RusRom   'SHA256' $script:RusSha256   'Zelda64rus 2.0b'
    Assert-Hash $script:RusRom   'SHA1'   $script:RusSha1     'Zelda64rus 2.0b'
    foreach($j in @($script:Patcher,$script:Verifier,$script:Builder,$script:TitleTool,$script:TextTool)){
        if(-not (Test-Path -LiteralPath $j)){ Fail "Нет утилиты: $j" }
        Info ("{0}  SHA256 {1}" -f (Split-Path -Leaf $j), (Get-FileHash -LiteralPath $j -Algorithm SHA256).Hash)
    }

    Step '2/11 Инструменты и подключение'
    $script:Java = Get-Java
    $dev = Connect-Device
    if($dev.Model -notmatch '(?i)thor'){
        Warn "Модель устройства: $($dev.Model). Это не похоже на AYN Thor."
        $ans = Read-Host 'Продолжить полную переустановку на этом устройстве? (введите ДА)'
        if($ans -ne 'ДА'){ Fail 'Отменено пользователем.' }
    }

    Step '3/11 Фиксация проверенного RU APK ДО любых разрушительных действий'
    $have = $false
    if(Test-RuApk $FinalApk){ Good 'Готовый RU APK уже есть в build.'; $have = $true }
    if(-not $have){ $have = Try-PullInstalledApk }
    if(-not $have){ $have = Try-SiblingApk }
    if(-not $have){ Build-ApkFromOfficial; $have = $true }
    if(-not (Test-RuApk $FinalApk)){ Fail 'Проверенный RU APK получить не удалось. Устройство НЕ тронуто.' }
    $apkReport = Join-Path $script:Reports ("apk_verify_" + $script:Stamp + ".txt")
    & $script:Java -jar $script:Verifier $FinalApk $apkReport 2>&1 | Out-Null
    Good "Отчёт по APK: $apkReport"

    Step '4/11 Резервная копия сохранений'
    $hadSaves = $false
    if(Test-RemotePath "$($script:DevRoot)/saves"){
        New-Item -ItemType Directory -Force -Path $SaveBackup | Out-Null
        if((Pull-File "$($script:DevRoot)/saves" $SaveBackup) -eq 0){
            $hadSaves = $true
            Good "Сохранения скопированы в: $SaveBackup"
        } else { Warn 'Не удалось снять сохранения (возможно, их ещё нет).' }
    } else { Info 'Папки сохранений на устройстве нет - копировать нечего.' }

    Step '5/11 Полная очистка предыдущей установки'
    Warn 'Старые данные приложения удаляются. Резервная копия сохранений сделана на шаге 4.'
    Invoke-AdbChecked @('shell','am','force-stop',$script:Package) -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','pm','clear',$script:Package)      -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('uninstall',$script:Package)               -AllowFailure -Quiet | Out-Null
    if(Test-RemotePath "/data/app/$($script:Package)"){ Warn 'Каталог приложения ещё виден, продолжаю.' }
    $wipe = @(
        '/sdcard/2S2H','/storage/emulated/0/2S2H',
        '/sdcard/Download/2S2H_MM_RU','/storage/emulated/0/Download/2S2H_MM_RU',
        "/sdcard/Android/data/$($script:Package)","/storage/emulated/0/Android/data/$($script:Package)",
        "/sdcard/Android/media/$($script:Package)","/storage/emulated/0/Android/media/$($script:Package)",
        "/sdcard/Android/obb/$($script:Package)","/storage/emulated/0/Android/obb/$($script:Package)"
    )
    foreach($t in $wipe){ Invoke-AdbChecked @('shell','rm','-rf',$t) -AllowFailure -Quiet | Out-Null }
    if(Test-RemotePath $script:DevRoot){ Fail "$($script:DevRoot) осталась после очистки." }
    Good 'Предыдущая установка удалена.'

    Step '6/11 Чистая установка RU APK'
    Invoke-AdbChecked @('install','-g',$FinalApk) | Out-Null
    if((Adb-Capture @('shell','pm','path',$script:Package)) -notmatch 'package:'){ Fail 'APK не виден Package Manager.' }
    Good 'RU APK установлен с нуля.'

    Step '7/11 Генерация stock mm.o2r из чистого USA v1.0'
    Invoke-AdbChecked @('shell','mkdir','-p',$script:DevRoot,'/sdcard/Download/2S2H_MM_RU') | Out-Null
    Push-File $script:CleanRom "$($script:DevRoot)/MM.z64" 'чистый ROM'
    Push-File $script:CleanRom '/sdcard/Download/2S2H_MM_RU/MM_USA_v1.0.z64' 'чистый ROM (Download)'
    Invoke-AdbChecked @('shell','appops','set',$script:Package,'MANAGE_EXTERNAL_STORAGE','allow') -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','monkey','-p',$script:Package,'-c','android.intent.category.LAUNCHER','1') -Quiet | Out-Null
    if(-not (Wait-O2R 30)){
        Warn '2Ship ждёт подтверждения на экране.'
        Write-Host 'На Thor разрешите доступ к файлам и подтвердите Setup.' -ForegroundColor White
        Write-Host 'Если попросит ROM: Download -> 2S2H_MM_RU -> MM_USA_v1.0.z64' -ForegroundColor Cyan
        Write-Host 'Ничего не нажимайте в этом окне - жду до 15 минут.' -ForegroundColor Yellow
        if(-not (Wait-O2R 900)){ Fail 'Stock mm.o2r так и не был создан.' }
    }

    Step '8/11 Сборка графического оверлея Zelda64rus'
    Invoke-AdbChecked @('shell','am','force-stop',$script:Package) -AllowFailure -Quiet | Out-Null
    if(Test-Path -LiteralPath $PulledO2r){ Remove-Item -LiteralPath $PulledO2r -Force }
    if(Test-Path -LiteralPath $GraphicsMod){ Remove-Item -LiteralPath $GraphicsMod -Force }
    if((Pull-File "$($script:DevRoot)/mm.o2r" $PulledO2r) -ne 0){ Fail 'Не удалось забрать stock mm.o2r.' }
    $ovReport = Join-Path $script:Reports ("overlay_" + $script:Stamp + ".txt")
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'
          & $script:Java -jar $script:Builder $script:CleanRom $script:RusRom $PulledO2r $GraphicsMod $ovReport 2>&1 | Out-Host
          $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0 -or -not (Test-Path -LiteralPath $GraphicsMod)){ Fail 'Сборщик графического оверлея завершился ошибкой.' }
    Good "Оверлей: $((Get-Item -LiteralPath $GraphicsMod).Length) байт"
    Good "Отчёт оверлея: $ovReport"
    Build-TitleTextMod $PulledO2r

    Step '9/11 Раскладка профилей для изоляции багов'
    Invoke-AdbChecked @('shell','mkdir','-p',$script:DevMods,"$($script:DevProfiles)/text","$($script:DevProfiles)/graphics") | Out-Null
    Remove-OldGraphics
    Remove-OldText
    Push-File $GraphicsMod  "$($script:DevProfiles)/graphics/$($script:GfxName)" 'графический оверлей'
    Push-File $script:TitleTextMod "$($script:DevProfiles)/text/$($script:TxtName)" 'текстовый мод'
    Good 'Оба мода лежат в ru_profiles и переключаются скриптом 02.'

    Step '10/11 Применение профиля и возврат сохранений'
    Invoke-AdbChecked @('shell','rm','-rf',$script:DevMods) -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','mkdir','-p',$script:DevMods) | Out-Null
    if($ModProfile -eq 'text' -or $ModProfile -eq 'both'){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/text/$($script:TxtName)","$($script:DevMods)/") | Out-Null }
    if($ModProfile -eq 'graphics' -or $ModProfile -eq 'both'){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/graphics/$($script:GfxName)","$($script:DevMods)/") | Out-Null }
    Good "Активный профиль: $ModProfile"

    if($hadSaves -and -not $NoSaveRestore){
        $src = Join-Path $SaveBackup 'saves'
        if(-not (Test-Path -LiteralPath $src)){ $src = $SaveBackup }
        Push-File $src "$($script:DevRoot)/" 'сохранения'
        Good 'Сохранения возвращены на устройство.'
    } elseif($hadSaves){ Info 'Возврат сохранений отключён ключом -NoSaveRestore.' }

    Step '11/11 Финальная проверка и запуск'
    if((Remote-Size "$($script:DevRoot)/mm.o2r") -lt 1048576){ Fail 'mm.o2r отсутствует или слишком мал.' }
    Info ('Содержимое mods: ' + (Adb-Capture @('shell','ls','-la',$script:DevMods)))
    Invoke-AdbChecked @('shell','rm','-rf','/sdcard/Download/2S2H_MM_RU') -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','monkey','-p',$script:Package,'-c','android.intent.category.LAUNCHER','1') -Quiet | Out-Null
    Write-Host ''
    Good "Установка v$($script:Version) завершена."
    Write-Host ''
    Write-Host 'Дальше по плану отлова багов:' -ForegroundColor White
    Info '1. Перед игрой запустите 03_LOGCAT_LIVE.cmd и не закрывайте его.'
    Info '2. Играйте до вылета.'
    Info '3. Сразу после вылета запустите 04_COLLECT_LOGS.cmd.'
    Info '4. Профили переключаются: 02_SET_MOD_PROFILE.cmd none|text|graphics|both'
    Info '5. Новый оверлей без переустановки: 06_UPDATE_OVERLAY.cmd'
    Info '6. Новое приложение поверх старого с сохранением данных: 07_UPDATE_APP.cmd'
    Write-Host ''
    Info "Отчёты: $($script:Reports)"
    Info "Лог: $log"
    Stop-Log; exit 0
}
catch {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host "Лог: $log" -ForegroundColor Yellow
    Stop-Log; exit 1
}
