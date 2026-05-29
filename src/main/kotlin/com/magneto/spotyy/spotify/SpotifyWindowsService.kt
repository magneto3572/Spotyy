package com.magneto.spotyy.spotify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SpotifyWindowsService : SpotifyService {

    private val logger = Logger.getInstance(SpotifyWindowsService::class.java)

    // Kept in sync by getCurrentTrack() so getVolume()/increaseVolume()/decreaseVolume()
    // can operate without an extra PowerShell round-trip.
    @Volatile private var cachedVolume = 50

    override fun getCurrentTrack(): SpotifyState {
        val result = runPowerShell(GET_TRACK_SCRIPT)
            ?: return SpotifyState(false, false, null, cachedVolume)

        val parts = result.split("|", limit = 6)
        if (parts.size < 4) return SpotifyState(false, false, null, cachedVolume)

        val isRunning  = parts[0] == "1"
        val isPlaying  = parts[1] == "playing"
        val volume     = parts.getOrNull(2)?.toIntOrNull() ?: cachedVolume
        val trackInfo  = parts[3].trim().takeIf { it.isNotBlank() }
        val positionMs = parts.getOrNull(4)?.toDoubleOrNull()?.toLong() ?: 0L
        val durationMs = parts.getOrNull(5)?.toDoubleOrNull()?.toLong() ?: 0L

        cachedVolume = volume
        return SpotifyState(isRunning, isPlaying, trackInfo, volume, positionMs, durationMs)
    }

    override fun playPause()     { runPowerShellAsync(buildControlScript("TryTogglePlayPauseAsync")) }
    override fun nextTrack()     { runPowerShellAsync(buildControlScript("TrySkipNextAsync")) }
    override fun previousTrack() { runPowerShellAsync(buildControlScript("TrySkipPreviousAsync")) }

    override fun getVolume() = cachedVolume

    override fun setVolume(volume: Int) {
        val safe = volume.coerceIn(0, 100)
        cachedVolume = safe
        ApplicationManager.getApplication().executeOnPooledThread {
            runPowerShell(buildSetVolumeScript(safe))
        }
    }

    override fun increaseVolume(amount: Int) { setVolume(cachedVolume + amount) }
    override fun decreaseVolume(amount: Int) { setVolume(cachedVolume - amount) }

    private fun runPowerShellAsync(script: String) {
        ApplicationManager.getApplication().executeOnPooledThread { runPowerShell(script) }
    }

    private fun runPowerShell(script: String): String? {
        val future = CompletableFuture<String?>()
        val thread = Thread {
            try {
                val process = ProcessBuilder(
                    "powershell.exe", "-NonInteractive", "-NoProfile", "-Command", script
                ).redirectErrorStream(false).start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error  = process.errorStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(5, TimeUnit.SECONDS)

                if (!exited) {
                    process.destroy()
                    logger.warn("PowerShell script timed out")
                    future.complete(null)
                    return@Thread
                }
                if (error.isNotBlank()) logger.warn("PowerShell stderr: $error")
                future.complete(output.trim().takeIf { it.isNotBlank() })
            } catch (e: Exception) {
                logger.warn("PowerShell execution failed", e)
                future.completeExceptionally(e)
            }
        }
        thread.isDaemon = true
        thread.start()
        return try {
            future.get(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("PowerShell timed out or interrupted", e)
            thread.interrupt()
            null
        }
    }

    companion object {
        // C# stub definitions for the Windows Core Audio (WASAPI) COM interfaces.
        // Embedded as a PowerShell single-quote here-string (@'...'@) so PowerShell treats
        // the content as a verbatim literal — no variable expansion, no Kotlin $ escaping needed.
        // Add-Type compiles this inline; no external DLLs or admin rights are required.
        //
        // Vtable order must match the real COM interfaces exactly:
        //   IMMDeviceEnumerator: EnumAudioEndpoints, GetDefaultAudioEndpoint, ...
        //   IMMDevice:           Activate, OpenPropertyStore, ...
        //   IAudioEndpointVolume: RegisterControlChangeNotify, Unregister..., GetChannelCount,
        //                         SetMasterVolumeLevel, SetMasterVolumeLevelScalar,
        //                         GetMasterVolumeLevel, GetMasterVolumeLevelScalar, ...
        private val AUDIO_TYPE_DEF = """
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumerator {}

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    void EnumAudioEndpoints(uint dataFlow, uint dwStateMask, IntPtr ppDevices);
    void GetDefaultAudioEndpoint(uint dataFlow, uint role, out IMMDevice ppEndpoint);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    void Activate(ref Guid iid, uint dwClsCtx, IntPtr pActivationParams,
        [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
}

[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume {
    void RegisterControlChangeNotify(IntPtr pNotify);
    void UnregisterControlChangeNotify(IntPtr pNotify);
    void GetChannelCount(out uint pnChannelCount);
    void SetMasterVolumeLevel(float fLevelDB, ref Guid pguidEventContext);
    void SetMasterVolumeLevelScalar(float fLevel, ref Guid pguidEventContext);
    void GetMasterVolumeLevel(out float pfLevelDB);
    void GetMasterVolumeLevelScalar(out float pfLevel);
}

public static class AudioManager {
    static readonly Guid AEV_IID = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");

    static IAudioEndpointVolume GetAEV() {
        var en = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice device;
        en.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out device);
        var iid = AEV_IID;
        object aevObj;
        device.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out aevObj);
        return (IAudioEndpointVolume)aevObj;
    }

    public static int GetVolume() {
        float level;
        GetAEV().GetMasterVolumeLevelScalar(out level);
        return (int)(level * 100);
    }

    public static void SetVolume(int percent) {
        var aev = GetAEV();
        float level = Math.Max(0f, Math.Min(1f, percent / 100f));
        var ctx = Guid.Empty;
        aev.SetMasterVolumeLevelScalar(level, ref ctx);
    }
}
'@
""".trimIndent()

        // Loads the WinRT assembly and defines an Await helper that bridges
        // IAsyncOperation<T> to a blocking .NET Task (mirrors AppleScript via osascript on Mac).
        // After this block, $session is a Spotify SMTC session or $null.
        private val SMTC_SETUP = """
Add-Type -AssemblyName System.Runtime.WindowsRuntime
${'$'}asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    ${'$'}_.Name -eq 'AsTask' -and
    ${'$'}_.GetParameters().Count -eq 1 -and
    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await(${'$'}Op, ${'$'}T) {
    ${'$'}task = ${'$'}asTaskGeneric.MakeGenericMethod(${'$'}T).Invoke(${'$'}null, @(${'$'}Op))
    ${'$'}task.Wait(-1) | Out-Null
    ${'$'}task.Result
}
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
${'$'}mgr     = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
${'$'}session = ${'$'}mgr.GetCurrentSession()
if (${'$'}session -and ${'$'}session.SourceAppUserModelId -and ${'$'}session.SourceAppUserModelId -notlike '*spotify*') { ${'$'}session = ${'$'}null }
""".trimIndent()

        // Single PowerShell call that returns both the system volume (WASAPI) and
        // Spotify playback state (SMTC) as a pipe-delimited string:
        //   isRunning|status|volume|trackInfo|positionMs|durationMs
        private val GET_TRACK_SCRIPT = """
$AUDIO_TYPE_DEF
${'$'}vol = try { [AudioManager]::GetVolume() } catch { 50 }
$SMTC_SETUP
if (-not ${'$'}session) {
    ${'$'}running = (Get-Process -Name Spotify -ErrorAction SilentlyContinue) -ne ${'$'}null
    if (${'$'}running) { Write-Output "1|paused|${'$'}vol||0|0" } else { Write-Output "0|stopped|${'$'}vol||0|0" }
    exit
}
${'$'}props    = Await (${'$'}session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsMediaProperties])
${'$'}timeline = ${'$'}session.GetTimelineProperties()
${'$'}playback = ${'$'}session.GetPlaybackInfo()
${'$'}playing  = ${'$'}playback.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
${'$'}posMs    = [long]${'$'}timeline.Position.TotalMilliseconds
${'$'}endMs    = [long]${'$'}timeline.EndTime.TotalMilliseconds
${'$'}status   = if (${'$'}playing) { 'playing' } else { 'paused' }
${'$'}artist   = ${'$'}props.Artist
${'$'}title    = ${'$'}props.Title
${'$'}track    = if (${'$'}artist) { "${'$'}artist - ${'$'}title" } else { ${'$'}title }
Write-Output "1|${'$'}status|${'$'}vol|${'$'}track|${'$'}posMs|${'$'}endMs"
""".trimIndent()

        private fun buildSetVolumeScript(volume: Int) = """
$AUDIO_TYPE_DEF
[AudioManager]::SetVolume($volume)
""".trimIndent()

        private fun buildControlScript(method: String) = """
$SMTC_SETUP
if (${'$'}session) { Await (${'$'}session.$method()) ([bool]) | Out-Null }
""".trimIndent()
    }
}
