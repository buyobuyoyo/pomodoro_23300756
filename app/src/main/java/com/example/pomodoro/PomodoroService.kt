package com.example.pomodoro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// Lista cerrada de estados posibles de la sesión (antes estaba en MainActivity.kt)
enum class EstadoSesion {
    DETENIDA,   // no hay sesión activa
    CORRIENDO,  // el temporizador está contando
    PAUSADA     // la sesión está en pausa
}

// NUEVO: un Service es un componente de Android que corre SIN pantalla.
// Un FOREGROUND service además muestra una notificación permanente, y a cambio
// Android lo mantiene vivo aunque la app esté en segundo plano.
// Aquí vive el temporizador: la pantalla (MainActivity) solo da órdenes y muestra datos.
class PomodoroService : Service() {

    // NUEVO: "companion object" = cosas que pertenecen a la CLASE y no a un objeto.
    // Se acceden así: PomodoroService.estadoSesion (sin crear el servicio).
    companion object {
        // "Órdenes" que la pantalla le manda al servicio
        const val ACTION_INICIAR = "com.example.pomodoro.INICIAR"
        const val ACTION_PAUSAR = "com.example.pomodoro.PAUSAR"
        const val ACTION_REANUDAR = "com.example.pomodoro.REANUDAR"
        const val ACTION_DETENER = "com.example.pomodoro.DETENER"

        // Aviso que el servicio le manda a la pantalla: "algo cambió, vuelve a dibujarte"
        const val ACTION_ACTUALIZAR = "com.example.pomodoro.ACTUALIZAR"

        // Datos que acompañan a la orden de iniciar
        const val EXTRA_MIN_CONCENTRACION = "min_concentracion"
        const val EXTRA_MIN_DESCANSO = "min_descanso"

        private const val CHANNEL_ID = "pomodoro_channel"
        private const val NOTIFICATION_ID = 1

        // Estado de la sesión (una sola fuente de verdad: el servicio lo escribe,
        // la pantalla solo lo lee)
        var estadoSesion = EstadoSesion.DETENIDA
        var enDescanso = false
        var segundosRestantes = 0
        var ciclosCompletados = 0
        var sesionCompletada = false // true si el último descanso terminó solo

        // Convierte segundos a "MM:SS". Ejemplo: 1500 -> "25:00"
        fun formatearTiempo(segundosTotales: Int): String {
            val minutos = segundosTotales / 60
            val segundos = segundosTotales % 60
            return String.format("%02d:%02d", minutos, segundos)
        }
    }

    private var timer: CountDownTimer? = null
    private var minutosDescanso = 5
    private var milisRestantes = 0L

    // Un servicio que no se "une" a la pantalla devuelve null aquí
    override fun onBind(intent: Intent?): IBinder? = null

