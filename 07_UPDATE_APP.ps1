. (Join-Path $PSScriptRoot 'lib\Common.ps1')
$log = Start-Log 'update_app'
$SaveBackup = Join-Path $script:Build ("saves_backup_" + $script:Stamp)
try {
    Info "Комплект v$($script:Version) - обновление приложения поверх установленного"
    Info 'Данные приложения, mm.o2r, графический оверлей и сохранения остаются на месте.'

    Step '1/6 Проверка исходников'
    Assert-Hash $script:CleanRom 'SHA1' $script:CleanSha1 'Чистый MM USA v1.0'
    Assert-Hash $script:RusRom   'SHA1' $script:RusSha1   'Zelda64rus 2.0b'
    foreach($j in @($script:Patcher,$script:Verifier,$script:TitleTool,$script:TextTool)){
        if(-not (Test-Path -LiteralPath $j)){ Fail "Нет утилиты: $j" }
        Info ("{0}  SHA256 {1}" -f (Split-Path -Leaf $j), (Get-FileHash -LiteralPath $j -Algorithm SHA256).Hash)
    }

    Step '2/6 Подключение'
    $script:Java = Get-Java
    Connect-Device | Out-Null
    if((Adb-Capture @('shell','pm','path',$script:Package)) -notmatch 'package:'){ Fail 'Игра не установлена. Для первой установки запустите 01_INSTALL.cmd' }

    Step '3/6 Сборка пропатченного RU APK'
    if(-not (Test-RuApk $script:FinalApk)){ Build-ApkFromOfficial } else { Good 'Готовый пропатченный APK уже есть в build.' }
    if((Remote-Size "$($script:DevRoot)/mm.o2r") -lt 1048576){ Fail 'На устройстве нет mm.o2r. Запустите 01_INSTALL.cmd' }
    $stock = Join-Path $script:Build 'stock_mm_from_thor.o2r'
    if(-not (Test-Path -LiteralPath $stock)){
        if((Pull-File "$($script:DevRoot)/mm.o2r" $stock) -ne 0){ Fail 'Не удалось забрать mm.o2r с устройства.' }
    }
    Build-TitleTextMod $stock

    Step '4/6 Резервная копия сохранений (на всякий случай)'
    if(Test-RemotePath "$($script:DevRoot)/saves"){
        New-Item -ItemType Directory -Force -Path $SaveBackup | Out-Null
        if((Pull-File "$($script:DevRoot)/saves" $SaveBackup) -eq 0){ Good "Сохранения скопированы в: $SaveBackup" }
        else { Warn 'Снять сохранения не удалось - продолжаю, обновление поверх их не трогает.' }
    } else { Info 'Папки сохранений нет - копировать нечего.' }

    Step '5/6 Обновление приложения поверх (без удаления)'
    Invoke-AdbChecked @('shell','am','force-stop',$script:Package) -AllowFailure -Quiet | Out-Null
    $old = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'
          $out = @(& $script:Adb -s $script:Serial install -r -g $script:FinalApk 2>&1 | ForEach-Object { [string]$_ })
          $ec = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    $out | ForEach-Object { Info $_ }
    $txt = $out -join "`n"
    if($ec -ne 0 -or $txt -notmatch 'Success'){
        if($txt -match 'UPDATE_INCOMPATIBLE|signatures do not match|INCONSISTENT_CERTIFICATES'){
            Fail "Установленное приложение подписано другим ключом, обновить поверх нельзя.`nЗапустите 01_INSTALL.cmd - он сначала сохранит сейвы, а после установки вернёт их."
        }
        Fail "Установка не удалась:`n$txt"
    }
    Good 'Приложение обновлено, данные на месте.'

    Step '6/6 Текстовый мод с глифами титула'
    $textActive = ((Adb-Capture @('shell','ls',$script:DevMods)) -match '(Russian_MM_5\.0\.1|Zelda64Rus_MM_Text)')
    Remove-OldText
    Invoke-AdbChecked @('shell','mkdir','-p',"$($script:DevProfiles)/text",$script:DevMods) | Out-Null
    Push-File $script:TitleTextMod "$($script:DevProfiles)/text/$($script:TxtName)" 'текстовый мод'
    if($textActive){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/text/$($script:TxtName)","$($script:DevMods)/") | Out-Null
        Good 'Текстовый мод обновлён в mods.'
    } else {
        Info 'Текст сейчас выключен. Включить: 02_SET_MOD_PROFILE.cmd both   (или text)'
    }
    Write-Host (Adb-Capture @('shell','ls','-la',$script:DevMods))
    Invoke-AdbChecked @('shell','monkey','-p',$script:Package,'-c','android.intent.category.LAUNCHER','1') -Quiet | Out-Null
    Write-Host ''
    Good 'Готово. На титульном экране должно быть «НАЖМИ СТАРТ».'
    Info "Лог: $log"
    Stop-Log; exit 0
}
catch {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host "Лог: $log" -ForegroundColor Yellow
    Stop-Log; exit 1
}
