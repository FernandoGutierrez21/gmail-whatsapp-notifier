package notificador;

import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Escucha tu bandeja de Gmail en tiempo real (IMAP IDLE) y, cuando llega un correo
 * cuyo ASUNTO cumple alguna de las REGLAS, te manda un WhatsApp vía CallMeBot.
 *
 * Variables de entorno necesarias:
 *   GMAIL_USER           tu.correo@gmail.com
 *   GMAIL_APP_PASSWORD   contraseña de aplicación de 16 caracteres (no tu contraseña normal)
 *   WPP_PHONE            tu número con lada internacional, ej. +5218112345678
 *   CALLMEBOT_APIKEY     la apikey que te da CallMeBot
 */
public class GmailWppNotifier {

    // ───────────────────────── Configuración ─────────────────────────

    /** Cada regla = frases que deben aparecer TODAS en el asunto. Basta con que se cumpla UNA regla. */
    private static final List<List<String>> REGLAS = List.of(
            List.of("flash sale", "jeans")
    );

    /** true = el programa termina después de enviar el primer WhatsApp. false = sigue escuchando siempre. */
    private static final boolean TERMINAR_AL_ENVIAR = true;

    private static final Properties CONFIG = cargarConfig();

    private static final String GMAIL_USER = CONFIG.getProperty("GMAIL_USER");
    private static final String GMAIL_APP_PASSWORD = CONFIG.getProperty("GMAIL_APP_PASSWORD");
    private static final String WPP_PHONE = CONFIG.getProperty("WPP_PHONE");
    private static final String CALLMEBOT_APIKEY = CONFIG.getProperty("CALLMEBOT_APIKEY");
    private static boolean correoCorrecto = false;

    // Ajustes de envío a CallMeBot
    private static final int INTENTOS_WHATSAPP = 3;
    private static final long[] ESPERA_ENTRE_INTENTOS_SEG = {5, 15, 30};
    private static final Duration TIMEOUT_CONEXION_HTTP = Duration.ofSeconds(10);
    private static final Duration TIMEOUT_RESPUESTA_HTTP = Duration.ofSeconds(20);
    private static final int MAX_LARGO_MENSAJE = 600;

    // Ajustes de reconexión a Gmail
    private static final long ESPERA_BASE_RECONEXION_SEG = 30;
    private static final long ESPERA_MAX_RECONEXION_SEG = 300;

