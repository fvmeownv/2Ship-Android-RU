. (Join-Path $PSScriptRoot 'lib\Common.ps1')
$sw = $null
try {
    Connect-Device | Out-Null
    $out = Join-Path $script:Logs ("logcat_live_" + $script:Stamp + ".txt")
    Invoke-AdbChecked @('logcat','-c') -AllowFailure -Quiet | Out-Null
    Step 'Живая запись logcat'
    Write-Host 'Теперь запускайте игру и играйте.' -ForegroundColor White
    Info 'Окно НЕ закрывайте. Если игра упадёт - нажмите здесь Ctrl+C,'
    Info 'затем запустите 04_COLLECT_LOGS.cmd'
    Info 'В консоль выводятся только строки игры и ошибки; в файл пишется всё.'
    Info "Файл: $out"
    Write-Host ''
    # UTF-8 without BOM, flushed line by line: survives Ctrl+C and is greppable (v5.0 wrote UTF-16)
    $sw = New-Object System.IO.StreamWriter($out, $false, (New-Object System.Text.UTF8Encoding($false)))
    $sw.AutoFlush = $true
    $n = 0
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $script:Adb -s $script:Serial logcat -b all -v threadtime 2>&1 | ForEach-Object {
        $line = [string]$_
        $sw.WriteLine($line)
        $n++
        if($line -match 'twoshipfork|2ship|SDL|libc  |DEBUG   |FATAL|SIGSEGV|SIGABRT| F '){
            Write-Host $line -ForegroundColor Yellow
        } elseif(($n % 5000) -eq 0){
            Write-Host ("       ... записано строк: {0}" -f $n) -ForegroundColor DarkGray
        }
    }
    $ErrorActionPreference = $old
    exit 0
}
catch {
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
finally { if($sw){ $sw.Dispose() } }
