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
    @Volatile private var volumePollTick = 0
    @Volatile private var lastKnownRunning = false

    override fun getCurrentTrack(): SpotifyState {
        // Refresh system volume every ~30 s (every 10th 3-second poll).
        // Done separately so Add-Type C# compilation never blocks the metadata fetch.
        if (volumePollTick++ % 10 == 0) {
            ApplicationManager.getApplication().executeOnPooledThread {
                runPowerShell(GET_VOLUME_SCRIPT)?.toIntOrNull()?.let { cachedVolume = it }
            }
        }

        val result = runPowerShell(GET_TRACK_SCRIPT)
            ?: return SpotifyState(false, false, null, cachedVolume)

        val parts = result.split("|", limit = 5)
        if (parts.size < 3) return SpotifyState(false, false, null, cachedVolume)

        val isRunning  = parts[0] == "1"
        val isPlaying  = parts[1] == "playing"
        val trackInfo  = parts[2].trim().takeIf { it.isNotBlank() }
        val positionMs = parts.getOrNull(3)?.toDoubleOrNull()?.toLong() ?: 0L
        val durationMs = parts.getOrNull(4)?.toDoubleOrNull()?.toLong() ?: 0L

        lastKnownRunning = isRunning
        return SpotifyState(isRunning, isPlaying, trackInfo, cachedVolume, positionMs, durationMs)
    }

    override fun playPause() {
        if (lastKnownRunning) {
            // Fast path: Spotify was running at last poll
            runPowerShellAsync(buildControlScript("TryTogglePlayPauseAsync"))
            return
        }
        // lastKnownRunning is false (either Spotify is down, or metadata polling hasn't
        // succeeded yet). Do a quick process check to decide: toggle vs launch.
        ApplicationManager.getApplication().executeOnPooledThread {
            val out = runPowerShell(
                "(Get-Process -Name Spotify -ErrorAction SilentlyContinue) -ne \$null"
            )
            val running = out?.trim()?.equals("True", ignoreCase = true) == true
            if (running) {
                runPowerShell(buildControlScript("TryTogglePlayPauseAsync"))
                lastKnownRunning = true
            } else {
                launchSpotifyAndPlay()
            }
        }
    }

    override fun nextTrack()     { runPowerShellAsync(buildControlScript("TrySkipNextAsync")) }
    override fun previousTrack() { runPowerShellAsync(buildControlScript("TrySkipPreviousAsync")) }

    private fun launchSpotifyAndPlay() {
        try {
            // spotify: URI works for both direct-installer and MS Store builds
            ProcessBuilder("cmd.exe", "/c", "start", "", "spotify:")
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            logger.warn("Failed to launch Spotify", e)
            return
        }

        // Poll every 1 s for up to 20 s for the Spotify process to appear
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val out = runPowerShell(
                "(Get-Process -Name Spotify -ErrorAction SilentlyContinue) -ne \$null"
            )
            if (out?.trim().equals("True", ignoreCase = true)) {
                // Give SMTC 2 s to register the session before sending the play command
                Thread.sleep(2000)
                runPowerShell(buildControlScript("TryTogglePlayPauseAsync"))
                lastKnownRunning = true
                return
            }
        }
        logger.warn("Spotify did not start within 20 s")
    }

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
                    // -MTA forces MTA apartment mode. powershell.exe defaults to STA, where
                    // Task.Wait(-1) deadlocks when a WinRT completion callback tries to marshal
                    // back onto the blocked STA thread. MTA lets completions fire on any
                    // thread-pool thread, so the Await helper works correctly.
                    "powershell.exe", "-MTA", "-NonInteractive", "-NoProfile", "-Command", script
                ).redirectErrorStream(false).start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error  = process.errorStream.bufferedReader().use { it.readText() }
                // WinRT assembly load + RequestAsync() cold-start: 1.5–4 s in a fresh process.
                // 10 s gives comfortable headroom even on slow machines.
                val exited = process.waitFor(10, TimeUnit.SECONDS)

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
            future.get(13, TimeUnit.SECONDS)
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

        // SMTC-only metadata poll — no Add-Type/C# compilation so it completes well within timeout.
        // Format: isRunning|status|trackInfo|positionMs|durationMs
        // Volume is fetched separately via GET_VOLUME_SCRIPT to avoid blocking this path.
        private val GET_TRACK_SCRIPT = """
$SMTC_SETUP
if (-not ${'$'}session) {
    ${'$'}running = (Get-Process -Name Spotify -ErrorAction SilentlyContinue) -ne ${'$'}null
    if (${'$'}running) { Write-Output "1|paused||0|0" } else { Write-Output "0|stopped||0|0" }
    exit
}
try {
    ${'$'}props    = Await (${'$'}session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsMediaProperties])
    ${'$'}timeline = ${'$'}session.GetTimelineProperties()
    ${'$'}playback = ${'$'}session.GetPlaybackInfo()
    ${'$'}playing  = ${'$'}playback.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
    ${'$'}posMs    = [long]${'$'}timeline.Position.TotalMilliseconds
    ${'$'}endMs    = [long]${'$'}timeline.EndTime.TotalMilliseconds
    if (${'$'}endMs -eq 0) { ${'$'}endMs = [long]${'$'}timeline.MaxSeekTime.TotalMilliseconds }
    ${'$'}status   = if (${'$'}playing) { 'playing' } else { 'paused' }
    ${'$'}artist   = ${'$'}props.Artist
    ${'$'}title    = ${'$'}props.Title
    ${'$'}track    = if (${'$'}artist) { "${'$'}artist - ${'$'}title" } else { ${'$'}title }
    Write-Output "1|${'$'}status|${'$'}track|${'$'}posMs|${'$'}endMs"
} catch {
    Write-Output "1|paused||0|0"
}
""".trimIndent()

        // Standalone volume fetch — uses Add-Type C# compilation but runs off the hot path.
        private val GET_VOLUME_SCRIPT = """
$AUDIO_TYPE_DEF
try { Write-Output ([AudioManager]::GetVolume()) } catch { Write-Output 50 }
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
