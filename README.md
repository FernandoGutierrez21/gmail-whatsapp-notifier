# 📧➡️💬 NotificarMailPorWpp

Programa en Java que vigila tu bandeja de **Gmail** en tiempo real y te envía un **WhatsApp** cuando llega un correo cuyo **asunto** cumple con las reglas que tú defines.

Ejemplo: avisarme solo cuando llegue un correo con **"Flash Sale"** y **"jeans"** en el asunto.

```
🚨Flash Sale🚨Solo hoy 2x$899 en jeans   →   💬 WhatsApp a tu celular
```

---

## ✨ Características

- **Tiempo real**: usa IMAP IDLE, así que Gmail avisa al programa en cuanto llega un correo, sin revisar cada X segundos.
- **Reglas flexibles**: cada regla es un grupo de frases que deben aparecer **todas** en el asunto; si se cumple **cualquier** regla, se envía el aviso.
- **Sin importar mayúsculas ni acentos**: `"facturacion"` coincide con `"Facturación"`.
- **Modo de ejecución configurable**: terminar tras el primer aviso o seguir escuchando indefinidamente.
- **Control de errores robusto**:
  - Timeouts en las llamadas a CallMeBot para que nunca se quede trabado.
  - Envío de WhatsApp en un hilo aparte: si CallMeBot tarda, Gmail se sigue escuchando.
  - Reintentos automáticos (3 intentos con espera progresiva).
  - Detecta errores de configuración (apikey inválida, contraseña incorrecta) y termina con un mensaje claro en vez de reintentar sin fin.
  - Reconexión automática a Gmail con espera creciente (30 s → 5 min máx.).
  - Keep-alive cada 9 minutos para que Gmail no corte la sesión.
