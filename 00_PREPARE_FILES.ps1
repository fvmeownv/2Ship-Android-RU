. (Join-Path $PSScriptRoot 'lib\Common.ps1')
$log = Start-Log 'prepare'
try {
    Step 'Подготовка исходных файлов'
    Write-Host 'Нужен один ваш файл:' -ForegroundColor White
    Info 'MM_USA_v1.0.z64  - ваш образ Majora''s Mask USA v1.0 (33 554 432 байта)'
    Write-Host ''
    Info 'Русский образ будет создан из него вложенным патчем Zelda64Rus.'
    Write-Host ''

    $targets = @(
        @{ Name='MM_USA_v1.0.z64'; Dest=$script:CleanRom; Sha=$script:CleanSha256;
           Alt=@('Legend_of_Zelda__The_-_Majora_s_Mask__USA_.z64','Zelda_MM(U)_(V1.0).z64','MM.z64') },
        @{ Name='MM_RUS_2.0b.z64'; Dest=$script:RusRom;   Sha=$script:RusSha256;
           Alt=@("Legend of Zelda, The - Majora's Mask (U) [!] (T+Rus v2.0b).z64") }
    )

    $scan = New-Object System.Collections.Generic.List[string]
    foreach($r in (Get-SiblingRoots)){ $scan.Add((Join-Path $r.FullName 'files')) }
    $scan.Add($script:Parent)
    $scan.Add((Join-Path $env:USERPROFILE 'Downloads'))
    $scan.Add((Join-Path $env:USERPROFILE 'Desktop'))
    $scan.Add($script:Files)

    $missing = @()
    foreach($t in $targets){
        if(Test-Hash $t.Dest 'SHA256' $t.Sha){ Good "$($t.Name) уже на месте и проверен."; continue }
        $found = $null
        foreach($dir in $scan){
            if(-not (Test-Path -LiteralPath $dir)){ continue }
            foreach($n in (@($t.Name) + $t.Alt)){
                foreach($cand in @(Get-ChildItem -LiteralPath $dir -Filter $n -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 5)){
                    if(Test-Hash $cand.FullName 'SHA256' $t.Sha){ $found = $cand.FullName; break }
                }
                if($found){ break }
            }
            if($found){ break }
        }
        if($found){
            Copy-Item -LiteralPath $found -Destination $t.Dest -Force
            Good "$($t.Name) найден и скопирован из: $found"
        } else {
            $missing += $t.Name
            Info "$($t.Name) автоматически не найден."
        }
    }

    # русский образ создаётся из вашего образа вложенным патчем Zelda64Rus
    if(($missing -contains 'MM_RUS_2.0b.z64') -and (Test-Hash $script:CleanRom 'SHA256' $script:CleanSha256)){
        if(-not (Test-Path -LiteralPath $script:PatchDelta)){ Fail "Нет патча: $($script:PatchDelta)" }
        Step 'Создание русского образа патчем Zelda64Rus'
        Info 'Перевод: (c) ШЕДЕВР 2006, 2007 / (c) Zelda64RUS 2019 - shedevr.org.ru/zelda64rus'
        Info 'Подробности и условия: third_party\zelda64rus\NOTICE.md'
        $script:Java = Get-Java
        $old = $ErrorActionPreference
        try { $ErrorActionPreference='Continue'
              & $script:Java -jar $script:VcdiffTool $script:CleanRom $script:PatchDelta $script:RusRom $script:RusSha1 2>&1 |
                  ForEach-Object { Write-Host ('       ' + [string]$_) -ForegroundColor DarkGray }
              $ec = $LASTEXITCODE }
        finally { $ErrorActionPreference = $old }
        if($ec -ne 0 -or -not (Test-Path -LiteralPath $script:RusRom)){ Fail 'Не удалось создать русский образ.' }
        Good 'Русский образ создан и проверен по контрольной сумме.'
        $missing = @($missing | Where-Object { $_ -ne 'MM_RUS_2.0b.z64' })
    }

    if($missing.Count -gt 0){
        Write-Host ''
        Warn 'Не хватает файла. Положите его в папку files и запустите скрипт снова:'
        foreach($m in $missing){ Info "  $m" }
        Write-Host ''
        Info "Папка: $($script:Files)"
        Write-Host ''
        Info 'Нужен образ Majora''s Mask NTSC-U версии 1.0, снятый с вашего картриджа.'
        Info 'Размер 33 554 432 байта, SHA-1 D6133ACE5AFAA0882CF214CF88DABA39E266C078'
        Stop-Log; exit 1
    }

    Step 'Финальная проверка'
    Assert-Hash $script:CleanRom 'SHA256' $script:CleanSha256 'Чистый MM USA v1.0'
    Assert-Hash $script:CleanRom 'SHA1'   $script:CleanSha1   'Чистый MM USA v1.0'
    Assert-Hash $script:RusRom   'SHA256' $script:RusSha256   'Zelda64rus 2.0b'
    Assert-Hash $script:RusRom   'SHA1'   $script:RusSha1     'Zelda64rus 2.0b'
    Write-Host ''
    Good 'Всё готово. Дальше запускайте 01_INSTALL.cmd'
    Stop-Log; exit 0
}
catch {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host "Лог: $log" -ForegroundColor Yellow
    Stop-Log; exit 1
}
