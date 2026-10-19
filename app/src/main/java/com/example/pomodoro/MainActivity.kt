package com.example.pomodoro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Duraciones configurables (referencia inicial: 25 y 5)
    private var minutosConcentracion = 25
    private var minutosDescanso = 5

    private lateinit var tvEstado: TextView
    private lateinit var tvTiempo: TextView
    private lateinit var tvCiclos: TextView
    private lateinit var btnIniciar: Button
    private lateinit var btnPausar: Button
    private lateinit var btnReanudar: Button
    private lateinit var btnDetener: Button
    private lateinit var etConcentracion: EditText
    private lateinit var etDescanso: EditText
    private lateinit var btnGuardarConfig: Button

    // NUEVO: un BroadcastReceiver "escucha" avisos. Cada vez que el servicio avisa
    // (cada segundo, o al cambiar de fase), la pantalla se vuelve a dibujar.
    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            actualizarPantalla()
        }
    }

    // NUEVO: así se pide un permiso en tiempo de ejecución. Se declara aquí y se lanza
    // con .launch(...). Android muestra el cuadrito "¿Permitir notificaciones?" y
    // luego ejecuta este bloque con la respuesta (concedido = true/false).
    private val permisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (!concedido) {
                Toast.makeText(
                    this,
                    "Sin permiso no verás la notificación, pero el temporizador funcionará",
                    Toast.LENGTH_LONG
                ).show()
            }
            iniciarServicio() // iniciamos de todos modos
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvEstado = findViewById(R.id.tv_estado)
        tvTiempo = findViewById(R.id.tv_tiempo)
        tvCiclos = findViewById(R.id.tv_ciclos)
        btnIniciar = findViewById(R.id.btn_iniciar)
        btnPausar = findViewById(R.id.btn_pausar)
        btnReanudar = findViewById(R.id.btn_reanudar)
        btnDetener = findViewById(R.id.btn_detener)
        etConcentracion = findViewById(R.id.et_minutos_concentracion)
        etDescanso = findViewById(R.id.et_minutos_descanso)
        btnGuardarConfig = findViewById(R.id.btn_guardar_config)

        btnIniciar.setOnClickListener { pedirPermisoEIniciar() }
        btnPausar.setOnClickListener { enviarOrden(PomodoroService.ACTION_PAUSAR) }
        btnReanudar.setOnClickListener { enviarOrden(PomodoroService.ACTION_REANUDAR) }
        btnDetener.setOnClickListener { enviarOrden(PomodoroService.ACTION_DETENER) }
        btnGuardarConfig.setOnClickListener { guardarConfiguracion() }
    }

    // NUEVO: onStart/onStop se llaman cuando la pantalla se hace visible / deja de serlo.
    // Solo escuchamos los avisos mientras la pantalla se ve (no tiene caso dibujar si no).
    override fun onStart() {
        super.onStart()
        // RECEIVER_NOT_EXPORTED: solo acepta avisos de nuestra propia app (obligatorio
        // elegir uno de los dos en Android 14+)
        ContextCompat.registerReceiver(
            this,
            receptor,
            IntentFilter(PomodoroService.ACTION_ACTUALIZAR),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Al volver de segundo plano nos ponemos al día con el estado actual del servicio
        actualizarPantalla()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receptor)
    }

    private fun pedirPermisoEIniciar() {
        // POST_NOTIFICATIONS solo existe (y hay que pedirlo) desde Android 13 (TIRAMISU)
        val necesitaPermiso = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        if (necesitaPermiso) {
            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            iniciarServicio()
        }
    }

    private fun iniciarServicio() {
        val intent = Intent(this, PomodoroService::class.java).apply {
            action = PomodoroService.ACTION_INICIAR
            putExtra(PomodoroService.EXTRA_MIN_CONCENTRACION, minutosConcentracion)
            putExtra(PomodoroService.EXTRA_MIN_DESCANSO, minutosDescanso)
        }
        // NUEVO: startForegroundService es la forma de arrancar un servicio que va a
        // mostrarse como "foreground". El servicio tiene pocos segundos para llamar a
        // startForeground (ya lo hace en PomodoroService).
        ContextCompat.startForegroundService(this, intent)
    }

    // Para pausar, reanudar y detener el servicio ya está corriendo, así que basta startService
    private fun enviarOrden(accion: String) {
        startService(Intent(this, PomodoroService::class.java).apply { action = accion })
    }

    private fun guardarConfiguracion() {
        val concentracion = etConcentracion.text.toString().trim().toIntOrNull()
        val descanso = etDescanso.text.toString().trim().toIntOrNull()

        if (concentracion == null || descanso == null || concentracion <= 0 || descanso <= 0) {
            Toast.makeText(this, "Ingresa minutos válidos (mayores a 0)", Toast.LENGTH_SHORT).show()
            return
        }

        minutosConcentracion = concentracion
        minutosDescanso = descanso
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        actualizarPantalla()
    }

    // Dibuja la pantalla leyendo el estado que guarda el servicio
    private fun actualizarPantalla() {
        val estado = PomodoroService.estadoSesion

        tvCiclos.text = "Ciclos completados: ${PomodoroService.ciclosCompletados}"

        when (estado) {
            EstadoSesion.DETENIDA -> {
                btnIniciar.visibility = View.VISIBLE
                btnPausar.visibility = View.GONE
                btnReanudar.visibility = View.GONE
                btnDetener.visibility = View.GONE
                etConcentracion.isEnabled = true
                etDescanso.isEnabled = true
                btnGuardarConfig.isEnabled = true
                tvTiempo.text = PomodoroService.formatearTiempo(minutosConcentracion * 60)
                tvEstado.text = if (PomodoroService.sesionCompletada) {
                    "¡Ciclo completado! Listo para otro"
                } else {
                    "Concentración"
                }
            }
            EstadoSesion.CORRIENDO -> {
                btnIniciar.visibility = View.GONE
                btnPausar.visibility = View.VISIBLE
                btnReanudar.visibility = View.GONE
                btnDetener.visibility = View.VISIBLE
                etConcentracion.isEnabled = false
                etDescanso.isEnabled = false
                btnGuardarConfig.isEnabled = false
                mostrarFaseYTiempo(pausada = false)
            }
            EstadoSesion.PAUSADA -> {
                btnIniciar.visibility = View.GONE
                btnPausar.visibility = View.GONE
                btnReanudar.visibility = View.VISIBLE
                btnDetener.visibility = View.VISIBLE
                etConcentracion.isEnabled = false
                etDescanso.isEnabled = false
                btnGuardarConfig.isEnabled = false
                mostrarFaseYTiempo(pausada = true)
            }
        }
    }

    private fun mostrarFaseYTiempo(pausada: Boolean) {
        val fase = if (PomodoroService.enDescanso) "Descanso" else "Concentración"
        tvEstado.text = if (pausada) "$fase (en pausa)" else fase
        tvTiempo.text = PomodoroService.formatearTiempo(PomodoroService.segundosRestantes)
    }
}