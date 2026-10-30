# AFK Cinematic media bridge: reads the Windows media session (SMTC / "GlobalSystemMediaTransportControls"),
# i.e. the same data the volume flyout shows. Works with Spotify, YouTube in the browser, and any other
# player that reports to Windows. Prints one JSON line about every 250 ms:
#   {"ok":true,"title":"..","artist":"..","album":"..","playing":true,"pos":12345,"dur":200000,"app":".."}
# Exits as soon as stdin is closed (Minecraft quits or no longer needs us).
# ASCII only: Windows PowerShell 5.1 misreads non-ASCII characters in scripts without a BOM.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]

$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    if (-not $task.Wait(3000)) { return $null }
    $task.Result
}

$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) `
    ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

$playingStatus = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing

# Prefers a session that is actually playing; otherwise whatever Windows considers "current".
function Get-Session {
    try {
        foreach ($s in $manager.GetSessions()) {
            if ($s.GetPlaybackInfo().PlaybackStatus -eq $playingStatus) { return $s }
        }
    } catch { }
    return $manager.GetCurrentSession()
}

# Console.In's ReadLineAsync is secretly synchronous; a StreamReader on the raw stream is not.
$reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
$pending = $reader.ReadLineAsync()
$lastLine = ''

while ($true) {
    if ($pending.IsCompleted) {
        if ($null -eq $pending.Result) { exit 0 }
        $pending = $reader.ReadLineAsync()
    }

    $out = @{ ok = $false }
    try {
        $s = Get-Session
        if ($null -ne $s) {
            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $info = $s.GetPlaybackInfo()
            $tl = $s.GetTimelineProperties()
            $playing = $info.PlaybackStatus -eq $playingStatus
            $dur = [long]($tl.EndTime - $tl.StartTime).TotalMilliseconds
            $pos = [long]($tl.Position - $tl.StartTime).TotalMilliseconds
            # Many players only update the timeline on play/pause/seek: extrapolate from the last update.
            if ($playing -and $tl.LastUpdatedTime.Year -gt 2000) {
                $pos += [long]([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
            }
            if ($dur -gt 0) { $pos = [Math]::Max(0, [Math]::Min($pos, $dur)) }
            if ($null -ne $props -and "$($props.Title)" -ne '') {
                $out = @{
                    ok      = $true
                    title   = "$($props.Title)"
                    artist  = "$($props.Artist)"
                    album   = "$($props.AlbumTitle)"
                    playing = [bool]$playing
                    pos     = $pos
                    dur     = $dur
                    app     = "$($s.SourceAppUserModelId)"
                }
            }
        }
    } catch { $out = @{ ok = $false } }

    $line = $out | ConvertTo-Json -Compress
    # Always emit while playing (the position moves); otherwise only on change.
    if ($line -ne $lastLine -or $out.playing) {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
        $lastLine = $line
    }
    Start-Sleep -Milliseconds 250
}
