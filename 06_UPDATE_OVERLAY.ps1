param([switch]$StrictCollisions)
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
$log = Start-Log 'update_overlay'
$PulledO2r   = Join-Path $script:Build 'stock_mm_from_thor.o2r'
$GraphicsMod = Join-Path $script:Build $script:GfxName
try {
    Info "Комплект v$($script:Version) - обновление графического оверлея без переустановки"
    Info 'Приложение, mm.o2r, текстовый мод и сохранения НЕ трогаются.'

    Step '1/5 Проверка исходников'
    Assert-Hash $script:CleanRom 'SHA256' $script:CleanSha256 'Чистый MM USA v1.0'
    Assert-Hash $script:CleanRom 'SHA1'   $script:CleanSha1   'Чистый MM USA v1.0'
    Assert-Hash $script:RusRom   'SHA256' $script:RusSha256   'Zelda64rus 2.0b'
    Assert-Hash $script:RusRom   'SHA1'   $script:RusSha1     'Zelda64rus 2.0b'
    if(-not (Test-Path -LiteralPath $script:Builder)){ Fail "Нет утилиты: $($script:Builder)" }
    Info ("{0}  SHA256 {1}" -f (Split-Path -Leaf $script:Builder), (Get-FileHash -LiteralPath $script:Builder -Algorithm SHA256).Hash)

    Step '2/5 Подключение'
    $script:Java = Get-Java
    Connect-Device | Out-Null
    if((Adb-Capture @('shell','pm','path',$script:Package)) -notmatch 'package:'){ Fail 'Игра не установлена. Запустите 01_INSTALL.cmd' }
    if((Remote-Size "$($script:DevRoot)/mm.o2r") -lt 1048576){ Fail 'На устройстве нет mm.o2r. Запустите 01_INSTALL.cmd' }
    $wasActive = Test-GraphicsActive
    if($wasActive){ Info 'Сейчас графика включена в mods - новый оверлей будет подключён сразу.' }
    else { Info 'Сейчас графика в mods выключена - оверлей будет положен в запас.' }

    Step '3/5 Сборка оверлея из stock mm.o2r, который реально использует игра'
    Invoke-AdbChecked @('shell','am','force-stop',$script:Package) -AllowFailure -Quiet | Out-Null
    foreach($f in @($PulledO2r,$GraphicsMod)){ if(Test-Path -LiteralPath $f){ Remove-Item -LiteralPath $f -Force } }
    if((Pull-File "$($script:DevRoot)/mm.o2r" $PulledO2r) -ne 0){ Fail 'Не удалось забрать mm.o2r с устройства.' }
    $ovReport = Join-Path $script:Reports ("overlay_" + $script:Stamp + ".txt")
    $args2 = @('-jar',$script:Builder,$script:CleanRom,$script:RusRom,$PulledO2r,$GraphicsMod,$ovReport)
    if($StrictCollisions){ $args2 += '--strict-collisions' }
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; & $script:Java @args2 2>&1 | ForEach-Object { Write-Host ([string]$_) }; $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if($ec -ne 0 -or -not (Test-Path -LiteralPath $GraphicsMod)){ Fail 'Сборщик оверлея завершился ошибкой.' }
    Good "Оверлей: $((Get-Item -LiteralPath $GraphicsMod).Length) байт"
    Good "Отчёт: $ovReport"

    Step '4/5 Замена оверлея на устройстве'
    Remove-OldGraphics
    Invoke-AdbChecked @('shell','mkdir','-p',"$($script:DevProfiles)/graphics",$script:DevMods) | Out-Null
    Push-File $GraphicsMod "$($script:DevProfiles)/graphics/$($script:GfxName)" 'графический оверлей'
    if($wasActive){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/graphics/$($script:GfxName)","$($script:DevMods)/") | Out-Null
        Good 'Новый оверлей подключён.'
    } else {
        Info 'Включить: 02_SET_MOD_PROFILE.cmd both   (или graphics)'
    }

    Step '5/5 Проверка'
    Write-Host (Adb-Capture @('shell','ls','-la',$script:DevMods))
    Invoke-AdbChecked @('shell','monkey','-p',$script:Package,'-c','android.intent.category.LAUNCHER','1') -Quiet | Out-Null
    Write-Host ''
    Good 'Готово. Игра запущена с новым оверлеем.'
    Info "Лог: $log"
    Stop-Log; exit 0
}
catch {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host "Лог: $log" -ForegroundColor Yellow
    Stop-Log; exit 1
}
