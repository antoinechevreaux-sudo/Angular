package com.tony.angulo

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.*
import android.os.*
import kotlin.math.*

/* Servicio en primer plano: sigue funcionando con Waze delante o con la pantalla apagada.
   - Micrófono: espera un silbido (Detector.java).
   - Sensor de rotación: mide el giro del coche desde el momento del silbido.
   - Avisa a 25° y 45°; suena al empezar y al terminar; se para solo al minuto. */
class AnguloService : Service(), SensorEventListener {
    companion object {
        const val CANAL = "angulo"
        const val ACT_RESET = "com.tony.angulo.RESET"
        const val ACT_STOP = "com.tony.angulo.STOP"
        const val ACTIVE_MS = 60_000L
        @Volatile var running = false
        @Volatile var active = false
        @Volatile var angle = 0.0
        @Volatile var until = 0L
        @Volatile var hearing = 0L
        @Volatile var micOk = false
        @Volatile var sensorOk = false
        @Volatile var vol = -1
        var instance: AnguloService? = null
        fun estadoJson(): String {
            val left = if (active) max(0L, (until - SystemClock.elapsedRealtime() + 999) / 1000) else 0
            val hear = SystemClock.elapsedRealtime() - hearing < 300
            return """{"running":$running,"active":$active,"angle":${"%.2f".format(java.util.Locale.US, angle)},"left":$left,"hear":$hear,"mic":$micOk,"sensor":$sensorOk,"vol":$vol}"""
        }
    }

    private lateinit var sm: SensorManager
    private var wake: PowerManager.WakeLock? = null
    private var audioThread: Thread? = null
    @Volatile private var stopAudio = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var beeper: Beeper
    private lateinit var vib: Vibrator

    // giro
    private var axis = 0 // 0 = sin elegir, 1 = Y, 2 = Z
    private var lastYaw: Double? = null
    private var unwrapped = 0.0
    private var refYaw: Double? = null
    private val armed = booleanArrayOf(true, true)
    private val rot = FloatArray(9)
    private var detector: Detector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VibratorManager::class.java)).defaultVibrator
              else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        beeper = Beeper(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Ángulo de Maniobra", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACT_RESET -> { if (running) activate(); return START_STICKY }
        }
        if (running) return START_STICKY
        val n = noti()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = 0
            if (Build.VERSION.SDK_INT >= 30) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1, n, type)
        } else startForeground(1, n)
        running = true
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "angulo:servicio").apply { acquire() }
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorOk = s != null
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        startAudio()
        beeper.ready()   // un «tin» al arrancar: si no se oye, el volumen multimedia está bajo
        tick()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; active = false
        stopAudio = true
        sm.unregisterListener(this)
        main.removeCallbacksAndMessages(null)
        try { wake?.release() } catch (_: Exception) {}
        beeper.release()
        instance = null
        super.onDestroy()
    }

    // ---------- notificación ----------
    private fun noti(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val reset = PendingIntent.getService(this, 1, Intent(this, AnguloService::class.java).setAction(ACT_RESET), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AnguloService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE)
        val txt = when {
            active -> "Midiendo · ${abs(angle).roundToInt()}° · ${max(0L, (until - SystemClock.elapsedRealtime() + 999) / 1000)} s"
            !micOk -> "Sin micrófono · usa «Poner a 0°»"
            else -> "Escuchando · silba para empezar"
        }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ángulo de Maniobra")
            .setContentText(txt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Poner a 0°", reset).build())
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }
    private var lastNoti = ""
    private fun refreshNoti() {
        val n = noti()
        val key = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (key == lastNoti) return
        lastNoti = key
        getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun tick() {
        if (!running) return
        if (active && SystemClock.elapsedRealtime() > until) deactivate()
        vol = try { val am = getSystemService(AudioManager::class.java); am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) } catch (_: Exception) { -1 }
        refreshNoti()
        main.postDelayed({ tick() }, 500)
    }

    // ---------- medir ----------
    fun activate() {
        main.post {
            zero(); active = true; until = SystemClock.elapsedRealtime() + ACTIVE_MS
            detector?.pause(true)
            vibrate(longArrayOf(0, 80, 60, 80))
            beeper.start()
            refreshNoti()
        }
    }
    private fun deactivate() {
        active = false
        beeper.end()
        vibrate(longArrayOf(0, 200))
        main.postDelayed({ if (!active) detector?.pause(false) }, 1500)
        refreshNoti()
    }
    private fun zero() { axis = 0; lastYaw = null; refYaw = null; unwrapped = 0.0; angle = 0.0; armed[0] = true; armed[1] = true }

    private fun vibrate(p: LongArray) {
        try { if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(p, -1)) } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // columnas Y (eje largo) y Z (perpendicular a la pantalla) en coordenadas del mundo
        val y = doubleArrayOf(rot[1].toDouble(), rot[4].toDouble(), rot[7].toDouble())
        val z = doubleArrayOf(rot[2].toDouble(), rot[5].toDouble(), rot[8].toDouble())
        if (axis == 0) axis = if (abs(y[2]) < abs(z[2])) 1 else 2
        val v = if (axis == 1) y else z
        if (hypot(v[0], v[1]) < 0.2) return
        val yaw = Math.toDegrees(atan2(v[1], v[0]))
        lastYaw?.let { var d = yaw - it; if (d > 180) d -= 360; if (d < -180) d += 360; unwrapped += d }
        lastYaw = yaw
        if (refYaw == null) refYaw = unwrapped
        angle = -(unwrapped - refYaw!!)
        if (!active) return
        val a = abs(angle)
        val lv = intArrayOf(25, 45)
        for (i in 0..1) {
            if (armed[i] && a >= lv[i]) { armed[i] = false; beeper.alert(lv[i]); vibrate(if (i == 0) longArrayOf(0, 120) else longArrayOf(0, 120, 80, 120)) }
            else if (!armed[i] && a < lv[i] - 3) armed[i] = true
        }
    }

    // ---------- micrófono ----------
    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micOk = false; return }
        stopAudio = false
        audioThread = Thread {
            val sr = 48000
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_RECOGNITION: sin control automático de volumen ni supresión de ruido (mejor para el silbido)
            @Suppress("MissingPermission")
            val r = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192)) }
                    catch (e: Exception) { micOk = false; return@Thread }
            if (r.state != AudioRecord.STATE_INITIALIZED) { micOk = false; r.release(); return@Thread }
            micOk = true
            val det = Detector(sr) { activate() }
            detector = det
            val buf = ShortArray(960); val f = FloatArray(960)
            r.startRecording()
            while (!stopAudio) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) f[i] = buf[i] / 32768f
                det.feed(f, n)
                if (det.lastOk) hearing = SystemClock.elapsedRealtime()
            }
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }.apply { isDaemon = true; start() }
    }
}

