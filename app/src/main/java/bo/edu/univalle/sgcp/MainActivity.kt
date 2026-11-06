package bo.edu.univalle.sgcp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : Activity() {
    private lateinit var almacen: AlmacenSesion
    private lateinit var cuerpo: LinearLayout
    private var peticion: Call<*>? = null
    private val verde = Color.rgb(40, 92, 73)
    private val tinta = Color.rgb(29, 48, 46)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        almacen = AlmacenSesion(this)
        val sesion = almacen.leer()
        if (sesion == null) acceso() else if (Conexion.vencido(sesion.expira)) acceso(true) else verificar(sesion)
    }
    private fun fondo(color: Int, borde: Boolean = false) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat(); if(borde) setStroke(dp(1), Color.rgb(204,
            213,
            201)) }
    private fun pagina(titulo: String, subtitulo: String) {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(245,244,239)); isFillViewport = true }
        cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(25),dp(34),dp(25),dp(30)) }
        scroll.addView(cuerpo)
        scroll.setOnApplyWindowInsetsListener { v, insets -> v.setPadding(0,
                insets.systemWindowInsetTop,
                0,
                insets.systemWindowInsetBottom); insets }
        setContentView(scroll)
        texto("SGCP  /  REPARTO", 13, verde, true, 28)
        texto(titulo, 30, tinta, true, 10)
        texto(subtitulo, 15, Color.rgb(104,119,113), false, 28)
    }
    private fun texto(valor: String, size: Int = 15, color: Int = tinta, bold: Boolean = false, abajo: Int = 14): TextView {
        val t = TextView(this).apply { text = valor; textSize = size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface, Typeface.BOLD) }
        cuerpo.addView(t, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(abajo) }); return t
    }
    private fun campo(etiqueta: String, tipo: Int, valor: String = ""): EditText {
        texto(etiqueta, 13, tinta, true, 7)
        val e = EditText(this).apply { setText(valor); inputType = tipo; textSize = 16f; setPadding(dp(13),
                dp(12),
                dp(13),
                dp(12)); background = fondo(Color.WHITE,
                true); setSingleLine(true); contentDescription = etiqueta }
        cuerpo.addView(e, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(20) });return e
    }
    private fun boton(etiqueta: String, accion: () -> Unit): Button {
        val b = Button(this).apply { text = etiqueta; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f; background = fondo(verde); setPadding(dp(12),
                dp(12),
                dp(12),
                dp(12)); setOnClickListener { accion() } }
        cuerpo.addView(b, LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin = dp(16) });return b
    }
    private fun acceso(renovar: Boolean = false) {
        pagina(if(renovar) "Renueva tu acceso" else "Acceder al reparto", if(renovar) "Tu sesión terminó. Conéctate e inicia sesión para continuar." else "Ingresa con la cuenta asignada por la administración.")
        val usuario = campo("Usuario", InputType.TYPE_CLASS_TEXT)
        val clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val preferencias = getSharedPreferences("conexion", MODE_PRIVATE)
        val url = campo("Dirección del servidor",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                preferencias.getString("url", BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL)
        val error = texto("", 14, Color.rgb(168,58,56))
        lateinit var entrar: Button
        entrar = boton("Ingresar") {
            if(usuario.text.isBlank() || clave.text.isBlank()) { error.text = "Completa usuario y contraseña."; return@boton }
            val direccion = runCatching { Conexion.validar(url.text.toString(), BuildConfig.DEBUG) }.getOrElse { error.text = it.message; return@boton }
            entrar.isEnabled = false; entrar.text = "Conectando…"; error.text = ""
            val llamada = Conexion.crear(direccion).acceder(AccesoPeticion(usuario.text.toString().trim(), clave.text.toString()))
            peticion = llamada
            llamada.enqueue(object : Callback<AccesoRespuesta> {
                override fun onResponse(call: Call<AccesoRespuesta>, response: Response<AccesoRespuesta>) {
                    if(isDestroyed) return
                    entrar.isEnabled = true; entrar.text = "Ingresar"
                    val r = response.body()
                    if(!response.isSuccessful || r == null) { error.text = mensaje(response); return }
                    if(!r.usuario.roles.any { it == "ENTREGA" || it == "ADMINISTRACION" }) {
                        Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio())
                        error.text = "Esta aplicación está destinada al personal de reparto.";return
                    }
                    val sesion = SesionGuardada(r.token,r.expira,r.usuario.nombre,direccion)
                    try { almacen.guardar(sesion); preferencias.edit().putString("url",direccion).apply(); clave.text.clear(); entregas(sesion) }
                    catch(e: Exception) { error.text = "No se pudo guardar el acceso en este dispositivo."; Conexion.crear(direccion).salir("Bearer " + r.token).enqueue(silencio()) }
                }
                override fun onFailure(call: Call<AccesoRespuesta>, t: Throwable) { if(!isDestroyed 
                                && !call.isCanceled){entrar.isEnabled=true;entrar.text="Ingresar";error.text="No se pudo conectar. Revisa internet y la dirección del servidor."} }
            })
        }
        texto("El inicio de sesión requiere conexión. Usa la dirección compartida por el equipo.", 12, Color.rgb(104,119,113))
    }
    private fun verificar(s: SesionGuardada) {
        pagina("Comprobando acceso", "Estamos verificando tu sesión con el servidor.")
        val c = Conexion.crear(s.url).perfil("Bearer " + s.token); peticion = c
        c.enqueue(object : Callback<Perfil> {
            override fun onResponse(call: Call<Perfil>, response: Response<Perfil>) {
                if(isDestroyed) return
                if(response.isSuccessful && response.body()?.roles?.any{it=="ENTREGA"||it=="ADMINISTRACION"} == true) entregas(s)
                else { almacen.borrar(); acceso(true) }
            }
            override fun onFailure(call: Call<Perfil>, t: Throwable) { if(!isDestroyed
                            &&!call.isCanceled){pagina("No hay conexión", "Conéctate para verificar tu acceso. Esta versión todavía no descarga entregas.");boton("Reintentar"){verificar(s)};boton("Volver al acceso"){acceso(true)}} }
        })
    }
    private fun entregas(s: SesionGuardada) {
        pagina("Mis entregas", "Hola, ${s.nombre}. Tu cuenta está conectada al reparto.")
        val panel = TextView(this).apply { text="No hay reparto descargado\n\nLa descarga de entregas y el registro de recepciones estarán disponibles en la siguiente etapa de reparto."; textSize=16f; setTextColor(tinta); setPadding(dp(24),
                dp(30),
                dp(24),
                dp(30)); background=fondo(Color.WHITE,
                true) }
        cuerpo.addView(panel,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(30)})
        boton("Verificar conexión"){verificar(s)}
        boton("Cerrar sesión") {
            AlertDialog.Builder(this).setTitle("Cerrar sesión").setMessage("Se cerrará el acceso en este dispositivo.").setNegativeButton("Volver",
                    null).setPositiveButton("Cerrar") { _,
                    _ ->
                val c=Conexion.crear(s.url).salir("Bearer " + s.token);peticion=c
                c.enqueue(object:Callback<Void>{
                    override fun onResponse(call:Call<Void>,response:Response<Void>){if(isDestroyed)return;if(response.isSuccessful
                        ||response.code()==401){almacen.borrar();acceso()}else Toast.makeText(this@MainActivity,
                            "No se pudo cerrar la sesión. Intenta nuevamente.",
                            Toast.LENGTH_LONG).show()}
                    override fun onFailure(call:Call<Void>,t:Throwable){if(!isDestroyed&&!call.isCanceled)Toast.makeText(this@MainActivity,
                            "Conéctate para cerrar también la sesión del servidor.",
                            Toast.LENGTH_LONG).show()}
                })
            }.show()
        }
    }
    private fun mensaje(response:Response<*>):String = runCatching { Gson().fromJson(response.errorBody()?.string(),ErrorApi::class.java)?.mensaje }.getOrNull() ?: "No se pudo completar el acceso. Intenta nuevamente."
    private fun silencio()=object:Callback<Void>{override fun onResponse(call:Call<Void>,
            response:Response<Void>){};override fun onFailure(call:Call<Void>,
            t:Throwable){}}
    override fun onDestroy(){peticion?.cancel();super.onDestroy()}
}
