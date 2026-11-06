package bo.edu.univalle.sgcp
import org.junit.Assert.*
import org.junit.Test
class ConexionTest {
    @Test fun normalizaLaRuta(){assertEquals("https://servidor.example/api/v1/",Conexion.validar("https://servidor.example/api/v1",false))}
    @Test(expected=IllegalArgumentException::class) fun produccionRechazaHttp(){Conexion.validar("http://servidor.example/api/v1/",false)}
    @Test(expected=IllegalArgumentException::class) fun rechazaCredencialesEnUrl(){Conexion.validar("https://user:pass@servidor.example/api/v1/",false)}
    @Test fun detectaVencimiento(){assertTrue(Conexion.vencido("2020-01-01T00:00:00Z"));assertTrue(Conexion.vencido("incorrecta"));assertFalse(Conexion.vencido("2099-01-01T00:00:00Z"))}
}