/* Sonidos: se oyen por encima de Waze o la música (bajan un momento su volumen). */
class Beeper(ctx: Context) {
    private val sr = 44100
    private val am = ctx.getSystemService(AudioManager::class.java)
    // mismo canal que las indicaciones de Waze: sigue el volumen multimedia (el de «sonidos del sistema» suele estar a cero en Xiaomi)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private val worker = HandlerThread("pitidos").apply { start() }
    private val h = Handler(worker.looper)

    private fun tones(vararg t: DoubleArray): ShortArray {   // t = [frecuencia, inicio s, duración s, volumen]
        val len = (t.maxOf { it[1] + it[2] } * sr).toInt() + sr / 20
        val out = FloatArray(len)
        for (x in t) {
            val s0 = (x[1] * sr).toInt(); val n = (x[2] * sr).toInt()
            for (i in 0 until n) {
                val tt = i.toDouble() / sr
                val env = min(1.0, tt / 0.015) * exp(-4.0 * tt / x[2])
                out[s0 + i] += (x[3] * env * sin(2 * PI * x[0] * tt)).toFloat()
            }
        }
        return ShortArray(len) { (max(-1f, min(1f, out[it])) * 32000).toInt().toShort() }
    }
    private val sStart = tones(doubleArrayOf(523.25, 0.0, 0.18, 0.5), doubleArrayOf(659.25, 0.14, 0.18, 0.5), doubleArrayOf(783.99, 0.28, 0.3, 0.5))
    private val sEnd = tones(doubleArrayOf(783.99, 0.0, 0.18, 0.45), doubleArrayOf(523.25, 0.16, 0.35, 0.45))
    private val s25 = tones(doubleArrayOf(784.0, 0.0, 0.45, 0.6), doubleArrayOf(1046.5, 0.15, 0.6, 0.55))
    private val s45 = tones(doubleArrayOf(1046.5, 0.0, 0.3, 0.6), doubleArrayOf(1318.5, 0.12, 0.3, 0.55), doubleArrayOf(1568.0, 0.24, 0.7, 0.55))

    private fun play(pcm: ShortArray) = h.post {
        try {
            am.requestAudioFocus(focus)
            val tr = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            tr.write(pcm, 0, pcm.size); tr.play()
            Thread.sleep(pcm.size * 1000L / sr + 80)
            tr.release()
            am.abandonAudioFocusRequest(focus)
        } catch (_: Exception) {}
    }
    private val sReady = tones(doubleArrayOf(659.25, 0.0, 0.2, 0.45))
    fun ready() = play(sReady)
    fun start() = play(sStart)
    fun end() = play(sEnd)
    fun alert(l: Int) = play(if (l == 25) s25 else s45)
    fun release() { worker.quitSafely() }
}
