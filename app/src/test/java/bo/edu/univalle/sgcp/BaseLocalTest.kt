package bo.edu.univalle.sgcp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room real (SQLite en archivo): una captura sobrevive al cierre y reapertura de la app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseLocalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun abrir() = Room.databaseBuilder(context, BaseLocal::class.java, "prueba.db").allowMainThreadQueries().build()

    private val captura = RegistroEnvio(
        clave = "11111111-2222-3333-4444-555555555555",
        entregaId = "e1",
        versionBase = 3,
        autorId = "u1",
        fechaReal = "2026-11-14T15:00:00Z",
        pesoRecibido = "12.500",
        unidad = "kg",
        conforme = false,
        receptor = "Ana Rojas",
        observacion = "Faltó un kilo de costilla",
        evidencias = listOf("/data/evidencias/a.jpg", "/data/evidencias/b.jpg")
    )

    @Test fun laCapturaSobreviveAlCerrarYReabrir() = runTest {
        context.deleteDatabase("prueba.db")
        val primera = abrir()
        AlmacenPendientesRoom(primera.reparto()).guardar(captura)
        primera.close()

        val segunda = abrir()
        val leidas = AlmacenPendientesRoom(segunda.reparto()).listar()
        assertEquals(listOf(captura.copy(creadaEn = leidas.single().creadaEn)), leidas)
        assertEquals(1, segunda.reparto().sinEnviar())
        segunda.close()
    }

    @Test fun descargarNoBorraEntregasConCaptura() = runTest {
        context.deleteDatabase("prueba.db")
        val base = abrir()
        val dao = base.reparto()
        fun entrega(id: String) = EntregaLocal(id, 1, "PED-$id", null, "Cliente", "", "", "2026-11-14T12:00:00Z", "", 0)
        dao.reemplazarDescarga(listOf(entrega("e1"), entrega("e2")))
        AlmacenPendientesRoom(dao).guardar(captura) // captura sobre e1
        dao.reemplazarDescarga(emptyList()) // ya no hay asignadas en el servidor
        assertEquals(listOf("e1"), dao.entregas().map { it.id })
        base.close()
    }
}
