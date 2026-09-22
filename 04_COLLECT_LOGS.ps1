. (Join-Path $PSScriptRoot 'lib\Common.ps1')
try {
    Connect-Device | Out-Null
    $dir = Join-Path $script:Logs ("crashkit_" + $script:Stamp)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Step 'Сбор диагностики'

    Adb-Capture @('logcat','-b','all','-d','-v','threadtime') | Out-File -LiteralPath (Join-Path $dir 'logcat_buffered.txt') -Encoding utf8
    Good 'logcat (буфер) снят'

    Adb-Capture @('logcat','-b','crash','-d','-v','threadtime') | Out-File -LiteralPath (Join-Path $dir 'logcat_crash.txt') -Encoding utf8
    Good 'crash-буфер снят'

    Adb-Capture @('shell','dumpsys','meminfo',$script:Package) | Out-File -LiteralPath (Join-Path $dir 'meminfo.txt') -Encoding utf8
    Adb-Capture @('shell','dumpsys','package',$script:Package) | Out-File -LiteralPath (Join-Path $dir 'package.txt') -Encoding utf8
    Good 'meminfo и package сняты'

    $props = @('ro.product.model','ro.product.manufacturer','ro.build.version.release','ro.build.version.sdk',
               'ro.product.cpu.abi','ro.hardware','ro.board.platform','ro.hardware.egl','dalvik.vm.heapsize')
    $sb = New-Object System.Text.StringBuilder
    foreach($p in $props){ [void]$sb.AppendLine(("{0}={1}" -f $p, (Adb-Capture @('shell','getprop',$p)))) }
    $sb.ToString() | Out-File -LiteralPath (Join-Path $dir 'device.txt') -Encoding utf8
    Good 'Свойства устройства сняты'

    $st = New-Object System.Text.StringBuilder
    [void]$st.AppendLine('--- 2S2H root ---')
    [void]$st.AppendLine((Adb-Capture @('shell','ls','-la',$script:DevRoot)))
    [void]$st.AppendLine('--- mods ---')
    [void]$st.AppendLine((Adb-Capture @('shell','ls','-la',$script:DevMods)))
    [void]$st.AppendLine('--- saves ---')
    [void]$st.AppendLine((Adb-Capture @('shell','ls','-la',"$($script:DevRoot)/saves")))
    [void]$st.AppendLine('--- ru_profiles ---')
    [void]$st.AppendLine((Adb-Capture @('shell','ls','-laR',$script:DevProfiles)))
    $st.ToString() | Out-File -LiteralPath (Join-Path $dir 'files_on_device.txt') -Encoding utf8
    Good 'Состояние файлов на устройстве снято'

    $tomb = Adb-Capture @('shell','ls','-la','/data/tombstones/')
    $tomb | Out-File -LiteralPath (Join-Path $dir 'tombstones_list.txt') -Encoding utf8
    if($tomb -match 'tombstone'){
        New-Item -ItemType Directory -Force -Path (Join-Path $dir 'tombstones') | Out-Null
        Pull-File '/data/tombstones/' (Join-Path $dir 'tombstones') | Out-Null
        Good 'Tombstone-файлы получены'
    } else { Warn 'Tombstone недоступны без root - полагаемся на logcat.' }

    if(Test-RemotePath "$($script:DevRoot)/saves"){
        New-Item -ItemType Directory -Force -Path (Join-Path $dir 'saves') | Out-Null
        Pull-File "$($script:DevRoot)/saves" (Join-Path $dir 'saves') | Out-Null
        Good 'Текущие сохранения скопированы'
    }

    if(Test-Path -LiteralPath $script:Reports){
        Copy-Item -LiteralPath $script:Reports -Destination (Join-Path $dir 'reports') -Recurse -Force -ErrorAction SilentlyContinue
    }
    $live = Get-ChildItem -LiteralPath $script:Logs -Filter 'logcat_live_*.txt' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if($live){ Copy-Item -LiteralPath $live.FullName -Destination $dir -Force; Good "Живой logcat добавлен: $($live.Name)" }

    $zip = "$dir.zip"
    if(Test-Path -LiteralPath $zip){ Remove-Item -LiteralPath $zip -Force }
    Compress-Archive -Path (Join-Path $dir '*') -DestinationPath $zip -Force
    Write-Host ''
    Good "Готовый пакет для отправки: $zip"
    Info 'Приложите его вместе со скриншотами.'
    exit 0
}
catch {
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
