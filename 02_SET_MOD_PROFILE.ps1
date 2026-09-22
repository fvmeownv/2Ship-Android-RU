param([Parameter(Position=0)][ValidateSet('none','text','graphics','both','status')][string]$ModProfile = 'status')
. (Join-Path $PSScriptRoot 'lib\Common.ps1')
try {
    $script:Adb = $null
    Connect-Device | Out-Null
    if(-not (Test-RemotePath "$($script:DevProfiles)/text/$($script:TxtName)")){
        Fail "На устройстве нет $($script:TxtName). Запустите 07_UPDATE_APP.cmd (или 01_INSTALL.cmd)"
    }
    if(($ModProfile -eq 'graphics' -or $ModProfile -eq 'both') -and -not (Test-RemotePath "$($script:DevProfiles)/graphics/$($script:GfxName)")){
        Fail "На устройстве нет $($script:GfxName). Запустите 06_UPDATE_OVERLAY.cmd"
    }
    if($ModProfile -eq 'status'){
        Step 'Текущий состав mods'
        Write-Host (Adb-Capture @('shell','ls','-la',$script:DevMods))
        Write-Host ''
        Info 'Использование: 02_SET_MOD_PROFILE.cmd none | text | graphics | both'
        exit 0
    }
    Step "Переключение профиля: $ModProfile"
    Invoke-AdbChecked @('shell','am','force-stop',$script:Package) -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','rm','-rf',$script:DevMods) -AllowFailure -Quiet | Out-Null
    Invoke-AdbChecked @('shell','mkdir','-p',$script:DevMods) | Out-Null
    if($ModProfile -eq 'text' -or $ModProfile -eq 'both'){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/text/$($script:TxtName)","$($script:DevMods)/") | Out-Null }
    if($ModProfile -eq 'graphics' -or $ModProfile -eq 'both'){
        Invoke-AdbChecked @('shell','cp',"$($script:DevProfiles)/graphics/$($script:GfxName)","$($script:DevMods)/") | Out-Null }
    Write-Host (Adb-Capture @('shell','ls','-la',$script:DevMods))
    Good "Профиль $ModProfile применён."
    Info 'Профиль влияет только на моды. Сам APK и mm.o2r не меняются.'
    Invoke-AdbChecked @('shell','monkey','-p',$script:Package,'-c','android.intent.category.LAUNCHER','1') -Quiet | Out-Null
    exit 0
}
catch {
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