    // NUEVO: Android llama a onStartCommand cada vez que alguien hace startService /
    // startForegroundService hacia este servicio. Aquí leemos la "orden" (action).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INICIAR -> iniciarSesion(intent)
            ACTION_PAUSAR -> pausarSesion()
            ACTION_REANUDAR -> reanudarSesion()
            ACTION_DETENER -> detenerSesion()
        }
        // START_NOT_STICKY: si Android mata el servicio por falta de memoria,
        // NO lo reinicia solo (perderíamos el tiempo restante de todos modos).
        return START_NOT_STICKY
    }

    private fun iniciarSesion(intent: Intent) {
        if (estadoSesion != EstadoSesion.DETENIDA) return // ya hay una sesión activa

        val minutosConcentracion = intent.getIntExtra(EXTRA_MIN_CONCENTRACION, 25)
        minutosDescanso = intent.getIntExtra(EXTRA_MIN_DESCANSO, 5)

        sesionCompletada = false
        enDescanso = false
        estadoSesion = EstadoSesion.CORRIENDO
        segundosRestantes = minutosConcentracion * 60

        crearCanalNotificacion()

        // NUEVO: startForeground convierte este servicio en "foreground": le pasamos el
        // número de la notificación y la notificación misma. Android EXIGE llamarlo pocos
        // segundos después de startForegroundService, por eso va primero.
        // Desde Android 14 hay que indicar el TIPO de servicio (el mismo del manifest).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                construirNotificacion(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, construirNotificacion())
        }

        iniciarFase(minutosConcentracion * 60_000L)
    }

    // Arranca (o reanuda) una cuenta regresiva de "millis" milisegundos
    private fun iniciarFase(millis: Long) {
        timer?.cancel()
        milisRestantes = millis

        // NUEVO: CountDownTimer llama a onTick cada 1000 ms y a onFinish al llegar a 0.
        timer = object : CountDownTimer(millis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                milisRestantes = millisUntilFinished
                // +500 para redondear al segundo más cercano (si no, se ve 24:59 al inicio)
                segundosRestantes = ((millisUntilFinished + 500) / 1000).toInt()
                actualizar()
            }

            override fun onFinish() {
                terminarFase()
            }
        }.start()
    }

    // Se llama cuando una fase llega a 0
    private fun terminarFase() {
        if (!enDescanso) {
            // Terminó la concentración -> empieza el descanso automáticamente
            enDescanso = true
            segundosRestantes = minutosDescanso * 60
            iniciarFase(minutosDescanso * 60_000L)
            actualizar()
        } else {
            // Terminó el descanso -> el ciclo está completo; la sesión termina
            // y el usuario decide si empieza otro ciclo.
            ciclosCompletados++
            sesionCompletada = true
            finalizarSesion()
        }
    }

    private fun pausarSesion() {
        if (estadoSesion != EstadoSesion.CORRIENDO) return
        timer?.cancel() // milisRestantes ya guarda cuánto faltaba
        estadoSesion = EstadoSesion.PAUSADA
        actualizar()
    }

    private fun reanudarSesion() {
        if (estadoSesion != EstadoSesion.PAUSADA) return
        estadoSesion = EstadoSesion.CORRIENDO
        iniciarFase(milisRestantes) // continúa justo donde se quedó
        actualizar()
    }

    private fun detenerSesion() {
        ciclosCompletados = 0 // detener manualmente reinicia el contador
        sesionCompletada = false
        finalizarSesion()
    }

    // Limpia todo, quita la notificación y apaga el servicio
    private fun finalizarSesion() {
        timer?.cancel()
        timer = null
        estadoSesion = EstadoSesion.DETENIDA
        enDescanso = false
        segundosRestantes = 0

        // Quita el modo foreground Y borra la notificación
        stopForeground(STOP_FOREGROUND_REMOVE)
        // El servicio se apaga a sí mismo
        stopSelf()
        avisarALaPantalla()
    }

    // Refresca la notificación y avisa a la pantalla
    private fun actualizar() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, construirNotificacion())
        avisarALaPantalla()
    }

    // NUEVO: sendBroadcast lanza un "aviso" que cualquiera puede escuchar.
    // setPackage(packageName) lo limita a nuestra propia app (por seguridad).
    private fun avisarALaPantalla() {
        sendBroadcast(Intent(ACTION_ACTUALIZAR).setPackage(packageName))
    }

    // NUEVO: desde Android 8 toda notificación necesita un "canal" (agrupa notificaciones
    // y el usuario puede configurarlo en Ajustes). IMPORTANCE_LOW = sin sonido ni vibración,
    // para que no suene cada segundo que se actualiza.
    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Temporizador Pomodoro",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra el estado y el tiempo restante de la sesión Pomodoro"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): android.app.Notification {
        val fase = if (enDescanso) "Descanso" else "Concentración"
        val titulo = if (estadoSesion == EstadoSesion.PAUSADA) "$fase (en pausa)" else fase
        val texto = "Tiempo restante: ${formatearTiempo(segundosRestantes)}" +
                " · Ciclos completados: $ciclosCompletados"

        // NUEVO: PendingIntent = un Intent "guardado" que Android ejecuta después, cuando
        // el usuario toca la notificación (abre MainActivity). FLAG_IMMUTABLE es obligatorio
        // en Android 12+ salvo que necesites modificarlo.
        val abrirApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // ícono provisional del sistema
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrirApp)
            .setOngoing(true)      // el usuario no puede deslizarla para quitarla
            .setOnlyAlertOnce(true) // no vuelve a "avisar" en cada actualización
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // NUEVO: botones dentro de la notificación. Si está corriendo mostramos "Pausar";
        // si está en pausa, "Reanudar". "Detener" siempre aparece.
        if (estadoSesion == EstadoSesion.CORRIENDO) {
            builder.addAction(
                android.R.drawable.ic_media_pause, "Pausar",
                accionNotificacion(ACTION_PAUSAR, 1)
            )
        } else if (estadoSesion == EstadoSesion.PAUSADA) {
            builder.addAction(
                android.R.drawable.ic_media_play, "Reanudar",
                accionNotificacion(ACTION_REANUDAR, 2)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "Detener",
            accionNotificacion(ACTION_DETENER, 3)
        )

        return builder.build()
    }

    // NUEVO: crea un PendingIntent que, al tocar el botón, le manda la orden (action)
    // a este mismo servicio. Cae en onStartCommand, igual que cuando la pantalla manda
    // las órdenes. El "requestCode" distinto (1, 2, 3) evita que Android confunda un
    // botón con otro.
    private fun accionNotificacion(accion: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PomodoroService::class.java).apply { action = accion }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}