- Buzón abierto en **solo lectura**: no marca correos como leídos ni los modifica.
- Envío de WhatsApp gratuito mediante [CallMeBot](https://www.callmebot.com/).

---

## 🧰 Requisitos

- **Java 21** (funciona desde Java 17)
- **Maven** (o un IDE que lo incluya, como IntelliJ IDEA)
- Una cuenta de **Gmail** con verificación en 2 pasos activada
- **WhatsApp** en tu celular

---

## 📁 Estructura del proyecto

```
NotificarMailPorWpp/
├── pom.xml
├── README.md
├── .gitignore
└── src/
    └── main/
        └── java/
            └── notificador/
                └── GmailWppNotifier.java
```

---

## ⚙️ Configuración

### 1. Contraseña de aplicación de Gmail

El programa **no** usa tu contraseña normal de Gmail.

1. Ve a [myaccount.google.com](https://myaccount.google.com) → **Seguridad**.
2. Activa la **Verificación en 2 pasos** (si no la tienes).
3. Busca **"Contraseñas de aplicaciones"** y crea una (ej. `Notificador`).
4. Copia el código de 16 letras **sin espacios**.

> En cuentas personales de Gmail, IMAP ya viene siempre activado. En cuentas de trabajo o escuela (Google Workspace), el administrador debe tenerlo habilitado.

### 2. API key de CallMeBot (WhatsApp)

1. Entra a [callmebot.com](https://www.callmebot.com/) → sección **WhatsApp**.
2. Agrega a tus contactos el número de CallMeBot que aparece en la página.
3. Desde **tu** WhatsApp, envíale el mensaje de autorización que indica la página (ej. `I allow callmebot to send me messages`).
4. Te responderán con tu **apikey** (solo números).

Pruébala en el navegador:

```
https://api.callmebot.com/whatsapp.php?phone=+521XXXXXXXXXX&text=prueba&apikey=TU_APIKEY
```

Si ves `Message queued`, funciona.

> 🇲🇽 **Números de México:** usa el formato `+521` + 10 dígitos (ej. `+5218112345678`). Si no funciona, prueba con `+52` + 10 dígitos.

### 3. Variables de entorno

El programa lee sus credenciales de variables de entorno (nunca van escritas en el código):

| Variable | Descripción | Ejemplo |
|---|---|---|
| `GMAIL_USER` | Tu correo de Gmail | `tu.correo@gmail.com` |
| `GMAIL_APP_PASSWORD` | Contraseña de aplicación (16 letras, sin espacios) | `abcdwxyzefghijkl` |
| `WPP_PHONE` | Tu número de WhatsApp con lada | `+5218112345678` |
| `CALLMEBOT_APIKEY` | API key de CallMeBot | `1234567` |

**En IntelliJ IDEA:**

1. Junto al botón ▶️ → menú desplegable → **Edit Configurations...**
2. Selecciona `GmailWppNotifier`.
3. En **Environment variables** (si no aparece: **Modify options** → **Environment variables**) pega, en una sola línea:

```
GMAIL_USER=tu.correo@gmail.com;GMAIL_APP_PASSWORD=abcdwxyzefghijkl;WPP_PHONE=+5218112345678;CALLMEBOT_APIKEY=1234567
```

4. **Apply** → **OK**.

**En Windows (PowerShell):**

```powershell
$env:GMAIL_USER="tu.correo@gmail.com"
$env:GMAIL_APP_PASSWORD="abcdwxyzefghijkl"
$env:WPP_PHONE="+5218112345678"
$env:CALLMEBOT_APIKEY="1234567"
```

**En Linux / macOS:**

```bash
export GMAIL_USER="tu.correo@gmail.com"
export GMAIL_APP_PASSWORD="abcdwxyzefghijkl"
export WPP_PHONE="+5218112345678"
export CALLMEBOT_APIKEY="1234567"
```

> ⚠️ **Nunca subas tus credenciales al repositorio.** Si alguna vez quedaron expuestas, revoca la contraseña de aplicación en Google y solicita una apikey nueva a CallMeBot.

### 4. Reglas y modo de ejecución

En `GmailWppNotifier.java`, sección **Configuración**:

```java
// Cada regla = frases que deben aparecer TODAS en el asunto.
// Basta con que se cumpla UNA regla para enviar el aviso.
private static final List<List<String>> REGLAS = List.of(
        List.of("flash sale", "jeans"),
        List.of("factura", "pago"),
        List.of("urgente")               // una sola palabra también vale
);

// true  = termina después de enviar el primer WhatsApp
// false = sigue escuchando siempre
private static final boolean TERMINAR_AL_ENVIAR = true;
```

| Asunto | ¿Avisa? | Motivo |
|---|---|---|
| `🚨Flash Sale🚨Solo hoy 2x$899 en jeans` | ✅ | contiene "flash sale" y "jeans" |
| `FLASH SALE en Jeans y camisas` | ✅ | no importan mayúsculas |
| `Flash Sale en camisas` | ❌ | falta "jeans" |
| `Nuevos jeans de temporada` | ❌ | falta "flash sale" |

### 5. Ajustes avanzados (opcionales)

También en la sección **Configuración**:

| Constante | Valor por defecto | Para qué sirve |
|---|---|---|
| `INTENTOS_WHATSAPP` | `3` | Intentos de envío a CallMeBot antes de rendirse |
| `ESPERA_ENTRE_INTENTOS_SEG` | `{5, 15, 30}` | Espera entre reintentos |
| `TIMEOUT_CONEXION_HTTP` | `10 s` | Tiempo máximo para conectar con CallMeBot |
| `TIMEOUT_RESPUESTA_HTTP` | `20 s` | Tiempo máximo esperando respuesta de CallMeBot |
| `MAX_LARGO_MENSAJE` | `600` | Recorta el WhatsApp si el asunto es muy largo |
| `ESPERA_BASE_RECONEXION_SEG` | `30` | Espera inicial para reconectar a Gmail (crece con cada fallo) |
| `ESPERA_MAX_RECONEXION_SEG` | `300` | Espera máxima entre reconexiones |

---

## ▶️ Ejecución

Desde la raíz del proyecto (donde está el `pom.xml`):

```bash
mvn compile exec:java
```

O en IntelliJ, con el botón ▶️ en `GmailWppNotifier`.

Salida esperada:

```
[2026-10-12T17:41:50] Iniciando. Reglas activas: [[flash sale, jeans]]
[2026-10-12T17:41:52] Conectado a tu.correo@gmail.com. Esperando correos nuevos...
[2026-10-12T17:42:52] Coincidencia: "🚨Flash Sale🚨Solo hoy 2x$899 en jeans" → enviando WhatsApp
[2026-10-12T17:42:53] ✅ WhatsApp enviado (intento 1): Message to: +521... Message queued. You will receive it in a few seconds.
[2026-10-12T17:42:53] Programa terminado.
```

Ejemplo cuando CallMeBot falla temporalmente:

```
[...] ⚠️ CallMeBot no respondió a tiempo (intento 1).
[...]    Reintentando en 5 s...
[...] ✅ WhatsApp enviado (intento 2): ... Message queued ...
```

> El programa debe **quedarse corriendo** mientras quieras recibir avisos. Solo detecta correos que lleguen **después** de iniciarlo.

---

## 📦 Generar un `.jar` para dejarlo corriendo 24/7

Para que funcione sin depender de tu PC, puedes ejecutarlo en una Raspberry Pi, una PC que esté siempre encendida o un servidor en la nube (VPS). Usa `TERMINAR_AL_ENVIAR = false` para que siga escuchando.

Agrega el plugin `maven-shade-plugin` al `pom.xml` para empaquetar las dependencias y luego:

```bash
mvn clean package
java -jar target/gmail-whatsapp-notifier-1.0.jar
```

---

## 🛠️ Solución de problemas

| Mensaje / problema | Causa y solución |
|---|---|
| `import jakarta...` en rojo en IntelliJ | Dependencias sin descargar. Clic derecho en `pom.xml` → **Maven** → **Reload project** |
| `Falta la variable de entorno GMAIL_USER` (u otra) | No está configurada en **Edit Configurations → Environment variables**, o el nombre está mal escrito |
| `❌ Gmail rechazó el acceso` | Usa la **contraseña de aplicación**, no tu contraseña de Gmail, y sin espacios |
| `⚠️ Problema con la conexión a Gmail ... Reintentando` | Internet caído o Gmail cerró la sesión. Se reconecta solo |
| `❌ CallMeBot rechazó la apikey o el número` | La apikey no corresponde al número. Prueba `+521` / `+52` o solicita una apikey nueva |
| `⚠️ CallMeBot no respondió a tiempo` | CallMeBot saturado. Se reintenta automáticamente |
| `⚠️ No se pudo enviar el WhatsApp tras 3 intentos` | CallMeBot caído. El programa sigue escuchando; el siguiente correo que coincida se volverá a intentar |
| Dice `✅ WhatsApp enviado` pero no llega | CallMeBot puso el mensaje en cola; puede tardar unos minutos en horas de alta demanda |

---

## 🔒 `.gitignore` recomendado

```gitignore
target/
.idea/
*.iml
.env
```

---

## 🧱 Tecnologías

- Java 21
- [Jakarta Mail](https://jakarta.ee/specifications/mail/) + [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) (IMAP)
- `java.net.http.HttpClient`
- `java.util.concurrent` (envío asíncrono y control de estado entre hilos)
- [CallMeBot WhatsApp API](https://www.callmebot.com/)
- Maven

---

## 📝 Notas

- CallMeBot es un servicio gratuito pensado para **uso personal** (enviarte mensajes a ti mismo). Para uso comercial, considera [Twilio](https://www.twilio.com/whatsapp) o la [WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api) de Meta.
- Los correos que lleguen mientras el programa está desconectado (sin internet o reconectando) no se procesan.
