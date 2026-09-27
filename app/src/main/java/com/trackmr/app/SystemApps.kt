package com.trackmr.app

import android.app.ActivityManager
import android.content.Context
import com.trackmr.ui.AppCategory
import com.trackmr.ui.AppRegistry
import com.trackmr.ui.FormPanel
import com.trackmr.ui.UiAudio
import com.trackmr.ui.UiTheme
import com.trackmr.ui.WindowManager
import com.trackmr.ui.XrApp
import com.trackmr.ui.XrWindow
import com.trackmr.xr.ResolutionPreset
import com.trackmr.xr.XrBackend
import com.trackmr.xr.XrContext
import com.trackmr.xr.XrMode
import java.io.File

/** Built-in system apps: settings, diagnostics, recenter. */
object SystemApps {
    fun register(ctx: XrContext) {
        UiAudio.enabled = ctx.settings.getBool("uiSounds", true)
        UiAudio.volume = ctx.settings.getFloat("uiVolume", 0.5f)
        AppRegistry.register(XrApp("settings", "Configurações", "Tela, mãos, MR", "⚙️", UiTheme.neonBlue, AppCategory.SYSTEM) { openSettings(it) })
        AppRegistry.register(XrApp("diagnostics", "Diagnóstico", "Desempenho e sensores", "📈", UiTheme.neonMint, AppCategory.TOOLS) { openDiagnostics(it) })
        AppRegistry.register(XrApp("recenter", "Recentralizar", "Vista e janelas", "🎯", UiTheme.neonGold, AppCategory.SYSTEM) {
            it.trackingProvider?.recenter(); it.service(WindowManager::class.java)?.recenter(); it.notify("Vista recentralizada")
        })
        AppRegistry.register(XrApp("mode", "MR ⇄ VR", "Alternar realidade", "🌗", UiTheme.neonPink, AppCategory.SYSTEM) {
            it.setMode(if (it.mode == XrMode.MR) XrMode.VR else XrMode.MR)
        })
        // Show the crash report from a previous session, if any (spatial toast).
        val crash = File(ctx.activity.filesDir, "last_crash.txt")
        if (crash.exists()) {
            ctx.notify("A sessão anterior foi encerrada por um erro — veja Diagnóstico")
        }
    }

