# Liquid Player media bridge: reads the Windows media session (SMTC) of Spotify and
# prints one JSON line per update. Commands are read line by line from stdin.
# Exits when stdin is closed (i.e. when Minecraft quits).
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]

$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream]))

function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    if (-not $task.Wait(3000)) { return $null }
    $task.Result
}

$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) `
    ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

# Live audio level of Spotify's audio session (Windows audio meter), for the reactive wave.
$meterCode = @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace LiquidPlayer {
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int EnumAudioEndpoints();
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }
    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionManager2 {
        int GetAudioSessionControl();
        int GetSimpleAudioVolume();
        [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
    }
    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionEnumerator {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetSession(int index, [MarshalAs(UnmanagedType.IUnknown)] out object session);
    }
    [ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionControl2 {
        int GetState(); int GetDisplayName(); int SetDisplayName(); int GetIconPath(); int SetIconPath();
        int GetGroupingParam(); int SetGroupingParam(); int RegisterNotification(); int UnregisterNotification();
        int GetSessionIdentifier(); int GetSessionInstanceIdentifier();
        [PreserveSig] int GetProcessId(out uint pid);
    }
    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioMeterInformation {
        [PreserveSig] int GetPeakValue(out float peak);
    }
    [ComImport, Guid("87CE5498-68D6-44E5-9215-6F2F8D5C3E4E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface ISimpleAudioVolume {
        [PreserveSig] int SetMasterVolume(float level, ref Guid context);
        [PreserveSig] int GetMasterVolume(out float level);
        [PreserveSig] int SetMute(bool mute, ref Guid context);
        [PreserveSig] int GetMute(out bool mute);
    }
    [ComImport, Guid("1C158861-B533-4B30-B1CF-E853E51C59B8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IChannelAudioVolume {
        [PreserveSig] int GetChannelCount(out uint count);
        [PreserveSig] int SetChannelVolume(uint index, float level, ref Guid context);
        [PreserveSig] int GetChannelVolume(uint index, out float level);
    }
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    /// Live output level of Spotify's audio session(s), 0..1; -1 if Spotify isn't playing audio.
    public static class Meter {
        static IAudioMeterInformation[] meters = new IAudioMeterInformation[0];
        static DateTime lastScan = DateTime.MinValue;

        static void Scan() {
            lastScan = DateTime.UtcNow;
            var found = new System.Collections.Generic.List<IAudioMeterInformation>();
            try { ScanInto(found, null); } catch { }
            meters = found.ToArray();
        }

        /// Runs on a separate MTA thread: the session's volume interface can't be reached from
        /// PowerShell's STA thread (E_NOINTERFACE).
        static float VolumeOp(float set) {
            float result = -1f;
            var t = new System.Threading.Thread(() => {
                try {
                    var sessions = new System.Collections.Generic.List<object>();
                    ScanInto(new System.Collections.Generic.List<IAudioMeterInformation>(), sessions);
                    Guid ctx = Guid.Empty;
                    foreach (var s in sessions) {
                        // The mixer's volume if available; some systems only offer per-channel volume.
                        var v = s as ISimpleAudioVolume;
                        if (v != null) {
                            if (set >= 0) { v.SetMasterVolume(set, ref ctx); v.SetMute(false, ref ctx); }
                            float level; bool mute;
                            if (v.GetMasterVolume(out level) == 0) result = (v.GetMute(out mute) == 0 && mute) ? 0f : level;
                            continue;
                        }
                        var c = s as IChannelAudioVolume;
                        uint n;
                        if (c == null || c.GetChannelCount(out n) != 0) continue;
                        float max = 0f;
                        for (uint i = 0; i < n; i++) {
                            if (set >= 0) c.SetChannelVolume(i, set, ref ctx);
                            float l;
                            if (c.GetChannelVolume(i, out l) == 0 && l > max) max = l;
                        }
                        result = max;
                    }
                } catch { }
            });
            t.SetApartmentState(System.Threading.ApartmentState.MTA);
            t.Start();
            t.Join(2000);
            return result;
        }

        /// Spotify's volume in the Windows volume mixer, 0..1; -1 if Spotify has no audio session.
        public static float SpotifyVolume() { return VolumeOp(-1f); }

        public static void SetSpotifyVolume(float level) { VolumeOp(Math.Max(0f, Math.Min(1f, level))); }

        static void ScanInto(System.Collections.Generic.List<IAudioMeterInformation> found, System.Collections.Generic.List<object> vols) {
                var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumerator();
                IMMDevice device;
                if (enumerator.GetDefaultAudioEndpoint(0, 1, out device) != 0) return;
                Guid iid = typeof(IAudioSessionManager2).GUID;
                object o;
                if (device.Activate(ref iid, 23, IntPtr.Zero, out o) != 0) return;
                IAudioSessionEnumerator sessions;
                ((IAudioSessionManager2) o).GetSessionEnumerator(out sessions);
                int count;
                sessions.GetCount(out count);
                for (int i = 0; i < count; i++) {
                    object s;
                    if (sessions.GetSession(i, out s) != 0) continue;
                    uint pid;
                    ((IAudioSessionControl2) s).GetProcessId(out pid);
                    string name = "";
                    try { name = Process.GetProcessById((int) pid).ProcessName; } catch { }
                    if (name.Equals("Spotify", StringComparison.OrdinalIgnoreCase)) {
                        found.Add((IAudioMeterInformation) s);
                        if (vols != null) vols.Add(s);
                    }
                }
        }

        public static float SpotifyPeak() {
            if (meters.Length == 0 && (DateTime.UtcNow - lastScan).TotalSeconds > 2) Scan();
            if ((DateTime.UtcNow - lastScan).TotalSeconds > 10) Scan();
            if (meters.Length == 0) return -1f;
            float max = 0f;
            foreach (var m in meters) {
                float p;
                try { if (m.GetPeakValue(out p) == 0 && p > max) max = p; } catch { lastScan = DateTime.MinValue; meters = new IAudioMeterInformation[0]; return -1f; }
            }
            return max;
        }
    }
}
'@
try { Add-Type -TypeDefinition $meterCode -Language CSharp; $hasMeter = $true } catch { $hasMeter = $false }

$thumbDir = Join-Path $env:TEMP 'liquidplayer'
New-Item -ItemType Directory -Force $thumbDir | Out-Null

$lastKey = $null
$thumbPath = ''
$lastLine = ''
# Console.In's ReadLineAsync is secretly synchronous; a StreamReader on the raw stream is not.
$reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
$pending = $reader.ReadLineAsync()

# Window helpers: open Spotify links in the background and give the focus back to Minecraft.
$winCode = @"
using System;
using System.Runtime.InteropServices;
public static class LPWin {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
"@
try { Add-Type -TypeDefinition $winCode -Language CSharp; $hasWin = $true } catch { $hasWin = $false }

function Get-SpotifyWindow {
    $p = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1
    if ($p) { return $p.MainWindowHandle }
    return [IntPtr]::Zero
}

# Opens a spotify: link (Spotify starts the song right away) without leaving Spotify in front:
# Spotify grabs the focus when it handles the link, so it is put back where it was afterwards.
function Open-Background($uri) {
    if (-not $hasWin) { Start-Process $uri; return }
    $fg = [LPWin]::GetForegroundWindow()
    $sw = Get-SpotifyWindow
    $wasHidden = ($sw -eq [IntPtr]::Zero) -or [LPWin]::IsIconic($sw)
    # Spotify only starts a linked track by itself while nothing is playing: pause first.
    if ($uri.StartsWith('spotify:track:')) {
        $s = Get-Session
        if ($null -ne $s) {
            try { $null = Await ($s.TryPauseAsync()) ([bool]) } catch { }
            Start-Sleep -Milliseconds 500
        }
    }
    Start-Process $uri
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Milliseconds 80
        if ([LPWin]::GetForegroundWindow() -ne $fg) { break }
    }
    Start-Sleep -Milliseconds 250
    $sw = Get-SpotifyWindow
    if ($sw -ne [IntPtr]::Zero) {
        # 7 = SW_SHOWMINNOACTIVE; HWND_BOTTOM with NOSIZE|NOMOVE|NOACTIVATE
        if ($wasHidden) { $null = [LPWin]::ShowWindow($sw, 7) }
        else { $null = [LPWin]::SetWindowPos($sw, [IntPtr]1, 0, 0, 0, 0, 0x13) }
    }
    if ($fg -ne [IntPtr]::Zero) {
        # A tap on Alt lets a background process hand the focus back.
        [LPWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
        [LPWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
        $null = [LPWin]::SetForegroundWindow($fg)
    }
}
# Searches in the Spotify app and presses play on the best matching result (via UI Automation,
# the Windows accessibility interface), then puts Spotify back behind Minecraft.
try { Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes; $hasUia = $true } catch { $hasUia = $false }

function Restore-Focus($fg, $wasHidden) {
    $sw = Get-SpotifyWindow
    if ($sw -ne [IntPtr]::Zero) {
        if ($wasHidden) { $null = [LPWin]::ShowWindow($sw, 7) }
        else { $null = [LPWin]::SetWindowPos($sw, [IntPtr]1, 0, 0, 0, 0, 0x13) }
    }
    if ($fg -ne [IntPtr]::Zero) {
        [LPWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
        [LPWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
        $null = [LPWin]::SetForegroundWindow($fg)
    }
}

$playWord = '^(Play |Abspielen: )|( wiedergeben| abspielen)$'

# Versions that only borrow the title (karaoke, covers, ...) are never the song we want.
$coverWords = 'karaoke|made popular|cover|tribute|instrumental|backing version|vocal version|in the style of|originally performed'

# Presses the play button in the list row of this song on the current Spotify page.
# In search results the row must say it is a "Song" (not an album, playlist or podcast episode)
# and name the artist outside the title. Exact titles win over longer ones ("- Live", ...).
function Click-Play($title, $artist, $resultsOnly) {
    $sw = Get-SpotifyWindow
    if ($sw -eq [IntPtr]::Zero) { return $false }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($sw)
    $gridType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::DataGrid)
    $rowType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::DataItem)
    $want = $title.ToLower().Trim()
    $by = $artist.ToLower().Trim()
    if ($by.Contains(', ')) { $by = $by.Substring(0, $by.IndexOf(', ')) }
    $skipCovers = $want -notmatch $coverWords
    $best = $null; $bestScore = 0
    foreach ($grid in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $gridType)) {
        $gname = $grid.Current.Name
        if ($resultsOnly -and $gname -notmatch 'Search results|Suchergebnisse') { continue }
        if (-not $resultsOnly -and $gname -match 'Bibliothek|Library|Als N|Next|Aktueller') { continue }
        foreach ($row in $grid.FindAll([System.Windows.Automation.TreeScope]::Children, $rowType)) {
            $rowName = $row.Current.Name
            $name = ($rowName -replace $playWord, '').Replace('"', '').Trim().ToLower()
            if (-not $name.StartsWith($want)) { continue }
            if ($skipCovers -and $name -match $coverWords) { continue }
            $play = $null; $meta = @()
            foreach ($el in $row.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
                $n = $el.Current.Name
                if (-not $n) { continue }
                if ($el.Current.ControlType -eq [System.Windows.Automation.ControlType]::Button -and $n -match $playWord) {
                    if ($play -eq $null) { $play = $el }
                    continue
                }
                if ($n -ne $rowName) { $meta += $n }
            }
            if ($play -eq $null) { continue }
            $metaText = ($meta -join ' | ').ToLower()
            $artistOk = (-not $by) -or $metaText.Contains($by) -or ($name -match (' von | by ') -and $name.Substring($want.Length).Contains($by))
            if ($resultsOnly) {
                if ($metaText -notmatch '(^|[^a-z])song([^a-z]|$)') { continue }
                if (-not $artistOk) { continue }
            }
            $score = 1
            if ($name -eq $want -or $name.StartsWith($want + ' von ') -or $name.StartsWith($want + ' by ')) { $score += 3 }
            if ($artistOk) { $score += 2 }
            if ($score -gt $bestScore) { $best = $play; $bestScore = $score }
        }
    }
    if ($best -eq $null) { return $false }
    $best.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    return $true
}
# Opens the song shown as the search "top result" (clicks its title link).
function Open-TopResult($title, $artist) {
    $sw = Get-SpotifyWindow
    if ($sw -eq [IntPtr]::Zero) { return $false }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($sw)
    $gridType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::DataGrid)
    $rowType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::DataItem)
    $linkType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Hyperlink)
    $want = $title.ToLower().Trim()
    foreach ($grid in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $gridType)) {
        if ($grid.Current.Name -notmatch 'Search results|Suchergebnisse') { continue }
        foreach ($row in $grid.FindAll([System.Windows.Automation.TreeScope]::Children, $rowType)) {
            if (-not $row.Current.Name.ToLower().StartsWith($want)) { continue }
            $all = (($row.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { $_.Current.Name }) -join ' | ').ToLower()
            if ($all -notmatch '(^|[^a-z])song([^a-z]|$)') { continue }
            if ($row.Current.Name.ToLower() -match $coverWords -and $want -notmatch $coverWords) { continue }
            $link = $row.FindAll([System.Windows.Automation.TreeScope]::Descendants, $linkType) | Where-Object { $_.Current.Name.ToLower().StartsWith($want) } | Select-Object -First 1
            if ($link) {
                $link.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
                return $true
            }
        }
    }
    return $false
}
function Search-And-Play($query, $title, $artist) {
    # Commas, colons and quotes break Spotify's search links ("Tyler, The Creator").
    $query = ($query -replace '[,:;"]', ' ') -replace '\s+', ' '
    if (-not $hasWin -or -not $hasUia) { Start-Process ('spotify:search:' + [uri]::EscapeDataString($query)); return $false }
    $fg = [LPWin]::GetForegroundWindow()
    $sw = Get-SpotifyWindow
    $wasHidden = ($sw -eq [IntPtr]::Zero) -or [LPWin]::IsIconic($sw)
    if ($sw -ne [IntPtr]::Zero) {
        # A minimized Spotify doesn't update its page: show it without taking the focus.
        if ([LPWin]::IsIconic($sw)) { $null = [LPWin]::ShowWindow($sw, 4); Start-Sleep -Milliseconds 700 }
        # Spotify ignores a new search link while it already shows a search: go "Home" first.
        $btnType = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
        for ($try = 0; $try -lt 6; $try++) {
            try {
                $root = [System.Windows.Automation.AutomationElement]::FromHandle($sw)
                $homeBtn = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $btnType) | Where-Object { $_.Current.Name -in @('Start', 'Home') } | Select-Object -First 1
                if ($homeBtn) {
                    $homeBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
                    Start-Sleep -Milliseconds 900
                    break
                }
            } catch { }
            Start-Sleep -Milliseconds 350
        }
    }    Start-Process ('spotify:search:' + [uri]::EscapeDataString($query))
    $played = $false
    $restored = $false
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $openedSong = $false
    while (-not $played -and $timer.ElapsedMilliseconds -lt 12000) {
        Start-Sleep -Milliseconds 400
        # A minimized Spotify doesn't update its page: show it without taking the focus.
        $w = Get-SpotifyWindow
        if (-not $restored -and $w -ne [IntPtr]::Zero -and [LPWin]::IsIconic($w)) { $null = [LPWin]::ShowWindow($w, 4); $restored = $true }
        try { $played = Click-Play $title $artist (-not $openedSong) } catch { }
        # Some songs only appear as the "top result" card, whose play button shows on hover only.
        # Then open the song (its album page) and press the play button in the song's row.
        if (-not $played -and -not $openedSong -and $timer.ElapsedMilliseconds -gt 3000) {
            try { $openedSong = Open-TopResult $title $artist } catch { }
            if ($openedSong) { Start-Sleep -Milliseconds 1200 }
        }
    }    Start-Sleep -Milliseconds 200
    Restore-Focus $fg $wasHidden
    return $played
}
function Get-Session {
    $sessions = $manager.GetSessions()
    foreach ($s in $sessions) { if ($s.SourceAppUserModelId -match 'Spotify') { return $s } }
    return $null
}

function Save-Thumb($props, $key) {
    try {
        if ($null -eq $props.Thumbnail) { return '' }
        $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        if ($null -eq $stream) { return '' }
        $hash = [System.BitConverter]::ToString(
            [System.Security.Cryptography.MD5]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($key))).Replace('-', '').Substring(0, 16)
        $path = Join-Path $thumbDir "cover-$hash.img"
        # Called via reflection: PowerShell can't bind the WinRT COM object to the overload directly.
        $net = $asStreamForRead.Invoke($null, @($stream))
        $file = [System.IO.File]::Create($path)
        try { $net.CopyTo($file) } finally { $file.Dispose(); $net.Dispose() }
        # Keep the folder small.
        Get-ChildItem $thumbDir -Filter 'cover-*.img' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 20 | Remove-Item -Force
        return $path
    } catch { return '' }
}

$pass = 0
while ($true) {
    $pass++
    # Handle commands from Minecraft.
    while ($pending.IsCompleted) {
        $cmd = $pending.Result
        if ($null -eq $cmd) { exit 0 }
        $pending = $reader.ReadLineAsync()
        if ($cmd.StartsWith('searchplay ')) {
            # searchplay <url-encoded query> <url-encoded title>
            $args2 = $cmd.Substring(11).Trim().Split(' ')
            if ($args2.Count -ge 2) {
                $ar = if ($args2.Count -ge 3) { [uri]::UnescapeDataString($args2[2]) } else { '' }
                try { $null = Search-And-Play ([uri]::UnescapeDataString($args2[0])) ([uri]::UnescapeDataString($args2[1])) $ar } catch { }
            }
            $lastLine = ''
            continue
        }
        if ($cmd.StartsWith('open ')) {
            $uri = $cmd.Substring(5).Trim()
            if ($uri -match '^spotify:(track|album|playlist):[A-Za-z0-9]{22}$' -or $uri -match '^spotify:search:[A-Za-z0-9%._~-]+$') { try { Open-Background $uri } catch { } }
            $lastLine = ''
            continue
        }
        $s = Get-Session
        if ($null -eq $s) { continue }
        try {
            $parts = $cmd.Trim().Split(' ')
            switch ($parts[0]) {
                'toggle' { $null = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
                'play' { $null = Await ($s.TryPlayAsync()) ([bool]) }
                'pause' { $null = Await ($s.TryPauseAsync()) ([bool]) }
                'next' { $null = Await ($s.TrySkipNextAsync()) ([bool]) }
                'prev' { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) }
                'volume' {
                    if ($hasMeter) {
                        [LiquidPlayer.Meter]::SetSpotifyVolume([float]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture))
                        $vol = $null
                    }
                }
                'seek' { $null = Await ($s.TryChangePlaybackPositionAsync([long]$parts[1] * 10000)) ([bool]) }
                'shuffle' { $null = Await ($s.TryChangeShuffleActiveAsync([bool]::Parse($parts[1]))) ([bool]) }
                'repeat' {
                    $mode = [Windows.Media.MediaPlaybackAutoRepeatMode]$parts[1]
                    $null = Await ($s.TryChangeAutoRepeatModeAsync($mode)) ([bool])
                }
            }
        } catch { }
        $lastLine = ''
    }

    if ($hasMeter) {
        $peak = [LiquidPlayer.Meter]::SpotifyPeak()
        # The volume needs its own thread to read, so only every 8th pass (~0.4 s).
        if (($pass % 8) -eq 1 -or $null -eq $vol) { $vol = [LiquidPlayer.Meter]::SpotifyVolume() }
        $ic = [Globalization.CultureInfo]::InvariantCulture
        [Console]::Out.WriteLine('{"peak":' + $peak.ToString('0.000', $ic) + ',"vol":' + $vol.ToString('0.000', $ic) + '}')
        [Console]::Out.Flush()
    }
    if (($pass % 8) -ne 0 -and $lastLine -ne '') { Start-Sleep -Milliseconds 50; continue }

    $out = @{ ok = $false }
    try {
        $s = Get-Session
        if ($null -ne $s) {
            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $info = $s.GetPlaybackInfo()
            $tl = $s.GetTimelineProperties()
            $playing = $info.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
            $dur = [long]$tl.EndTime.TotalMilliseconds
            $pos = [long]$tl.Position.TotalMilliseconds
            if ($playing -and $tl.LastUpdatedTime.Year -gt 2000) {
                $pos += [long]([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
            }
            if ($dur -gt 0) { $pos = [Math]::Max(0, [Math]::Min($pos, $dur)) }
            $key = "$($props.Title)|$($props.Artist)|$($props.AlbumTitle)"
            if ($key -ne $lastKey) { $thumbPath = Save-Thumb $props $key; $lastKey = $key }
            $repeat = 'off'
            if ($null -ne $info.AutoRepeatMode) { $repeat = "$($info.AutoRepeatMode)".ToLower() }
            $out = @{
                ok      = $true
                title   = "$($props.Title)"
                artist  = "$($props.Artist)"
                album   = "$($props.AlbumTitle)"
                playing = [bool]$playing
                pos     = $pos
                dur     = $dur
                shuffle = [bool]$info.IsShuffleActive
                repeat  = $repeat
                thumb   = "$thumbPath"
            }
        }
    } catch { $out = @{ ok = $false; error = "$_" } }

    $line = $out | ConvertTo-Json -Compress
    # Always emit while playing (position moves); otherwise only on change.
    if ($line -ne $lastLine -or $out.playing) {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
        $lastLine = $line
    }
    Start-Sleep -Milliseconds 50
}
