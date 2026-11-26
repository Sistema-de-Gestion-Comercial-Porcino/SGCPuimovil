package bo.edu.univalle.sgcp

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var almacen: AlmacenSesion
    private lateinit var cuerpo: LinearLayout
    private lateinit var dao: RepartoDao
    private lateinit var pendientes: AlmacenPendientesRoom
    private val alcance = MainScope()
    private var peticion: Call<*>? = null
    private var sesionActual: SesionGuardada? = null
    private var enPantallaEntregas = false
    private var sincronizando = false
    private var vigilante: ConnectivityManager.NetworkCallback? = null
    private var chipSenal: TextView? = null
    private var verCapturas = false
    private var borradorRevisado = false

    // Borrador del formulario de captura: sobrevive mientras se toma una foto.
    private var borrador: Borrador? = null
    private var fotoEnCurso: File? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private class Borrador(
        val entrega: EntregaLocal,
        var receptor: String = "",
        var fecha: String = Formato.ahoraBolivia(),
        var peso: String = "",
        var unidad: String = "kg",
        var conforme: Boolean = true,
        var observacion: String = "",
        val fotos: MutableList<String> = mutableListOf()
    )

    /** Copia del borrador en el teléfono: si Android cierra la app mientras la cámara está abierta, no se pierde lo escrito. */
    private data class BorradorGuardado(
        val entregaId: String,
        val receptor: String,
        val fecha: String,
        val peso: String,
        val unidad: String,
        val conforme: Boolean,
        val observacion: String,
        val fotos: List<String>,
        val fotoEnCurso: String?
    )

    private fun guardarBorradorEnTelefono(b: Borrador) {
        val copia = BorradorGuardado(b.entrega.id, b.receptor, b.fecha, b.peso, b.unidad, b.conforme, b.observacion, b.fotos.toList(), fotoEnCurso?.path)
        getSharedPreferences("borrador", MODE_PRIVATE).edit().putString("actual", com.google.gson.Gson().toJson(copia)).apply()
    }

    private fun leerBorradorDelTelefono(): BorradorGuardado? = runCatching {
        getSharedPreferences("borrador", MODE_PRIVATE).getString("actual", null)?.let { com.google.gson.Gson().fromJson(it, BorradorGuardado::class.java) }
    }.getOrNull()

    private fun olvidarBorrador() = getSharedPreferences("borrador", MODE_PRIVATE).edit().remove("actual").apply()

    /** Rearma el borrador con la entrega descargada; solo conserva las fotos que siguen existiendo. */
    private suspend fun restaurar(g: BorradorGuardado): Borrador? {
        val entrega = dao.entregas().firstOrNull { it.id == g.entregaId } ?: return null
        return Borrador(entrega, g.receptor, g.fecha, g.peso, g.unidad, g.conforme, g.observacion, g.fotos.filter { File(it).exists() }.toMutableList())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        almacen = AlmacenSesion(this)
        dao = BaseLocal.obtener(this).reparto()
        pendientes = AlmacenPendientesRoom(dao)
        val sesion = almacen.leer()
        if (sesion == null) acceso() else if (Conexion.vencido(sesion.expira) || sesion.usuarioId == null) acceso(true) else verificar(sesion)
    }

    override fun onResume() {
        super.onResume()
        // Sincronización con la app abierta: al recuperar la red se envía lo pendiente. El indicador de señal sigue a la red.
        val gestor = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    pintarSenal(true)
                    if (enPantallaEntregas) sesionActual?.let { sincronizar(it, silencioso = true) }
                }
            }

            override fun onLost(network: Network) {
                // Si el teléfono pasa de wifi a datos, Android avisa la pérdida antes que la nueva red: se vuelve a mirar en un momento.
                runOnUiThread { pintarSenal(false); chipSenal?.postDelayed({ pintarSenal(hayRed()) }, 800) }
            }
        }
        runCatching { gestor.registerDefaultNetworkCallback(callback); vigilante = callback }
        pintarSenal(hayRed())
    }

    override fun onPause() {
        vigilante?.let { v -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(v) } }
        vigilante = null
        super.onPause()
    }

    // ---------- Componentes visuales (mismos colores que la web) ----------

    private fun fondo(color: Int, radio: Int = 14, borde: Int? = Paleta.LINEA) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radio).toFloat()
        if (borde != null) setStroke(dp(1), borde)
    }

    /** Fondo con respuesta al toque, para que cada botón se sienta presionado. */
    private fun tocable(color: Int, radio: Int = 12, borde: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(0x33174B3E), fondo(color, radio, borde), null)

    private fun pagina(titulo: String, subtitulo: String, formulario: Boolean = false) {
        enPantallaEntregas = false
        atrasEnFormulario(formulario)
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Paleta.CREMA) }
        val barra = cabecera()
        raiz.addView(barra, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(22), dp(20), dp(28)) }
        scroll.addView(cuerpo)
        raiz.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Barras del sistema y teclado: el formulario sube por encima del teclado en vez de quedar tapado.
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            // La cabecera verde se extiende detrás de la barra de estado; abajo se reserva lugar para la navegación o el teclado.
            barra.setPadding(dp(18), dp(14) + barras.top, dp(16), dp(14))
            v.setPadding(0, 0, 0, maxOf(barras.bottom, teclado.bottom))
            insets
        }
        setContentView(raiz)
        texto(titulo, 27, Paleta.TINTA, abajo = 6).typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        if (subtitulo.isNotBlank()) texto(subtitulo, 15, Paleta.GRIS, abajo = 22)
    }

    private fun cabecera(): LinearLayout {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Paleta.VERDE)
            setPadding(dp(18), dp(14), dp(16), dp(14))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.sgcp_logo)
            background = fondo(Paleta.CREMA, 12, null)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = "Logo de SGCP"
        }
        barra.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
        val marca = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        marca.addView(TextView(this).apply { text = "SGCP"; textSize = 20f; setTextColor(Paleta.PAPEL); setTypeface(Typeface.SERIF, Typeface.BOLD) })
        marca.addView(TextView(this).apply { text = "Reparto · Gestión comercial porcino"; textSize = 12f; setTextColor(Paleta.SALVIA) })
        barra.addView(marca, LinearLayout.LayoutParams(0, -2, 1f))
        val chip = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        barra.addView(chip, LinearLayout.LayoutParams(-2, -2))
        chipSenal = chip
        pintarSenal(hayRed())
        return barra
    }

    private fun hayRed(): Boolean {
        val gestor = getSystemService(ConnectivityManager::class.java) ?: return false
        val capacidades = gestor.getNetworkCapabilities(gestor.activeNetwork) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun pintarSenal(conSenal: Boolean) {
        val chip = chipSenal ?: return
        chip.text = if (conSenal) "● Con señal" else "● Sin señal"
        chip.setTextColor(if (conSenal) Paleta.VERDE else Paleta.ARCILLA_OSCURA)
        chip.background = fondo(if (conSenal) Paleta.SALVIA_SUAVE else 0xFFF5E3DA.toInt(), 20, null)
        chip.contentDescription = if (conSenal) "El teléfono tiene señal" else "El teléfono está sin señal"
    }

    private fun texto(valor: String, size: Int = 15, color: Int = Paleta.TINTA, bold: Boolean = false, abajo: Int = 12, destino: LinearLayout = cuerpo): TextView {
        val t = TextView(this).apply {
            text = valor
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }
        destino.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(abajo) })
        return t
    }

    /** Texto de error que solo ocupa lugar cuando hay algo que decir. */
    private fun error(destino: LinearLayout = cuerpo): TextView = texto("", 14, Paleta.PELIGRO, true, 12, destino).apply { visibility = View.GONE }

    private fun TextView.mostrar(valor: String) { text = valor; visibility = if (valor.isBlank()) View.GONE else View.VISIBLE }

    private fun etiqueta(valor: String, destino: LinearLayout = cuerpo) = texto(valor.uppercase(), 12, Paleta.TINTA_SUAVE, true, 7, destino).apply { letterSpacing = 0.06f }

    private fun campo(titulo: String, tipo: Int, valor: String = "", lineas: Int = 1, destino: LinearLayout = cuerpo, pista: String = ""): EditText {
        etiqueta(titulo, destino)
        val e = EditText(this).apply {
            setText(valor)
            hint = pista
            inputType = tipo
            textSize = 16f
            setTextColor(Paleta.TINTA)
            setHintTextColor(Paleta.GRIS)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = fondo(Paleta.PAPEL, 11, Paleta.LINEA)
            if (lineas == 1) setSingleLine(true) else { minLines = lineas; gravity = Gravity.TOP or Gravity.START }
            contentDescription = titulo
        }
        destino.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        return e
    }

    private fun boton(titulo: String, destino: LinearLayout = cuerpo, claro: Boolean = false, accion: () -> Unit): TextView {
        val b = TextView(this).apply {
            text = titulo
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (claro) Paleta.VERDE else Paleta.PAPEL)
            background = if (claro) tocable(Paleta.PAPEL, 12, Paleta.LINEA) else tocable(Paleta.VERDE)
            isClickable = true
            isFocusable = true
            setOnClickListener { accion() }
        }
        destino.addView(b, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        return b
    }

    /** Botón chico de texto, para acciones secundarias dentro de una tarjeta (llamar, mapa, cambiar servidor). */
    private fun enlace(titulo: String, destino: LinearLayout, abajo: Int = 8, accion: () -> Unit): TextView = TextView(this).apply {
        text = titulo
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Paleta.VERDE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = tocable(Paleta.SALVIA_SUAVE, 18)
        isClickable = true
        setOnClickListener { accion() }
        destino.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8); bottomMargin = dp(abajo) })
    }

    private fun fila(destino: LinearLayout = cuerpo, abajo: Int = 6): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        destino.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(abajo) })
    }

    /** Opciones excluyentes como botones lado a lado: se ve de un vistazo cuál está elegida. */
    private fun segmento(opciones: List<String>, elegida: Int, destino: LinearLayout = cuerpo, alElegir: (Int) -> Unit) {
        val f = fila(destino, 18)
        opciones.forEachIndexed { i, nombre ->
            val activo = i == elegida
            f.addView(TextView(this).apply {
                text = nombre
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (activo) Paleta.PAPEL else Paleta.TINTA_SUAVE)
                background = if (activo) tocable(Paleta.VERDE, 11) else tocable(Paleta.PAPEL, 11, Paleta.LINEA)
                isClickable = true
                contentDescription = nombre + if (activo) ", elegido" else ""
                setOnClickListener { if (!activo) alElegir(i) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i < opciones.lastIndex) marginEnd = dp(8) })
        }
    }

    private fun insignia(valor: String, tono: Tono, destino: LinearLayout): TextView = TextView(this).apply {
        text = valor
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(tono.texto)
        background = fondo(tono.fondo, 20, null)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        destino.addView(this, LinearLayout.LayoutParams(-2, -2))
    }

    private fun tarjeta(destino: LinearLayout = cuerpo, acento: Int? = null): LinearLayout {
        val externa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = fondo(Paleta.PAPEL, 14, Paleta.LINEA)
            elevation = dp(1).toFloat()
            clipToOutline = true // la franja de color respeta las esquinas redondeadas
        }
        if (acento != null) externa.addView(View(this).apply { setBackgroundColor(acento) }, LinearLayout.LayoutParams(dp(5), -1))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(8)) }
        externa.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        destino.addView(externa, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return t
    }

    private fun titulo(valor: String, destino: LinearLayout = cuerpo) = texto(valor, 18, Paleta.TINTA, abajo = 10, destino = destino).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

    // ---------- Acceso (Sprint 1) ----------

    private fun acceso(renovar: Boolean = false) {
        pagina(
            if (renovar) "Renueva tu acceso" else "Acceder al reparto",
            if (renovar) "Tu sesión terminó. Conéctate e inicia sesión para continuar." else "Ingresa con la cuenta asignada por la administración."
        )
        val t = tarjeta()
        val usuario = campo("Usuario", InputType.TYPE_CLASS_TEXT, destino = t)
        val clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, destino = t)
        val preferencias = getSharedPreferences("conexion", MODE_PRIVATE)
        val guardadaUrl = preferencias.getString("url", BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL
        // La dirección del servidor es configuración, no código: se puede cambiar, pero no ocupa la pantalla de entrada.
        val servidor = fila(t, 8)
        val resumenServidor = TextView(this).apply { text = "Servidor: $guardadaUrl"; textSize = 12f; setTextColor(Paleta.GRIS) }
        servidor.addView(resumenServidor, LinearLayout.LayoutParams(0, -2, 1f))
        val zonaUrl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val url = campo("Dirección del servidor", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, guardadaUrl, destino = zonaUrl)
        enlace("Cambiar", servidor, abajo = 0) { zonaUrl.visibility = View.VISIBLE; resumenServidor.text = "Escribe la dirección compartida por el equipo."; url.requestFocus() }
        t.addView(zonaUrl, LinearLayout.LayoutParams(-1, -2))
        val mensajeError = error(t)
        lateinit var entrar: TextView
        entrar = boton("Ingresar", t) {
            if (usuario.text.isBlank() || clave.text.isBlank()) { mensajeError.mostrar("Completa usuario y contraseña."); return@boton }
            val direccion = runCatching { Conexion.validar(url.text.toString(), BuildConfig.DEBUG) }.getOrElse { mensajeError.mostrar(it.message ?: "Dirección inválida."); return@boton }
            entrar.isEnabled = false; entrar.text = "Conectando…"; mensajeError.mostrar("")
            val llamada = Conexion.crear(direccion).acceder(AccesoPeticion(usuario.text.toString().trim(), clave.text.toString()))
            peticion = llamada
            llamada.enqueue(object : Callback<AccesoRespuesta> {
                override fun onResponse(call: Call<AccesoRespuesta>, response: Response<AccesoRespuesta>) {
                    if (isDestroyed) return
                    entrar.isEnabled = true; entrar.text = "Ingresar"
                    val r = response.body()
                    if (!response.isSuccessful || r == null) { mensajeError.mostrar(mensaje(response)); return }
                    if (!puedeRepartir(r.usuario)) {
                        Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio())
                        mensajeError.mostrar("Esta aplicación está destinada al personal de reparto."); return
                    }
                    val sesion = SesionGuardada(r.token, r.expira, r.usuario.nombre, direccion, r.usuario.id)
                    try {
                        almacen.guardar(sesion); preferencias.edit().putString("url", direccion).apply(); clave.text.clear(); entregas(sesion)
                    } catch (e: Exception) {
                        mensajeError.mostrar("No se pudo guardar el acceso en este dispositivo.")
                        Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio())
                    }
                }

                override fun onFailure(call: Call<AccesoRespuesta>, t: Throwable) {
                    if (!isDestroyed && !call.isCanceled) {
                        entrar.isEnabled = true; entrar.text = "Ingresar"
                        mensajeError.mostrar("No se pudo conectar. Revisa internet y la dirección del servidor.")
                    }
                }
            })
        }
        texto("El inicio de sesión requiere conexión. Después puedes registrar entregas sin señal.", 13, Paleta.GRIS)
        // Sin señal y con la sesión vencida, igual se puede capturar lo ya descargado: la captura no necesita sesión, el envío sí.
        val guardada = almacen.leer()
        if (renovar && guardada?.usuarioId != null) {
            alcance.launch {
                if (dao.entregas().isNotEmpty()) {
                    val sinSenal = tarjeta(acento = Paleta.ARCILLA)
                    texto("¿Estás sin señal?", 16, Paleta.TINTA, true, 6, sinSenal)
                    texto("Puedes registrar recepciones de lo que ya descargaste. Se enviarán cuando vuelvas a iniciar sesión con conexión.", 14, Paleta.GRIS, false, 12, sinSenal)
                    boton("Capturar sin conexión", sinSenal, claro = true) { entregas(guardada) }
                }
            }
        }
    }

    private fun verificar(s: SesionGuardada) {
        pagina("Comprobando acceso", "Estamos verificando tu sesión con el servidor. Sin señal, entrarás con lo ya descargado.")
        cuerpo.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Paleta.VERDE) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24) })
        val c = Conexion.crear(s.url).perfil("Bearer " + s.token); peticion = c
        c.enqueue(object : Callback<Perfil> {
            override fun onResponse(call: Call<Perfil>, response: Response<Perfil>) {
                if (isDestroyed) return
                val perfil = response.body()
                if (response.isSuccessful && perfil != null && puedeRepartir(perfil)) entregas(s)
                else { almacen.borrar(); acceso(true) }
            }

            // Sin conexión se trabaja con lo descargado: es justamente el caso de uso del Sprint 3.
            override fun onFailure(call: Call<Perfil>, t: Throwable) {
                if (!isDestroyed && !call.isCanceled) entregas(s)
            }
        })
    }

    // ---------- Mis entregas: descarga, estado y sincronización (RF-07, RNF-01) ----------

    private fun entregas(s: SesionGuardada) {
        sesionActual = s
        alcance.launch {
            // Con una recepción a medio registrar (por ejemplo, recuperada al volver de la cámara), el formulario tiene prioridad.
            borrador?.let { capturar(s, it); return@launch }
            if (!borradorRevisado) {
                borradorRevisado = true
                val recuperado = leerBorradorDelTelefono()?.let { restaurar(it) }
                if (recuperado != null) {
                    avisar("Recuperamos la recepción que estabas registrando.")
                    capturar(s, recuperado); return@launch
                }
            }
            val lista = dao.entregas()
            val capturas = pendientes.todas()
            val sinEnviar = capturas.count { it.estado == Pendiente.nombre || it.estado == Enviando.nombre }
            val enConflicto = capturas.filter { it.estado == Conflicto.nombre }
            pintarEntregas(s, lista, capturas, sinEnviar, enConflicto)
        }
    }

    private fun cifra(destino: LinearLayout, valor: String, nombre: String, tono: Tono, ultimo: Boolean) {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo(tono.fondo, 12, null)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        caja.addView(TextView(this).apply { text = valor; textSize = 26f; setTextColor(tono.texto); setTypeface(Typeface.SERIF, Typeface.BOLD) })
        caja.addView(TextView(this).apply { text = nombre; textSize = 13f; setTextColor(tono.texto) })
        destino.addView(caja, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!ultimo) marginEnd = dp(10) })
    }

    private fun pintarEntregas(s: SesionGuardada, lista: List<EntregaLocal>, capturas: List<RegistroEnvio>, sinEnviar: Int, enConflicto: List<RegistroEnvio>) {
        pagina("Mis entregas", "Hola, ${s.nombre}.")
        enPantallaEntregas = true
        val ultimo = getSharedPreferences("reparto", MODE_PRIVATE).getString("ultimoEnvio", null)

        // Resumen siempre visible: cuántas capturas faltan enviar, cuándo fue el último envío y qué espera revisión.
        val resumen = tarjeta()
        val cifras = fila(resumen, 12)
        cifra(cifras, sinEnviar.toString(), "sin enviar", if (sinEnviar > 0) Tono.AVISO else Tono.EXITO, false)
        cifra(cifras, enConflicto.size.toString(), "en conflicto", if (enConflicto.isEmpty()) Tono.NEUTRO else Tono.PELIGRO, true)
        texto("Último envío: ${if (ultimo == null) "todavía no hubo" else Formato.bolivia(ultimo)}", 14, Paleta.GRIS, false, 10, resumen)
        if (enConflicto.isNotEmpty()) {
            texto("Los conflictos no se resuelven en el teléfono: los revisa Ventas o Administración en la web.", 13, Paleta.PELIGRO, false, 10, resumen)
        }
        if (sinEnviar > 0) {
            texto(
                "Lo que no se envió solo está en este teléfono. Si se borra la app o se pierde el teléfono, esas capturas se pierden. Sincroniza en cuanto tengas señal.",
                13, Paleta.AVISO, false, 12, resumen
            )
        }
        val estadoEnvio = texto(if (sincronizando) "Enviando…" else "", 13, Paleta.GRIS, false, 6, resumen).apply { mostrar(text.toString()) }
        boton("Sincronizar ahora", resumen) { estadoEnvio.mostrar("Enviando…"); sincronizar(s, silencioso = false, aviso = estadoEnvio) }

        boton("Descargar mis entregas", claro = true) { descargar(s) }

        // Capturas guardadas en este teléfono, con su estado: así se ve qué falta enviar y qué ya confirmó el servidor.
        if (capturas.isNotEmpty()) {
            boton(if (verCapturas) "Ocultar capturas del teléfono" else "Ver capturas del teléfono (${capturas.size})", claro = true) {
                verCapturas = !verCapturas; entregas(s)
            }
            if (verCapturas) {
                val pedidos = lista.associate { it.id to it.pedido }
                val t = tarjeta()
                titulo("Capturas en este teléfono", t)
                capturas.sortedByDescending { it.creadaEn }.forEach { c ->
                    val estado = SincronizacionRecepcion.estadoPorNombre(c.estado)
                    val cabeza = fila(t, 4)
                    cabeza.addView(TextView(this).apply {
                        text = pedidos[c.entregaId] ?: "Entrega ya no descargada"
                        textSize = 15f; setTextColor(Paleta.TINTA); setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    insignia(estadoVisibleEntrega(estado, c.conforme), tonoEntrega(estado, c.conforme), cabeza)
                    texto("${c.receptor} · ${c.pesoRecibido.toBigDecimal().stripTrailingZeros().toPlainString()} ${c.unidad} · ${c.evidencias.size} foto(s)", 13, Paleta.GRIS, false, 2, t)
                    texto("Capturada el ${FechasPantalla.capturada(c.creadaEn)}", 12, Paleta.GRIS, false, 12, t)
                }
            }
        }

        if (lista.isEmpty()) {
            val vacia = tarjeta(acento = Paleta.ARCILLA)
            texto("No hay reparto descargado", 17, Paleta.TINTA, true, 8, vacia)
            texto(
                "Descarga tus entregas con señal antes de salir. Sin descargar no hay nada que capturar en la calle.",
                14, Paleta.GRIS, false, 12, vacia
            )
        } else {
            etiqueta("Entregas descargadas (${lista.size})").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
        }

        val porEntrega = capturas.groupBy { it.entregaId }
        for (e in lista) {
            val ultimaCaptura = porEntrega[e.id]?.maxByOrNull { it.creadaEn }
            val estado = ultimaCaptura?.let { SincronizacionRecepcion.estadoPorNombre(it.estado) }
            val tono = tonoEntrega(estado, ultimaCaptura?.conforme)
            val t = tarjeta(acento = if (tono == Tono.NEUTRO) null else tono.texto)
            val cabeza = fila(t, 6)
            cabeza.addView(TextView(this).apply { text = e.pedido; textSize = 18f; setTextColor(Paleta.TINTA); setTypeface(Typeface.SERIF, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            insignia(estadoVisibleEntrega(estado, ultimaCaptura?.conforme), tono, cabeza)
            texto(e.cliente, 16, Paleta.TINTA_SUAVE, true, 4, t)
            if (e.direccion.isNotBlank()) texto(e.direccion, 14, Paleta.GRIS, false, 4, t)
            texto("Prevista: ${Formato.bolivia(e.fechaPrevista)}", 14, Paleta.GRIS, false, 8, t)
            if (e.productos.isNotBlank()) {
                val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fondo(Paleta.CREMA, 10, null); setPadding(dp(12), dp(10), dp(12), dp(4)) }
                texto(e.productos, 14, Paleta.TINTA_SUAVE, false, 6, caja)
                t.addView(caja, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            val acciones = fila(t, 4)
            Enlaces.telefono(e.telefono)?.let { tel -> enlace("Llamar ${e.telefono.trim()}", acciones) { abrir(Intent(Intent.ACTION_DIAL, Uri.parse(tel)), "No hay una app para llamar.") } }
            Enlaces.mapa(e.direccion)?.let { geo -> enlace("Ver en mapa", acciones) { abrir(Intent(Intent.ACTION_VIEW, Uri.parse(geo)), "No hay una app de mapas instalada.") } }
            ultimaCaptura?.motivo?.takeIf { estado == Conflicto }?.let { texto(it, 13, Paleta.PELIGRO, false, 8, t) }
            if (estado == Confirmada && ultimaCaptura.fechaSincronizacion != null) {
                texto("Confirmada por el servidor: ${Formato.bolivia(ultimaCaptura.fechaSincronizacion)}", 13, Paleta.GRIS, false, 8, t)
            }
            if (estado == null || estado == Descartada) boton("Registrar recepción", t) { capturar(s, Borrador(e)) }
        }

        // Finalizar ruta: el servidor no sabe qué quedó en el teléfono, así que aquí se bloquea mientras haya pendientes.
        for ((rutaId, entregasRuta) in lista.filter { it.rutaId != null }.groupBy { it.rutaId!! }) {
            val ids = entregasRuta.map { it.id }.toSet()
            val sinEnviarRuta = capturas.count { it.entregaId in ids && (it.estado == Pendiente.nombre || it.estado == Enviando.nombre) }
            val sinCaptura = entregasRuta.count { en -> porEntrega[en.id].isNullOrEmpty() }
            val motivo = when {
                sinEnviarRuta > 0 -> "No se puede finalizar: hay $sinEnviarRuta captura(s) de esta ruta sin enviar."
                sinCaptura > 0 -> "No se puede finalizar: faltan $sinCaptura entrega(s) por registrar."
                else -> null
            }
            val t = tarjeta(acento = if (motivo == null) Paleta.VERDE else Paleta.AVISO)
            titulo("Ruta con ${entregasRuta.size} entrega(s)", t)
            if (motivo != null) texto(motivo, 13, Paleta.AVISO, false, 10, t)
            else boton("Finalizar ruta", t) { finalizarRuta(s, rutaId) }
        }

        boton("Cerrar sesión", claro = true) { cerrarSesion(s, sinEnviar) }
    }

    private fun abrir(intento: Intent, sinApp: String) {
        try { startActivity(intento) } catch (e: Exception) { avisar(sinApp) }
    }

    private fun descargar(s: SesionGuardada) {
        alcance.launch {
            val servicio = runCatching { ConexionReparto.crear(s.url) }.getOrElse { avisar(it.message ?: "Dirección inválida."); return@launch }
            val r = try { servicio.misEntregas("Bearer ${s.token}") } catch (e: IOException) {
                avisar("Sin conexión. Para descargar entregas necesitas señal."); return@launch
            }
            if (r.code() == 401) { avisar("Tu sesión venció. Inicia sesión de nuevo."); acceso(true); return@launch }
            val cuerpoR = r.body()
            if (!r.isSuccessful || cuerpoR == null) { avisar(ConexionReparto.error(r)?.mensaje ?: "No se pudo descargar (${r.code()})."); return@launch }
            val productos = cuerpoR.detalles.groupBy { it.pedidoId }
            val locales = cuerpoR.entregas.map { e ->
                EntregaLocal(
                    id = e.id,
                    version = e.version,
                    pedido = e.pedido,
                    rutaId = e.rutaId,
                    cliente = e.cliente,
                    direccion = e.direccion.orEmpty(),
                    telefono = e.telefono.orEmpty(),
                    fechaPrevista = e.fechaPrevista,
                    productos = productos[e.pedidoId].orEmpty().joinToString("\n") { "• ${it.producto}: ${it.cantidadConfirmada.stripTrailingZeros().toPlainString()} ${it.unidad}" },
                    descargadaEn = System.currentTimeMillis()
                )
            }
            dao.reemplazarDescarga(locales)
            avisar(if (locales.isEmpty()) "No tienes entregas despachadas asignadas." else "Descargadas ${locales.size} entrega(s). Ya puedes capturar sin señal.")
            entregas(s)
        }
    }

    private fun sincronizar(s: SesionGuardada, silencioso: Boolean, aviso: TextView? = null) {
        if (sincronizando) return
        if (Conexion.vencido(s.expira)) {
            aviso?.mostrar("")
            if (!silencioso) { avisar("Tu sesión venció. Inicia sesión con conexión para enviar."); acceso(true) }
            return
        }
        sincronizando = true
        alcance.launch {
            val resumen = try {
                val cola = ColaRecepciones(pendientes, ClienteRecepcionRetrofit(ConexionReparto.crear(s.url), s.token)) { File(it).readBytes() }
                cola.sincronizar().also { cola.revisarConflictos() }
            } finally {
                sincronizando = false
            }
            if (resumen.confirmadas > 0 || resumen.conflictos > 0) {
                getSharedPreferences("reparto", MODE_PRIVATE).edit().putString("ultimoEnvio", java.time.Instant.now().toString()).apply()
            }
            if (!silencioso || resumen.confirmadas > 0 || resumen.conflictos > 0) {
                avisar(
                    when {
                        resumen.sesionVencida -> resumen.mensaje!!
                        resumen.mensaje != null -> "Sin conexión: ${resumen.pendientes} captura(s) siguen pendientes y se reintentarán."
                        resumen.confirmadas == 0 && resumen.conflictos == 0 -> "No había capturas por enviar."
                        else -> "Enviadas: ${resumen.confirmadas}. En conflicto: ${resumen.conflictos}. Pendientes: ${resumen.pendientes}."
                    }
                )
            }
            if (resumen.sesionVencida) acceso(true) else if (enPantallaEntregas) entregas(s)
        }
    }

    private fun finalizarRuta(s: SesionGuardada, rutaId: String) {
        AlertDialog.Builder(this).setTitle("Finalizar ruta")
            .setMessage("El servidor comprobará que todas las entregas estén registradas y sin conflictos.")
            .setNegativeButton("Volver", null)
            .setPositiveButton("Finalizar") { _, _ ->
                alcance.launch {
                    val r = try { ConexionReparto.crear(s.url).finalizarRuta("Bearer ${s.token}", rutaId) } catch (e: IOException) {
                        avisar("Sin conexión. Finalizar la ruta requiere señal."); return@launch
                    }
                    avisar(if (r.isSuccessful) "Ruta finalizada." else ConexionReparto.error(r)?.mensaje ?: "No se pudo finalizar (${r.code()}).")
                }
            }.show()
    }

    private fun cerrarSesion(s: SesionGuardada, sinEnviar: Int) {
        val mensaje = if (sinEnviar > 0) "Tienes $sinEnviar captura(s) sin enviar. Se conservarán en el teléfono y se enviarán cuando vuelvas a entrar con esta cuenta."
        else "Se cerrará el acceso en este dispositivo."
        AlertDialog.Builder(this).setTitle("Cerrar sesión").setMessage(mensaje).setNegativeButton("Volver", null)
            .setPositiveButton("Cerrar") { _, _ ->
                val c = Conexion.crear(s.url).salir("Bearer " + s.token); peticion = c
                c.enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        if (isDestroyed) return
                        if (response.isSuccessful || response.code() == 401) { almacen.borrar(); sesionActual = null; acceso() }
                        else avisar("No se pudo cerrar la sesión. Intenta nuevamente.")
                    }

                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        if (!isDestroyed && !call.isCanceled) avisar("Conéctate para cerrar también la sesión del servidor.")
                    }
                })
            }.show()
    }

    // ---------- Captura de la recepción, con o sin señal (RF-07, RNF-01) ----------

    private fun capturar(s: SesionGuardada, b: Borrador) {
        borrador = b
        pagina("Registrar recepción", "Funciona sin señal: queda pendiente hasta sincronizar.", formulario = true)
        val entrega = tarjeta(acento = Paleta.VERDE)
        texto(b.entrega.pedido, 17, Paleta.TINTA, true, 2, entrega)
        texto(b.entrega.cliente, 14, Paleta.GRIS, false, 8, entrega)

        val datos = tarjeta()
        val receptor = campo("Quién recibe", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, b.receptor, destino = datos, pista = "Nombre de la persona")
        val fecha = campo("Fecha y hora real (hora de Bolivia)", InputType.TYPE_CLASS_DATETIME, b.fecha, destino = datos, pista = "dd/mm/aaaa hh:mm")
        (fecha.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        val acciones = fila(datos, 14)
        enlace("Ahora", acciones) { fecha.setText(Formato.ahoraBolivia()) }
        enlace("Elegir fecha y hora", acciones) { elegirFecha(fecha) }
        val peso = campo("Peso recibido", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, b.peso, destino = datos, pista = "Ej. 12.5")

        var leerObservacion: () -> Unit = {}
        fun guardarBorrador() {
            b.receptor = receptor.text.toString()
            b.fecha = fecha.text.toString()
            b.peso = peso.text.toString()
            leerObservacion()
        }

        etiqueta("Unidad", datos)
        segmento(listOf("kg", "g"), if (b.unidad == "g") 1 else 0, datos) { guardarBorrador(); b.unidad = if (it == 1) "g" else "kg"; capturar(s, b) }

        etiqueta("Resultado de la recepción", datos)
        segmento(listOf("Conforme", "Con observaciones"), if (b.conforme) 0 else 1, datos) { guardarBorrador(); b.conforme = it == 0; capturar(s, b) }
        val observacion = campo(
            if (b.conforme) "Observaciones (opcional)" else "Observaciones (obligatorias)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            b.observacion, 3, datos, "Qué se observó al recibir"
        )
        leerObservacion = { b.observacion = observacion.text.toString() }
        val guardarTodo = { guardarBorrador() }

        val evidencia = tarjeta()
        val cabezaFotos = fila(evidencia, 4)
        cabezaFotos.addView(TextView(this).apply { text = "Evidencia"; textSize = 18f; setTextColor(Paleta.TINTA); setTypeface(Typeface.SERIF, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        insignia("${b.fotos.size} de 3", if (b.fotos.isEmpty()) Tono.AVISO else Tono.EXITO, cabezaFotos)
        texto("Las fotos se guardan solo dentro de la app, no en la galería. Toca una foto para quitarla.", 13, Paleta.GRIS, false, 10, evidencia)
        if (b.fotos.isNotEmpty()) {
            val carrusel = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val tira = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            carrusel.addView(tira)
            b.fotos.forEachIndexed { i, ruta ->
                tira.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(miniatura(ruta))
                    background = fondo(Paleta.CREMA_HONDA, 10, null)
                    clipToOutline = true
                    contentDescription = "Foto ${i + 1} de evidencia"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setMessage("¿Quitar la foto ${i + 1}?")
                            .setNegativeButton("Dejarla", null)
                            .setPositiveButton("Quitar") { _, _ -> guardarTodo(); File(b.fotos.removeAt(i)).delete(); capturar(s, b) }.show()
                    }
                }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(10) })
            }
            evidencia.addView(carrusel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        if (b.fotos.size < 3) boton(if (b.fotos.isEmpty()) "Tomar foto" else "Tomar otra foto", evidencia, claro = true) { guardarTodo(); tomarFoto(b) }

        val mensajeError = error()
        boton("Guardar recepción") {
            guardarTodo()
            val problema = validar(b)
            if (problema != null) { mensajeError.mostrar(problema); return@boton }
            guardarCaptura(s, b)
        }
        boton("Cancelar", claro = true) { guardarTodo(); confirmarDescarte(s, b) }
    }

    private fun confirmarDescarte(s: SesionGuardada, b: Borrador) {
        AlertDialog.Builder(this).setMessage("Se descartará lo escrito y las fotos de esta captura.")
            .setNegativeButton("Seguir registrando", null)
            .setPositiveButton("Descartar") { _, _ -> b.fotos.forEach { File(it).delete() }; borrador = null; olvidarBorrador(); entregas(s) }.show()
    }

    /**
     * «Atrás» en el formulario vuelve a la lista (preguntando antes de perder lo escrito) en lugar de cerrar la app.
     * Android 13 o más usa OnBackInvokedCallback; los anteriores reciben la tecla.
     */
    private var atrasRegistrado: Any? = null

    private fun atrasEnFormulario(activo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val actual = atrasRegistrado as? OnBackInvokedCallback
        if (activo && actual == null) {
            val nuevo = OnBackInvokedCallback { atras() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, nuevo)
            atrasRegistrado = nuevo
        } else if (!activo && actual != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(actual)
            atrasRegistrado = null
        }
    }

    private fun atras(): Boolean {
        val b = borrador ?: return false
        val s = sesionActual ?: return false
        confirmarDescarte(s, b)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && atras()) true
        else super.onKeyDown(keyCode, event)

    /** Selector de fecha y luego de hora (24 h), partiendo de lo escrito. No permite días futuros. */
    private fun elegirFecha(campoFecha: EditText) {
        val inicio = FechasPantalla.paraSelector(campoFecha.text.toString())
        DatePickerDialog(this, { _, anio, mes, dia ->
            TimePickerDialog(this, { _, hora, minuto ->
                campoFecha.setText(FechasPantalla.desdeSelector(anio, mes, dia, hora, minuto))
            }, inicio.hour, inicio.minute, true).show()
        }, inicio.year, inicio.monthValue - 1, inicio.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /** Miniatura liviana: las fotos ya están reducidas, pero no hace falta cargarlas enteras para verlas chicas. */
    private fun miniatura(ruta: String) = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = 8 })

    private fun validar(b: Borrador): String? {
        val fechaUtc = Formato.fechaUtc(b.fecha)
        return when {
            b.receptor.isBlank() -> "Escribe quién recibe."
            b.receptor.length > 180 -> "El nombre de quien recibe es demasiado largo."
            fechaUtc == null -> "Escribe la fecha como dd/mm/aaaa hh:mm o usa «Elegir fecha y hora»."
            java.time.Instant.parse(fechaUtc).isAfter(java.time.Instant.now().plusSeconds(60)) -> "La fecha real no puede ser futura."
            Formato.peso(b.peso) == null -> "Escribe un peso positivo con hasta tres decimales."
            !b.conforme && b.observacion.isBlank() -> "Si la recepción tiene observaciones, escríbelas."
            b.observacion.length > 1000 -> "La observación admite hasta 1000 caracteres."
            b.fotos.isEmpty() -> "Toma al menos una foto de evidencia."
            else -> null
        }
    }

    private fun guardarCaptura(s: SesionGuardada, b: Borrador) {
        val autor = s.usuarioId ?: run { avisar("Vuelve a iniciar sesión para registrar capturas."); return }
        val registro = RegistroEnvio(
            clave = UUID.randomUUID().toString(), // la clave única la genera el teléfono
            entregaId = b.entrega.id,
            versionBase = b.entrega.version,
            autorId = autor,
            fechaReal = Formato.fechaUtc(b.fecha)!!,
            pesoRecibido = Formato.peso(b.peso)!!,
            unidad = b.unidad,
            conforme = b.conforme,
            receptor = b.receptor.trim(),
            observacion = b.observacion.trim(),
            evidencias = b.fotos.toList()
        )
        alcance.launch {
            pendientes.guardar(registro)
            borrador = null
            olvidarBorrador()
            avisar("Recepción guardada en el teléfono. Queda pendiente hasta sincronizar.")
            entregas(s)
            sincronizar(s, silencioso = true)
        }
    }

    private fun tomarFoto(b: Borrador) {
        val archivo = Evidencias.nuevaFoto(this)
        fotoEnCurso = archivo
        guardarBorradorEnTelefono(b)
        val uri = FileProvider.getUriForFile(this, "$packageName.evidencias", archivo)
        val intento = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intento, PEDIR_FOTO)
        } catch (e: Exception) {
            avisar("No se encontró una aplicación de cámara.")
        }
    }

    @Deprecated("API de Activity usada sin AndroidX Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIR_FOTO) return
        // Si Android cerró la app mientras la cámara estaba abierta, el borrador y la foto se recuperan del teléfono.
        val guardado = leerBorradorDelTelefono()
        val archivo = fotoEnCurso ?: guardado?.fotoEnCurso?.let(::File) ?: return
        fotoEnCurso = null
        borradorRevisado = true
        alcance.launch {
            val b = borrador ?: guardado?.let { restaurar(it) } ?: return@launch
            val s = sesionActual ?: almacen.leer() ?: return@launch
            sesionActual = s
            if (resultCode != RESULT_OK) { archivo.delete(); capturar(s, b); return@launch }
            val lista = withContext(Dispatchers.IO) { archivo.exists() && Evidencias.reducir(archivo) }
            if (lista) b.fotos.add(archivo.path) else { archivo.delete(); avisar("No se pudo procesar la foto. Intenta de nuevo.") }
            guardarBorradorEnTelefono(b)
            capturar(s, b)
        }
    }

    // ---------- Utilidades ----------

    private fun avisar(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    private fun mensaje(response: Response<*>): String = runCatching {
        com.google.gson.Gson().fromJson(response.errorBody()?.string(), ErrorApi::class.java)?.mensaje
    }.getOrNull() ?: "No se pudo completar el acceso. Intenta nuevamente."

    private fun silencio() = object : Callback<Void> {
        override fun onResponse(call: Call<Void>, response: Response<Void>) {}
        override fun onFailure(call: Call<Void>, t: Throwable) {}
    }

    override fun onDestroy() {
        peticion?.cancel()
        alcance.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PEDIR_FOTO = 7
    }
}
