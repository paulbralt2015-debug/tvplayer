package com.example.tvplayer

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    // ------------------------------------------------------------ Réglages

    private enum class AspectMode(
        val label: String,
        val ratio: Float?,
        val zoom: Boolean = false,
        val stretch: Boolean = false
    ) {
        ORIGINAL("Original (ratio de la vidéo)", null),
        ZOOM("Remplir l'écran (zoom, bords rognés)", null, zoom = true),
        STRETCH("Étirer (image déformée)", null, stretch = true),
        R_4_3("4:3", 4f / 3f),
        R_16_9("16:9", 16f / 9f),
        R_16_10("16:10", 16f / 10f),
        R_21_9("21:9", 21f / 9f),
        R_1_1("1:1", 1f)
    }

    private enum class RefreshMode(val label: String, val hz: Float?) {
        AUTO("Auto (par défaut de la TV)", null),
        HZ_50("50 Hz", 50f),
        HZ_60("60 Hz", 60f)
    }

    private enum class Interpolation(val label: String, val hz: Float?) {
        OFF("Désactivée", null),
        FPS_50("50 fps (expérimental)", 50f),
        FPS_60("60 fps (expérimental)", 60f)
    }

    private lateinit var playerView: PlayerView
    private lateinit var glView: GLSurfaceView
    private lateinit var glRenderer: InterpolatingRenderer
    private lateinit var toolbar: View
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null

    // Surface fournie par le renderer OpenGL quand l'interpolation est active
    private var interpSurface: Surface? = null
    private var lastVideoSize: VideoSize? = null

    // 0, 90, 180, 270 (rotation complète par pas de 90°)
    private var rotationDeg = 0f
    private var aspectMode = AspectMode.ORIGINAL
    private var refreshMode = RefreshMode.AUTO
    private var interpolation = Interpolation.OFF

    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                currentUri = uri
                startPlayer()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        glView = findViewById(R.id.gl_view)
        toolbar = findViewById(R.id.toolbar)

        // Renderer OpenGL (utilisé seulement quand l'interpolation est activée)
        glRenderer = InterpolatingRenderer { surface ->
            runOnUiThread {
                interpSurface = surface
                attachVideoOutput()
                applyTransform()
            }
        }
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(glRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        aspectMode = runCatching { AspectMode.valueOf(prefs.getString("aspect", "") ?: "") }
            .getOrDefault(AspectMode.ORIGINAL)
        refreshMode = runCatching { RefreshMode.valueOf(prefs.getString("refresh", "") ?: "") }
            .getOrDefault(RefreshMode.AUTO)
        val savedInterp = runCatching { Interpolation.valueOf(prefs.getString("interp", "") ?: "") }
            .getOrDefault(Interpolation.OFF)

        findViewById<Button>(R.id.audio_button).setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_AUDIO, "Piste audio")
        }
        findViewById<Button>(R.id.subtitle_button).setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_TEXT, "Sous-titres")
        }
        findViewById<Button>(R.id.aspect_button).setOnClickListener { showAspectDialog() }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { showRefreshDialog() }
        findViewById<Button>(R.id.interp_button).setOnClickListener { showInterpolationDialog() }
        findViewById<Button>(R.id.rotate_button).setOnClickListener { rotate(+90f) }

        // Les boutons suivent la visibilité des contrôles du lecteur
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> toolbar.visibility = visibility }
        )

        // Réapplique rotation + format à chaque changement de taille de la surface vidéo (mode normal)
        playerView.videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyTransform()
        }
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }

        if (savedInterp != Interpolation.OFF) {
            setInterpolation(savedInterp, announce = false)
        } else {
            applyRefreshMode(refreshMode, announce = false)
        }

        currentUri = intent?.data
        if (currentUri == null) {
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    override fun onStart() {
        super.onStart()
        glView.onResume()
        if (currentUri != null) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
        interpSurface = null
        glView.queueEvent { glRenderer.release() }
        glView.onPause()
    }

    private fun startPlayer() {
        val uri = currentUri ?: return
        releasePlayer()

        val renderersFactory = DefaultRenderersFactory(this)
            // Essaie d'abord le décodeur FFmpeg (DTS, AC3, E-AC3, TrueHD, AAC...)
            // puis retombe sur MediaCodec si besoin.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory).build().also { p ->
            playerView.player = p
            p.setMediaItem(MediaItem.fromUri(uri))
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    lastVideoSize = videoSize
                    pushVideoSizeToRenderer()
                    playerView.post { applyTransform() }
                }
            })
            p.prepare()
            p.playWhenReady = true
        }
        attachVideoOutput()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private fun pushVideoSizeToRenderer() {
        val vs = lastVideoSize ?: return
        glRenderer.videoWidth = vs.width
        glRenderer.videoHeight = vs.height
    }

    /**
     * Choisit où ExoPlayer envoie la vidéo :
     *  - interpolation désactivée : la TextureView du PlayerView (mode normal) ;
     *  - interpolation activée : la Surface du renderer OpenGL.
     */
    private fun attachVideoOutput() {
        val p = player
        if (interpolation == Interpolation.OFF) {
            if (p != null) {
                (playerView.videoSurfaceView as? TextureView)?.let { p.setVideoTextureView(it) }
            }
            glView.visibility = View.GONE
        } else {
            glView.visibility = View.VISIBLE
            val s = interpSurface
            if (p != null && s != null && s.isValid) p.setVideoSurface(s)
        }
    }

    // ------------------------------------------------------------- Dialogues

    private fun showChoiceDialog(
        title: String,
        labels: List<String>,
        checked: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ----------------------------------------------------------- Format image

    private fun showAspectDialog() {
        val modes = AspectMode.values().toList()
        showChoiceDialog("Format d'image", modes.map { it.label }, modes.indexOf(aspectMode)) { which ->
            aspectMode = modes[which]
            prefs.edit().putString("aspect", aspectMode.name).apply()
            applyTransform()
        }
    }

    // --------------------------------------------------------- Fréquence écran

    private fun showRefreshDialog() {
        val modes = RefreshMode.values().toList()
        showChoiceDialog("Fréquence d'affichage", modes.map { it.label }, modes.indexOf(refreshMode)) { which ->
            val wanted = modes[which]
            if (applyRefreshMode(wanted, announce = true)) {
                refreshMode = wanted
                prefs.edit().putString("refresh", wanted.name).apply()
                // Changer la fréquence à la main désactive l'interpolation (elle dépend de la fréquence choisie)
                if (interpolation != Interpolation.OFF) setInterpolation(Interpolation.OFF, announce = false)
            }
        }
    }

    /**
     * Demande à la TV de passer en 50 ou 60 Hz (même résolution). Ne crée PAS d'images
     * intermédiaires par elle-même : voir l'option « Interpolation » pour cela.
     */
    private fun applyRefreshMode(mode: RefreshMode, announce: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (announce && mode != RefreshMode.AUTO) toast("Changement de fréquence non pris en charge (Android 6 minimum)")
            return mode == RefreshMode.AUTO
        }

        val lp = window.attributes

        val hz = mode.hz
        if (hz == null) {
            lp.preferredDisplayModeId = 0
            window.attributes = lp
            if (announce) toast("Fréquence : automatique")
            return true
        }

        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val current = display.mode
        val candidate = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { abs(it.refreshRate - hz) }

        if (candidate == null || abs(candidate.refreshRate - hz) > 1f) {
            if (announce) toast("Votre TV ne propose pas ${mode.label} à cette résolution")
            return false
        }

        lp.preferredDisplayModeId = candidate.modeId
        window.attributes = lp
        if (announce) toast("Affichage demandé en ${mode.label} (l'écran peut se couper un instant)")
        return true
    }

    // ----------------------------------------------------------- Interpolation

    private fun showInterpolationDialog() {
        val modes = Interpolation.values().toList()
        showChoiceDialog(
            "Interpolation (expérimentale)",
            modes.map { it.label },
            modes.indexOf(interpolation)
        ) { which -> setInterpolation(modes[which], announce = true) }
    }

    /**
     * Active/désactive l'interpolation logicielle par fondu entre images.
     * Activer 50 ou 60 fps demande aussi à la TV le mode d'affichage correspondant.
     */
    private fun setInterpolation(mode: Interpolation, announce: Boolean): Boolean {
        val hz = mode.hz
        if (hz != null) {
            val target = if (hz == 50f) RefreshMode.HZ_50 else RefreshMode.HZ_60
            if (!applyRefreshMode(target, announce = announce)) return false
            refreshMode = target
            prefs.edit().putString("refresh", target.name).apply()
            glRenderer.displayIntervalNs = (1_000_000_000f / hz).toLong()
        }
        interpolation = mode
        prefs.edit().putString("interp", mode.name).apply()

        pushVideoSizeToRenderer()
        attachVideoOutput()
        applyTransform()
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- Pistes

    private data class TrackEntry(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

    private fun showTrackDialog(trackType: Int, title: String) {
        val p = player ?: return

        val entries = p.currentTracks.groups
            .filter { it.type == trackType }
            .flatMap { g ->
                (0 until g.length)
                    .filter { g.isTrackSupported(it) }
                    .map { i -> TrackEntry(g.mediaTrackGroup, i, describe(g.getTrackFormat(i), trackType), g.isTrackSelected(i)) }
            }

        if (entries.isEmpty()) {
            toast("Aucune piste disponible")
            return
        }

        val isText = trackType == C.TRACK_TYPE_TEXT
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        // Pour les sous-titres on ajoute une entrée « Désactivés » en tête
        val labels = mutableListOf<String>()
        if (isText) labels += "Désactivés"
        labels += entries.map { it.label }

        val checked = if (isText) {
            if (textDisabled) 0 else (entries.indexOfFirst { it.selected } + 1).coerceAtLeast(0)
        } else {
            entries.indexOfFirst { it.selected }.coerceAtLeast(0)
        }

        showChoiceDialog(title, labels, checked) { which ->
            val params = p.trackSelectionParameters.buildUpon()
            if (isText && which == 0) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val entry = entries[if (isText) which - 1 else which]
                params.setTrackTypeDisabled(trackType, false)
                params.clearOverridesOfType(trackType)
                params.setOverrideForType(TrackSelectionOverride(entry.group, entry.index))
            }
            p.trackSelectionParameters = params.build()
        }
    }

    private fun describe(f: Format, trackType: Int): String {
        val lang = f.language
            ?.takeIf { it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
            ?.replaceFirstChar { it.uppercase() }

        val parts = mutableListOf<String>()
        f.label?.let { parts += it }
        lang?.let { parts += it }

        if (trackType == C.TRACK_TYPE_AUDIO) {
            codecName(f.sampleMimeType)?.let { parts += it }
            if (f.channelCount > 0) parts += channelLayout(f.channelCount)
        }
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "forcés"

        return parts.distinct().joinToString(" · ").ifEmpty { "Piste" }
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_AAC -> "AAC"
        else -> mime?.substringAfter('/')?.uppercase()
    }

    private fun channelLayout(count: Int): String = when (count) {
        1 -> "mono"
        2 -> "stéréo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count canaux"
    }

    // ------------------------------------------------- Rotation + format image

    private fun rotate(delta: Float) {
        rotationDeg = ((rotationDeg + delta) % 360f + 360f) % 360f
        applyTransform()
    }

    /**
     * Taille (en px, dans le repère de la vidéo avant rotation) que doit occuper l'image
     * pour respecter le format choisi et tenir dans la zone du lecteur une fois tournée.
     */
    private fun targetSize(naturalRatio: Float, pw: Float, ph: Float): Pair<Float, Float> {
        // À 90° / 270° la vidéo est couchée : largeur et hauteur disponibles sont inversées.
        val sideways = rotationDeg % 180f != 0f
        val availW = if (sideways) ph else pw
        val availH = if (sideways) pw else ph

        if (aspectMode.stretch) return availW to availH

        val ratio = aspectMode.ratio ?: naturalRatio
        val containerWider = availW / availH > ratio
        // Ajuster : on cale sur la hauteur si le conteneur est plus large que le ratio.
        // Zoom : l'inverse, pour remplir tout l'écran.
        val fitByHeight = if (aspectMode.zoom) !containerWider else containerWider
        return if (fitByHeight) (availH * ratio) to availH else availW to (availW / ratio)
    }

    /**
     * Applique rotation et format d'image :
     *  - mode normal : transformation de la TextureView (échelles appliquées avant la rotation) ;
     *  - mode interpolation : paramètres transmis au renderer OpenGL, qui dessine l'image tournée.
     */
    private fun applyTransform() {
        val pw = playerView.width.toFloat()
        val ph = playerView.height.toFloat()
        if (pw == 0f || ph == 0f) return

        if (interpolation != Interpolation.OFF) {
            val vs = lastVideoSize ?: return
            if (vs.width == 0 || vs.height == 0) return
            val natural = vs.width * vs.pixelWidthHeightRatio / vs.height
            val (w, h) = targetSize(natural, pw, ph)
            glRenderer.setTransform(w, h, rotationDeg)
            return
        }

        val surface: View = playerView.videoSurfaceView ?: return
        val vw = surface.width.toFloat()
        val vh = surface.height.toFloat()
        if (vw == 0f || vh == 0f) return

        surface.rotation = rotationDeg
        val (w, h) = targetSize(vw / vh, pw, ph)
        surface.scaleX = w / vw
        surface.scaleY = h / vh
    }

    // Télécommande : rouge = +90°, vert = -90°, jaune = reset rotation,
    // bleu / touche « audio » = pistes audio, touche « CC » = sous-titres,
    // touche « zoom / format » = format d'image
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_R -> { rotate(+90f); return true }
                KeyEvent.KEYCODE_PROG_GREEN -> { rotate(-90f); return true }
                KeyEvent.KEYCODE_PROG_YELLOW -> { rotationDeg = 0f; applyTransform(); return true }
                KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                    showTrackDialog(C.TRACK_TYPE_AUDIO, "Piste audio"); return true
                }
                KeyEvent.KEYCODE_CAPTIONS -> {
                    showTrackDialog(C.TRACK_TYPE_TEXT, "Sous-titres"); return true
                }
                KeyEvent.KEYCODE_TV_ZOOM_MODE -> { showAspectDialog(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