    // ───────────────────────── Estado interno ─────────────────────────

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT_CONEXION_HTTP)
            .build();

    /** Hilo aparte para enviar WhatsApps: si CallMeBot se traba, no bloquea la escucha de Gmail. */
    private static final ExecutorService ENVIADOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "enviador-whatsapp");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean TERMINAR = new AtomicBoolean(false);
    private static final AtomicInteger FALLOS_SEGUIDOS = new AtomicInteger(0);

    private enum ResultadoEnvio { ENVIADO, ERROR_PERMANENTE, FALLIDO }

    /** Lee config.properties de la carpeta donde se ejecuta el programa, si existe. */
    private static Properties cargarConfig() {
        Properties p = new Properties();
        Path archivo = Path.of("config.properties");
        if (Files.exists(archivo)) {
            try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer config.properties: " + e.getMessage(), e);
            }
        }
        return p;
    }

    // ───────────────────────── Programa principal ─────────────────────────

    public static void main(String[] args) {
        log("Iniciando. Reglas activas: " + REGLAS);

        while (!TERMINAR.get()) {
            try {
                escucharBandeja();
                if (!TERMINAR.get()) {
                    log("La conexión con Gmail se cerró. Reconectando en 5 s...");
                    dormir(5_000);
                }
            } catch (AuthenticationFailedException e) {
                log("❌ Gmail rechazó el acceso: " + e.getMessage());
                log("   Revisa GMAIL_USER y GMAIL_APP_PASSWORD (debe ser contraseña de aplicación). Terminando.");
                TERMINAR.set(true);
            } catch (Exception e) {
                int fallos = FALLOS_SEGUIDOS.incrementAndGet();
                long espera = Math.min(ESPERA_BASE_RECONEXION_SEG * fallos, ESPERA_MAX_RECONEXION_SEG);
                log("⚠️ Problema con la conexión a Gmail (" + describir(e) + "). Reintentando en " + espera + " s...");
                dormir(espera * 1000);
            }
        }

        // Deja que termine un envío que esté en curso
        ENVIADOR.shutdown();
        try {
            if (!ENVIADOR.awaitTermination(2, TimeUnit.MINUTES)) {
                ENVIADOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("Programa terminado.");
    }

    // ───────────────────────── Gmail ─────────────────────────

    private static void escucharBandeja() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "20000"); // no quedarse colgado al conectar
        props.put("mail.imaps.writetimeout", "20000");

        Store store = Session.getInstance(props).getStore("imaps");
        Thread keepAlive = null;

        try {
            store.connect("imap.gmail.com", GMAIL_USER, GMAIL_APP_PASSWORD);

            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            FALLOS_SEGUIDOS.set(0);
            log("Conectado a " + GMAIL_USER + ". Esperando correos nuevos...");

            inbox.addMessageCountListener(new MessageCountAdapter() {
                @Override
                public void messagesAdded(MessageCountEvent e) {
                    for (Message m : e.getMessages()) {
                        if (TERMINAR.get()) return;
                        try {
                            String texto = revisarCorreo(m);
                            if (texto != null) {
                                programarEnvio(texto, inbox);
                            }
                        } catch (Exception ex) {
                            log("⚠️ No se pudo leer un correo: " + describir(ex));
                        }
                    }
                }
            });

            // Gmail corta las sesiones IDLE inactivas; un NOOP cada 9 min las mantiene vivas
            keepAlive = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(9 * 60 * 1000);
                        inbox.doCommand(p -> {
                            p.simpleCommand("NOOP", null);
                            return null;
                        });
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Exception ex) {
                        log("⚠️ Keep-alive falló: " + describir(ex));
                    }
                }
            }, "keep-alive");
            keepAlive.setDaemon(true);
            keepAlive.start();

            // idle() bloquea hasta que llega algo; despertar() lo interrumpe para revisar TERMINAR
            while (inbox.isOpen() && !TERMINAR.get()) {
                inbox.idle();
            }
        } finally {
            if (keepAlive != null) keepAlive.interrupt();
            try { store.close(); } catch (Exception ignored) { }
        }
    }

    /** Devuelve el texto del WhatsApp si el asunto cumple una regla, o null si no. */
    private static String revisarCorreo(Message m) throws MessagingException {
        String asunto = m.getSubject();
        if (asunto == null) return null;

        String asuntoNorm = normalizar(asunto);

        List<String> reglaCumplida = REGLAS.stream()
                .filter(regla -> regla.stream()
                        .allMatch(frase -> asuntoNorm.contains(normalizar(frase))))
                .findFirst()
                .orElse(null);

        if (reglaCumplida == null) return null;

        Address[] from = m.getFrom();
        String remitente = (from != null && from.length > 0) ? from[0].toString() : "desconocido";

        log("Coincidencia: \"" + asunto + "\" → enviando WhatsApp");

        String texto = "📧 Nuevo correo importante\n"
                + "De: " + remitente + "\n"
                + "Asunto: " + asunto + "\n"
                + "(coincidió: " + String.join(" + ", reglaCumplida) + ")";

        // Asuntos muy largos hacen la URL enorme y CallMeBot puede rechazarla
        if (texto.length() > MAX_LARGO_MENSAJE) {
            texto = texto.substring(0, MAX_LARGO_MENSAJE - 1) + "…";
        }
        return texto;
    }

    /** Despierta el idle() para que el bucle principal revise si debe terminar. */
    private static void despertar(IMAPFolder inbox) {
        try {
            inbox.doCommand(p -> {
                p.simpleCommand("NOOP", null);
                return null;
            });
        } catch (Exception ignored) {
            // Si la carpeta ya se cerró, no importa: el bucle terminará solo
        }
    }

    // ───────────────────────── WhatsApp (CallMeBot) ─────────────────────────

    private static void programarEnvio(String texto, IMAPFolder inbox) {
        ENVIADOR.submit(() -> {
            try {
                if (TERMINAR.get()) return;

                switch (enviarConReintentos(texto)) {
                    case ENVIADO -> {
                        if (TERMINAR_AL_ENVIAR) {
                            TERMINAR.set(true);
                            despertar(inbox);
                        }
                    }
                    case ERROR_PERMANENTE -> {
                        log("❌ Error de configuración de CallMeBot. Revisa WPP_PHONE y CALLMEBOT_APIKEY. Terminando.");
                        TERMINAR.set(true);
                        despertar(inbox);
                    }
                    case FALLIDO -> log("⚠️ No se pudo enviar el WhatsApp tras " + INTENTOS_WHATSAPP
                            + " intentos. Sigo escuchando correos.");
                }
            } catch (Throwable t) {
                // Cualquier error inesperado se registra; nunca tumba el programa
                log("⚠️ Error inesperado al enviar WhatsApp: " + describir(t));
            }
        });
    }

    private static ResultadoEnvio enviarConReintentos(String texto) {
        for (int intento = 1; intento <= INTENTOS_WHATSAPP; intento++) {
            ResultadoEnvio r = enviarWhatsApp(texto, intento);
            if (r != ResultadoEnvio.FALLIDO) return r;

            if (intento < INTENTOS_WHATSAPP) {
                long espera = ESPERA_ENTRE_INTENTOS_SEG[Math.min(intento - 1, ESPERA_ENTRE_INTENTOS_SEG.length - 1)];
                log("   Reintentando en " + espera + " s...");
                dormir(espera * 1000);
            }
        }
        return ResultadoEnvio.FALLIDO;
    }

    private static ResultadoEnvio enviarWhatsApp(String texto, int intento) {
        String url = "https://api.callmebot.com/whatsapp.php"
                + "?phone=" + enc(WPP_PHONE)
                + "&text=" + enc(texto)
                + "&apikey=" + enc(CALLMEBOT_APIKEY);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT_RESPUESTA_HTTP) // si CallMeBot no contesta, no se queda trabado
                .GET()
                .build();

        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String cuerpo = limpiarHtml(res.body());
            String c = cuerpo.toLowerCase();

            if (c.contains("message queued") || c.contains("message sent")) {
                log("✅ WhatsApp enviado (intento " + intento + "): " + cuerpo);
                return ResultadoEnvio.ENVIADO;
            }
            if (c.contains("apikey is invalid") || c.contains("invalid format") || c.contains("can not be empty")) {
                log("❌ CallMeBot rechazó la apikey o el número: " + cuerpo);
                return ResultadoEnvio.ERROR_PERMANENTE; // reintentar no sirve de nada
            }
            log("⚠️ Respuesta inesperada de CallMeBot (HTTP " + res.statusCode()
                    + ", intento " + intento + "): " + cuerpo);
            return ResultadoEnvio.FALLIDO;

        } catch (HttpTimeoutException e) {
            log("⚠️ CallMeBot no respondió a tiempo (intento " + intento + ").");
            return ResultadoEnvio.FALLIDO;
        } catch (IOException e) {
            log("⚠️ Error de red al llamar a CallMeBot (intento " + intento + "): " + describir(e));
            return ResultadoEnvio.FALLIDO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoEnvio.FALLIDO;
        }
    }

    // ───────────────────────── Utilidades ─────────────────────────

    /** Quita etiquetas HTML de la respuesta de CallMeBot y la recorta para el log. */
    private static String limpiarHtml(String html) {
        if (html == null) return "(respuesta vacía)";
        String texto = html.replaceAll("<[^>]*>", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return texto.length() > 300 ? texto.substring(0, 300) + "…" : texto;
    }

    /** Minúsculas y sin acentos, para que "Facturación" coincida con "facturacion". */
    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String describir(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TERMINAR.set(true);
        }
    }

    private static String env(String nombre) {
        String v = System.getenv(nombre);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + nombre);
        }
        return v.trim();
    }

    private static void log(String msg) {
        System.out.println("[" + LocalDateTime.now().withNano(0) + "] " + msg);
    }
}