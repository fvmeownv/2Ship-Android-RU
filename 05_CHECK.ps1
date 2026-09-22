. (Join-Path $PSScriptRoot 'lib\Common.ps1')
try {
    Connect-Device | Out-Null
    Step 'Проверка установки'
    $ok = $true
    $inst = Adb-Capture @('shell','pm','path',$script:Package)
    if($inst -match 'package:'){ Good "Приложение установлено: $inst" } else { Warn 'Приложение НЕ установлено'; $ok = $false }
    $sz = Remote-Size "$($script:DevRoot)/mm.o2r"
    if($sz -gt 1048576){ Good "mm.o2r: $sz байт" } else { Warn "mm.o2r отсутствует или мал: $sz"; $ok = $false }
    Write-Host ''
    Write-Host 'mods:' -ForegroundColor White
    Write-Host (Adb-Capture @('shell','ls','-la',$script:DevMods))
    Write-Host 'ru_profiles:' -ForegroundColor White
    Write-Host (Adb-Capture @('shell','ls','-laR',$script:DevProfiles))
    Write-Host 'saves:' -ForegroundColor White
    Write-Host (Adb-Capture @('shell','ls','-la',"$($script:DevRoot)/saves"))
    Write-Host ''
    if($ok){ Good 'Базовая проверка пройдена.' } else { Warn 'Есть замечания - см. выше.' }
    exit 0
}
catch {
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