    fun openSettings(ctx: XrContext) {
        val wm = ctx.service(WindowManager::class.java) ?: return
        val s = ctx.settings
        wm.openOrFocus("settings") {
            val form = FormPanel(1.05f, 0.72f, radius = 1.6f)
            form.title = "⚙️  Configurações"
            form.tab("Exibição") {
                segmented("Modo", listOf("Realidade Mista", "Realidade Virtual"), { if (s.mode == XrMode.MR) 0 else 1 }) {
                    ctx.setMode(if (it == 0) XrMode.MR else XrMode.VR)
                }
                segmented("Resolução", ResolutionPreset.values().map { it.label }, { s.resolution.ordinal }) {
                    s.resolution = ResolutionPreset.values()[it]; form.refresh()
                }
                if (s.resolution == ResolutionPreset.CUSTOM) {
                    slider("Escala personalizada", 0.4f, 1.5f, { s.customScale }, 0.05f, { "${(it * 100).toInt()}%" }) { s.customScale = it }
                }
                toggle("Resolução dinâmica (reduz calor e latência)", { s.dynamicResolution }) { s.dynamicResolution = it }
                segmented("FPS alvo", listOf("30", "45", "60", "72", "90"), { listOf(30, 45, 60, 72, 90).indexOf(s.targetFps).coerceAtLeast(0) }) {
                    s.targetFps = listOf(30, 45, 60, 72, 90)[it]
                }
                toggle("Estéreo (VR Box / Cardboard)", { s.stereo }) { s.stereo = it }
                toggle("Correção de distorção das lentes", { s.distortion }) { s.distortion = it }
                header("Lentes")
                slider("IPD", 0.052f, 0.075f, { s.ipd }, 0.0005f, { "%.1f mm".format(it * 1000) }) { s.ipd = it }
                slider("Distância entre lentes", 0.05f, 0.075f, { s.interLens }, 0.0005f, { "%.1f mm".format(it * 1000) }) { s.interLens = it }
                slider("Tela ↔ lente", 0.03f, 0.06f, { s.screenToLens }, 0.0005f, { "%.1f mm".format(it * 1000) }) { s.screenToLens = it }
                slider("Distorção k1", 0f, 1f, { s.k1 }, 0.01f) { s.k1 = it }
                slider("Distorção k2", 0f, 1f, { s.k2 }, 0.01f) { s.k2 = it }
            }
            form.tab("Mãos") {
                toggle("Rastreamento de mãos (MediaPipe)", { s.handTracking }) { s.handTracking = it }
                toggle("Esqueleto das mãos", { s.handSkeleton }) { s.handSkeleton = it }
                toggle("Aceleração por GPU", { s.handGpu }) { s.handGpu = it; ctx.notify("Aplicado ao reiniciar o rastreamento") }
                toggle("Recorte ROI (menor latência)", { s.handRoi }) { s.handRoi = it }
                toggle("Realce em pouca luz", { s.handLowLightBoost }) { s.handLowLightBoost = it }
                segmented("Mãos máximas", listOf("1", "2"), { s.maxHands - 1 }) { s.maxHands = it + 1 }
                slider("Frequência de inferência", 15f, 60f, { s.handInferenceHz.toFloat() }, 5f, { "${it.toInt()} Hz" }) { s.handInferenceHz = it.toInt() }
                slider("Predição", 0f, 60f, { s.handPredictionMs }, 2f, { "${it.toInt()} ms" }) { s.handPredictionMs = it }
                segmented("Resolução de entrada", listOf("160", "192", "256", "320"), { listOf(160, 192, 256, 320).indexOf(s.handInputSize).coerceAtLeast(0) }) {
                    s.handInputSize = listOf(160, 192, 256, 320)[it]
                }
                info("Gestos: pinça = clique • segurar pinça = arrastar • palma virada + pinça 0,5 s = menu", 2)
            }
            form.tab("MR") {
                slider("Brilho do passthrough", 0.3f, 1.6f, { s.passthroughBrightness }, 0.05f) { s.passthroughBrightness = it }
                slider("Campo do passthrough", 0.3f, 1f, { s.passthroughFill }, 0.05f) { s.passthroughFill = it }
                toggle("Oclusão por profundidade", { s.occlusion }) { s.occlusion = it }
                toggle("ARCore Depth API", { s.depthApi }) { s.depthApi = it; ctx.notify("Aplicado na próxima sessão") }
                toggle("Mostrar planos detectados", { s.planeVisualization }) { s.planeVisualization = it }
                slider("Campo de visão (sem lentes)", 50f, 100f, { s.monoFovDeg }, 1f, { "${it.toInt()}°" }) { s.monoFovDeg = it }
            }
            form.tab("Sistema") {
                segmented("Backend XR", XrBackend.values().map { it.label }, { s.backend.ordinal }) {
                    s.backend = XrBackend.values()[it]; ctx.notify("Backend aplicado ao reiniciar")
                }
                toggle("Sons da interface", { UiAudio.enabled }) { UiAudio.enabled = it; s.put("uiSounds", it) }
                slider("Volume da interface", 0f, 1f, { UiAudio.volume }, 0.05f, { "${(it * 100).toInt()}%" }) { UiAudio.volume = it; s.put("uiVolume", it) }
                button("Recentralizar vista", "🎯") { ctx.trackingProvider?.recenter(); wm.recenter() }
                button("Fechar todas as janelas", "✖") { wm.closeAll() }
                info("TrackMR 2.0 • ${ctx.trackingProvider?.capabilities?.name ?: "sem rastreamento"}")
            }
            XrWindow(ctx, "Configurações", form, "⚙️", UiTheme.neonBlue)
        }
    }

    fun openDiagnostics(ctx: XrContext) {
        val wm = ctx.service(WindowManager::class.java) ?: return
        wm.openOrFocus("diagnostics") {
            val form = object : FormPanel(0.9f, 0.62f) {
                private var t = 0f
                override fun onFrame(dt: Float) { t += dt; if (t > 1f) { t = 0f; refresh() } }
            }
            form.title = "📈  Diagnóstico"
            form.tab("Agora") {
                val am = ctx.activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val rt = Runtime.getRuntime()
                info("FPS: ${"%.1f".format(ctx.fps)}   CPU/frame: ${"%.1f".format(ctx.cpuFrameMs)} ms   Escala: ${(ctx.renderScale * 100).toInt()}%")
                info("Rastreamento: ${ctx.tracking.quality}  •  ${ctx.trackingProvider?.capabilities?.name ?: "-"}")
                info("Planos: ${ctx.tracking.planes.size}   Profundidade: ${if (ctx.tracking.depth.valid) "${ctx.tracking.depth.width}×${ctx.tracking.depth.height}" else "não"}")
                info("Mãos: ${ctx.hands.source}  •  ${"%.0f".format(ctx.hands.inferenceFps)} Hz  •  ${"%.0f".format(ctx.hands.latencyMs)} ms")
                info("Esq: ${if (ctx.hands.left.tracked) ctx.hands.left.gesture.name else "—"}   Dir: ${if (ctx.hands.right.tracked) ctx.hands.right.gesture.name else "—"}")
                info("Memória app: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB / ${rt.maxMemory() / 1048576} MB   Sistema livre: ${mi.availMem / 1048576} MB")
                ctx.handProvider?.let { info("Status das mãos: ${it.statusText}", 2) }
                val crash = File(ctx.activity.filesDir, "last_crash.txt")
                if (crash.exists()) {
                    header("Último erro")
                    info(crash.readText().lines().take(6).joinToString("\n"), 6)
                    button("Limpar relatório", "🧹") { crash.delete(); form.refresh() }
                }
            }
            XrWindow(ctx, "Diagnóstico", form, "📈", UiTheme.neonMint)
        }
    }
}
