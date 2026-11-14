package bo.edu.univalle.sgcp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    // Borrador del formulario de captura: sobrevive mientras se toma una foto.
    private var borrador: Borrador? = null
    private var fotoEnCurso: File? = null

    private val verde = Color.rgb(40, 92, 73)
    private val tinta = Color.rgb(29, 48, 46)
    private val gris = Color.rgb(104, 119, 113)
    private val rojo = Color.rgb(168, 58, 56)
    private val ambar = Color.rgb(156, 104, 21)
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
        // Sincronización con la app abierta: al recuperar la red se envía lo pendiente.
        val gestor = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (enPantallaEntregas) sesionActual?.let { sincronizar(it, silencioso = true) } }
            }
        }
        runCatching { gestor.registerDefaultNetworkCallback(callback); vigilante = callback }
    }

    override fun onPause() {
        vigilante?.let { v -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(v) } }
        vigilante = null
        super.onPause()
    }

    // ---------- Componentes visuales ----------

    private fun fondo(color: Int, borde: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        if (borde) setStroke(dp(1), Color.rgb(204, 213, 201))
    }

    private fun pagina(titulo: String, subtitulo: String) {
        enPantallaEntregas = false
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(245, 244, 239)); isFillViewport = true }
        cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(25), dp(34), dp(25), dp(30)) }
        scroll.addView(cuerpo)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, barras.top, 0, barras.bottom)
            insets
        }
        setContentView(scroll)
        texto("SGCP  /  REPARTO", 13, verde, true, 28)
        texto(titulo, 30, tinta, true, 10)
        texto(subtitulo, 15, gris, false, 28)
    }

    private fun texto(valor: String, size: Int = 15, color: Int = tinta, bold: Boolean = false, abajo: Int = 14, destino: LinearLayout = cuerpo): TextView {
        val t = TextView(this).apply {
            text = valor
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        destino.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(abajo) })
        return t
    }

    private fun campo(etiqueta: String, tipo: Int, valor: String = "", lineas: Int = 1): EditText {
        texto(etiqueta, 13, tinta, true, 7)
        val e = EditText(this).apply {
            setText(valor)
            inputType = tipo
            textSize = 16f
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = fondo(Color.WHITE, true)
            if (lineas == 1) setSingleLine(true) else minLines = lineas
            contentDescription = etiqueta
        }
        cuerpo.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        return e
    }

    private fun boton(etiqueta: String, destino: LinearLayout = cuerpo, claro: Boolean = false, accion: () -> Unit): Button {
        val b = Button(this).apply {
            text = etiqueta
            isAllCaps = false
            setTextColor(if (claro) verde else Color.WHITE)
            textSize = 16f
            background = if (claro) fondo(Color.WHITE, true) else fondo(verde)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { accion() }
        }
        destino.addView(b, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(16) })
        return b
    }

    private fun tarjeta(): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            background = fondo(Color.WHITE, true)
        }
        cuerpo.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        return t
    }

    // ---------- Acceso (Sprint 1) ----------

    private fun acceso(renovar: Boolean = false) {
        pagina(
            if (renovar) "Renueva tu acceso" else "Acceder al reparto",
            if (renovar) "Tu sesión terminó. Conéctate e inicia sesión para continuar." else "Ingresa con la cuenta asignada por la administración."
        )
        val usuario = campo("Usuario", InputType.TYPE_CLASS_TEXT)
        val clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val preferencias = getSharedPreferences("conexion", MODE_PRIVATE)
        val url = campo(
            "Dirección del servidor",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            preferencias.getString("url", BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL
        )
        val error = texto("", 14, rojo)
        lateinit var entrar: Button
        entrar = boton("Ingresar") {
            if (usuario.text.isBlank() || clave.text.isBlank()) { error.text = "Completa usuario y contraseña."; return@boton }
            val direccion = runCatching { Conexion.validar(url.text.toString(), BuildConfig.DEBUG) }.getOrElse { error.text = it.message; return@boton }
            entrar.isEnabled = false; entrar.text = "Conectando…"; error.text = ""
            val llamada = Conexion.crear(direccion).acceder(AccesoPeticion(usuario.text.toString().trim(), clave.text.toString()))
            peticion = llamada
            llamada.enqueue(object : Callback<AccesoRespuesta> {
                override fun onResponse(call: Call<AccesoRespuesta>, response: Response<AccesoRespuesta>) {
                    if (isDestroyed) return
                    entrar.isEnabled = true; entrar.text = "Ingresar"
                    val r = response.body()
                    if (!response.isSuccessful || r == null) { error.text = mensaje(response); return }
                    if (!r.usuario.roles.any { it == "ENTREGA" || it == "ADMINISTRACION" }) {
                        Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio())
                        error.text = "Esta aplicación está destinada al personal de reparto."; return
                    }
                    val sesion = SesionGuardada(r.token, r.expira, r.usuario.nombre, direccion, r.usuario.id)
                    try {
                        almacen.guardar(sesion); preferencias.edit().putString("url", direccion).apply(); clave.text.clear(); entregas(sesion)
                    } catch (e: Exception) {
                        error.text = "No se pudo guardar el acceso en este dispositivo."
                        Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio())
                    }
                }

                override fun onFailure(call: Call<AccesoRespuesta>, t: Throwable) {
                    if (!isDestroyed && !call.isCanceled) {
                        entrar.isEnabled = true; entrar.text = "Ingresar"
                        error.text = "No se pudo conectar. Revisa internet y la dirección del servidor."
                    }
                }
            })
        }
        texto("El inicio de sesión requiere conexión. Usa la dirección compartida por el equipo.", 12, gris)
        // Sin señal y con la sesión vencida, igual se puede capturar lo ya descargado: la captura no necesita sesión, el envío sí.
        val guardada = almacen.leer()
        if (renovar && guardada?.usuarioId != null) {
            alcance.launch {
                if (dao.entregas().isNotEmpty()) {
                    texto("¿Estás sin señal? Puedes registrar recepciones de lo que ya descargaste. Se enviarán cuando vuelvas a iniciar sesión con conexión.", 13, gris, false, 10)
                    boton("Capturar sin conexión", claro = true) { entregas(guardada) }
                }
            }
        }
    }

    private fun verificar(s: SesionGuardada) {
        pagina("Comprobando acceso", "Estamos verificando tu sesión con el servidor.")
        val c = Conexion.crear(s.url).perfil("Bearer " + s.token); peticion = c
        c.enqueue(object : Callback<Perfil> {
            override fun onResponse(call: Call<Perfil>, response: Response<Perfil>) {
                if (isDestroyed) return
                val perfil = response.body()
                if (response.isSuccessful && perfil?.roles?.any { it == "ENTREGA" || it == "ADMINISTRACION" } == true) entregas(s)
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
            val lista = dao.entregas()
            val capturas = pendientes.todas()
            val sinEnviar = capturas.count { it.estado == Pendiente.nombre || it.estado == Enviando.nombre }
            val enConflicto = capturas.filter { it.estado == Conflicto.nombre }
            pintarEntregas(s, lista, capturas, sinEnviar, enConflicto)
        }
    }

    private fun pintarEntregas(s: SesionGuardada, lista: List<EntregaLocal>, capturas: List<RegistroEnvio>, sinEnviar: Int, enConflicto: List<RegistroEnvio>) {
        pagina("Mis entregas", "Hola, ${s.nombre}.")
        enPantallaEntregas = true
        val ultimo = getSharedPreferences("reparto", MODE_PRIVATE).getString("ultimoEnvio", null)

        val resumen = tarjeta()
        texto("Capturas sin enviar: $sinEnviar", 18, if (sinEnviar > 0) ambar else verde, true, 6, resumen)
        texto("Último envío: ${if (ultimo == null) "todavía no hubo" else Formato.bolivia(ultimo)}", 14, gris, false, 6, resumen)
        texto("En conflicto, esperando revisión: ${enConflicto.size}", 14, if (enConflicto.isEmpty()) gris else rojo, false, 10, resumen)
        if (sinEnviar > 0) {
            texto(
                "Lo que no se envió solo está en este teléfono. Si se borra la app o se pierde el teléfono, esas capturas se pierden. Sincroniza en cuanto tengas señal.",
                13, ambar, false, 12, resumen
            )
        }
        val estadoEnvio = texto(if (sincronizando) "Enviando…" else "", 13, gris, false, 6, resumen)
        boton("Sincronizar ahora", resumen) { sincronizar(s, silencioso = false, aviso = estadoEnvio) }

        boton("Descargar mis entregas", claro = true) { descargar(s) }

        if (lista.isEmpty()) {
            val vacia = tarjeta()
            texto("No hay reparto descargado", 17, tinta, true, 8, vacia)
            texto(
                "Descarga tus entregas con señal antes de salir. Sin descargar no hay nada que capturar en la calle.",
                14, gris, false, 12, vacia
            )
        }

        val porEntrega = capturas.groupBy { it.entregaId }
        for (e in lista) {
            val ultimaCaptura = porEntrega[e.id]?.maxByOrNull { it.creadaEn }
            val estado = ultimaCaptura?.let { SincronizacionRecepcion.estadoPorNombre(it.estado) }
            val etiqueta = estadoVisibleEntrega(estado, ultimaCaptura?.conforme)
            val color = when (estado) {
                null -> gris
                Confirmada -> if (ultimaCaptura.conforme) verde else ambar
                Conflicto -> rojo
                else -> ambar
            }
            val t = tarjeta()
            texto("${e.pedido} · ${e.cliente}", 17, tinta, true, 4, t)
            if (e.direccion.isNotBlank()) texto(e.direccion, 14, gris, false, 4, t)
            if (e.telefono.isNotBlank()) texto("Tel. ${e.telefono}", 14, gris, false, 4, t)
            texto("Fecha prevista: ${Formato.bolivia(e.fechaPrevista)}", 14, gris, false, 4, t)
            if (e.productos.isNotBlank()) texto(e.productos, 14, tinta, false, 8, t)
            texto(etiqueta, 15, color, true, 8, t)
            ultimaCaptura?.motivo?.takeIf { estado == Conflicto }?.let { texto(it, 13, rojo, false, 8, t) }
            if (estado == Confirmada && ultimaCaptura.fechaSincronizacion != null) {
                texto("Confirmada por el servidor: ${Formato.bolivia(ultimaCaptura.fechaSincronizacion)}", 13, gris, false, 8, t)
            }
            if (estado == null || estado == Descartada) boton("Registrar recepción", t) { capturar(s, Borrador(e)) }
        }

        // Finalizar ruta: el servidor no sabe qué quedó en el teléfono, así que aquí se bloquea mientras haya pendientes.
        for ((rutaId, entregasRuta) in lista.filter { it.rutaId != null }.groupBy { it.rutaId!! }) {
            val ids = entregasRuta.map { it.id }.toSet()
            val sinEnviarRuta = capturas.count { it.entregaId in ids && (it.estado == Pendiente.nombre || it.estado == Enviando.nombre) }
            val sinCaptura = entregasRuta.count { en -> porEntrega[en.id].isNullOrEmpty() }
            val t = tarjeta()
            texto("Ruta con ${entregasRuta.size} entrega(s)", 15, tinta, true, 6, t)
            val motivo = when {
                sinEnviarRuta > 0 -> "No se puede finalizar: hay $sinEnviarRuta captura(s) de esta ruta sin enviar."
                sinCaptura > 0 -> "No se puede finalizar: faltan $sinCaptura entrega(s) por registrar."
                else -> null
            }
            if (motivo != null) texto(motivo, 13, ambar, false, 10, t)
            else boton("Finalizar ruta", t) { finalizarRuta(s, rutaId) }
        }

        boton("Cerrar sesión", claro = true) { cerrarSesion(s, sinEnviar) }
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
            if (!silencioso) { avisar("Tu sesión venció. Inicia sesión con conexión para enviar."); acceso(true) }
            return
        }
        sincronizando = true
        aviso?.text = "Enviando…"
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
        pagina("Registrar recepción", "${b.entrega.pedido} · ${b.entrega.cliente}. Funciona sin señal: queda pendiente hasta sincronizar.")
        val receptor = campo("Quién recibe", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, b.receptor)
        val fecha = campo("Fecha y hora real de la entrega (dd/mm/aaaa hh:mm, hora de Bolivia)", InputType.TYPE_CLASS_DATETIME, b.fecha)
        val peso = campo("Peso recibido", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, b.peso)
        texto("Unidad", 13, tinta, true, 7)
        val unidades = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val kg = RadioButton(this).apply { text = "kg"; id = 1001; textSize = 16f }
        val g = RadioButton(this).apply { text = "g"; id = 1002; textSize = 16f }
        unidades.addView(kg); unidades.addView(g)
        unidades.check(if (b.unidad == "g") 1002 else 1001)
        cuerpo.addView(unidades, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val conforme = CheckBox(this).apply { text = "Recepción conforme, sin observaciones"; isChecked = b.conforme; textSize = 16f }
        cuerpo.addView(conforme, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val observacion = campo("Observaciones (obligatorias si no es conforme)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, b.observacion, 3)

        fun guardarBorrador() {
            b.receptor = receptor.text.toString()
            b.fecha = fecha.text.toString()
            b.peso = peso.text.toString()
            b.unidad = if (unidades.checkedRadioButtonId == 1002) "g" else "kg"
            b.conforme = conforme.isChecked
            b.observacion = observacion.text.toString()
        }

        texto("Evidencia: ${b.fotos.size} de 3 foto(s)", 13, tinta, true, 7)
        texto("Las fotos se guardan solo dentro de la app, no en la galería.", 12, gris, false, 10)
        if (b.fotos.size < 3) boton("Tomar foto", claro = true) { guardarBorrador(); tomarFoto() }
        if (b.fotos.isNotEmpty()) boton("Quitar la última foto", claro = true) {
            guardarBorrador(); File(b.fotos.removeAt(b.fotos.lastIndex)).delete(); capturar(s, b)
        }

        val error = texto("", 14, rojo)
        boton("Guardar recepción") {
            guardarBorrador()
            val problema = validar(b)
            if (problema != null) { error.text = problema; return@boton }
            guardarCaptura(s, b)
        }
        boton("Cancelar", claro = true) {
            AlertDialog.Builder(this).setMessage("Se descartará lo escrito y las fotos de esta captura.")
                .setNegativeButton("Seguir", null)
                .setPositiveButton("Descartar") { _, _ -> b.fotos.forEach { File(it).delete() }; borrador = null; entregas(s) }.show()
        }
    }

    private fun validar(b: Borrador): String? {
        val fechaUtc = Formato.fechaUtc(b.fecha)
        return when {
            b.receptor.isBlank() -> "Escribe quién recibe."
            b.receptor.length > 180 -> "El nombre de quien recibe es demasiado largo."
            fechaUtc == null -> "Escribe la fecha como dd/mm/aaaa hh:mm."
            java.time.Instant.parse(fechaUtc).isAfter(java.time.Instant.now().plusSeconds(60)) -> "La fecha real no puede ser futura."
            Formato.peso(b.peso) == null -> "Escribe un peso positivo con hasta tres decimales."
            !b.conforme && b.observacion.isBlank() -> "Si la recepción no es conforme, escribe la observación."
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
            avisar("Recepción guardada en el teléfono. Queda pendiente hasta sincronizar.")
            entregas(s)
            sincronizar(s, silencioso = true)
        }
    }

    private fun tomarFoto() {
        val archivo = Evidencias.nuevaFoto(this)
        fotoEnCurso = archivo
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
        val archivo = fotoEnCurso ?: return
        val b = borrador ?: return
        val s = sesionActual ?: almacen.leer() ?: return
        fotoEnCurso = null
        if (resultCode != RESULT_OK) { archivo.delete(); capturar(s, b); return }
        alcance.launch {
            val lista = withContext(Dispatchers.IO) { archivo.exists() && Evidencias.reducir(archivo) }
            if (lista) b.fotos.add(archivo.path) else { archivo.delete(); avisar("No se pudo procesar la foto. Intenta de nuevo.") }
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
