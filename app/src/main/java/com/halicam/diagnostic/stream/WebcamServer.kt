package com.halicam.diagnostic.stream

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.halicam.diagnostic.gl.FrameSink
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP local do modo Webcam.
 * - /stream.mjpg  : vídeo MJPEG (OBS: Fonte de mídia ou Navegador)
 * - /obs          : página de tela cheia para "Navegador" (Browser Source) do OBS
 * - /             : painel de controle remoto (prévia, gravar, pausar, câmeras)
 * - /api/...      : comandos JSON
 * Todas as rotas exigem ?k=<token> (exibido no aparelho). Somente rede local; sem Internet.
 */
class WebcamServer(
    private val token: String,
    private val control: RemoteControl,
    val port: Int = 8080
) : FrameSink {

    interface RemoteControl {
        fun statusJson(): String
        /** Executa um comando (na thread principal) e devolve uma mensagem curta. */
        fun command(name: String, arg: String?): String
    }

    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = AtomicInteger(0)
    private val snapshotWaiters = AtomicInteger(0)
    val clientCount: Int get() = clients.get()

    private val frameLock = Object()
    private var frame: ByteArray? = null
    private var frameSeq = 0L

    private val jpegThread = HandlerThread("HaliCam-JPEG").apply { start() }
    private val jpegHandler = Handler(jpegThread.looper)
    @Volatile private var encoding = false
    private var bitmap: Bitmap? = null
    private val jpegOut = ByteArrayOutputStream(512 * 1024)

    fun start() {
        if (running) return
        running = true
        pool.execute {
            try {
                ServerSocket(port).use { ss ->
                    ss.reuseAddress = true
                    server = ss
                    while (running) {
                        val socket = try { ss.accept() } catch (_: Exception) { break }
                        pool.execute { handle(socket) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Servidor webcam: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    fun release() {
        stop()
        pool.shutdownNow()
        jpegThread.quitSafely()
    }

    // ---------------------------------------------------------------- FrameSink (thread GL)

    override fun wantsFrame(): Boolean = running && (clients.get() > 0 || snapshotWaiters.get() > 0) && !encoding

    override fun onRgbaFrame(buffer: ByteBuffer, width: Int, height: Int) {
        val bmp = bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        bmp.copyPixelsFromBuffer(buffer)
        encoding = true
        jpegHandler.post {
            try {
                jpegOut.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, 78, jpegOut)
                val bytes = jpegOut.toByteArray()
                synchronized(frameLock) {
                    frame = bytes
                    frameSeq++
                    frameLock.notifyAll()
                }
            } finally {
                encoding = false
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                s.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val out = s.getOutputStream()
                if (query["k"] != token) {
                    respond(out, 403, "text/html; charset=utf-8",
                        "<h1>HaliCam</h1><p>Token ausente ou inválido. Use o endereço exibido no celular.</p>")
                    return
                }
                when {
                    path == "/stream.mjpg" -> stream(s, out)
                    path == "/snapshot.jpg" -> {
                        val seq = synchronized(frameLock) { frameSeq }
                        snapshotWaiters.incrementAndGet()
                        val f = try { waitFrame(seq, 3000) } finally { snapshotWaiters.decrementAndGet() }
                            ?: return respond(out, 503, "text/plain", "sem quadro")
                        respondBytes(out, 200, "image/jpeg", f.second)
                    }
                    path == "/obs" -> respond(out, 200, "text/html; charset=utf-8", obsPage())
                    path == "/api/status" -> respond(out, 200, "application/json", control.statusJson())
                    path.startsWith("/api/") -> {
                        val msg = control.command(path.removePrefix("/api/"), query["v"])
                        respond(out, 200, "application/json", "{\"result\":\"${msg.replace("\"", "'")}\"}")
                    }
                    path == "/" || path == "/index.html" -> respond(out, 200, "text/html; charset=utf-8", controlPage())
                    else -> respond(out, 404, "text/plain", "não encontrado")
                }
            } catch (e: Exception) {
                Log.d(TAG, "cliente webcam: ${e.message}")
            }
        }
    }

    private fun stream(s: Socket, out: OutputStream) {
        s.soTimeout = 0
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
        out.flush()
        clients.incrementAndGet()
        try {
            var seq = 0L
            while (running) {
                val f = waitFrame(seq, 5000) ?: continue
                seq = f.first
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.second.size}\r\n\r\n".toByteArray())
                out.write(f.second)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun waitFrame(afterSeq: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(frameLock) {
            while (running && (frame == null || frameSeq <= afterSeq)) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                frameLock.wait(left)
            }
            val f = frame ?: return null
            return frameSeq to f
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) =
        respondBytes(out, code, type, body.toByteArray(Charsets.UTF_8))

    private fun respondBytes(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Service Unavailable" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun obsPage() = """<!doctype html><html><head><meta charset="utf-8"><title>HaliCam OBS</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}img{width:100%;height:100%;object-fit:contain}</style>
</head><body><img src="/stream.mjpg?k=$token"></body></html>"""

    private fun controlPage() = """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>HaliCam</title>
<style>
:root{--bg:#0d0e10;--panel:#17191d;--text:#f2f2f2;--muted:#9aa0a6;--accent:#ff6a2b;--rec:#ff3b30}
body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:960px;margin:0 auto;padding:16px}
h1{font-size:18px;font-weight:600;margin:4px 0 12px}h1 span{color:var(--accent)}
.view{background:#000;border-radius:14px;overflow:hidden;aspect-ratio:16/9;display:flex;align-items:center;justify-content:center}
.view img{width:100%;height:100%;object-fit:contain}
.bar{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0}
button{background:var(--panel);color:var(--text);border:1px solid #2a2d33;border-radius:999px;padding:10px 16px;font-size:14px;cursor:pointer}
button.rec{background:var(--rec);border-color:var(--rec)}button.on{border-color:var(--accent);color:var(--accent)}
#status{color:var(--muted);font-size:13px;font-variant-numeric:tabular-nums}
code{background:var(--panel);padding:2px 6px;border-radius:6px;font-size:12px;word-break:break-all}
p{color:var(--muted);font-size:13px;line-height:1.5}
</style></head><body><main>
<h1>Hali<span>Cam</span> · Webcam</h1>
<div class="view"><img src="/stream.mjpg?k=$token" alt="prévia"></div>
<div class="bar">
<button id="rec" onclick="toggleRec()">Gravar</button>
<button id="pause" onclick="cmd(paused?'resume':'pause')">Pausar</button>
<button data-src="back" onclick="cmd('source','back')">Traseira</button>
<button data-src="front" onclick="cmd('source','front')">Frontal</button>
<button data-src="dual" onclick="cmd('source','dual')">Dual</button>
<button onclick="cmd('swap')">Trocar principal</button>
</div>
<div id="status">conectando…</div>
<p>OBS Studio → <b>Navegador</b> (Browser Source): <code id="obs"></code><br>
ou <b>Fonte de mídia</b> sem "Arquivo local": <code id="mjpg"></code></p>
</main><script>
const k=new URLSearchParams(location.search).get('k');let recording=false,paused=false;
document.getElementById('obs').textContent=location.origin+'/obs?k='+k;
document.getElementById('mjpg').textContent=location.origin+'/stream.mjpg?k='+k;
function cmd(c,v){fetch('/api/'+c+'?k='+k+(v?'&v='+v:'')).then(r=>r.json()).then(()=>poll());}
function toggleRec(){cmd(recording?'stop':'record');}
function fmt(ms){const s=Math.floor(ms/1000);return [s/3600|0,(s/60|0)%60,s%60].map(x=>String(x).padStart(2,'0')).join(':')}
function poll(){fetch('/api/status?k='+k).then(r=>r.json()).then(s=>{recording=s.recording;paused=s.paused;
const b=document.getElementById('rec');b.textContent=recording?'Parar '+fmt(s.elapsedMs):'Gravar';b.className=recording?'rec':'';
document.getElementById('pause').textContent=paused?'Continuar':'Pausar';
document.querySelectorAll('[data-src]').forEach(e=>e.className=e.dataset.src==s.source?'on':'');
document.getElementById('status').textContent=(recording?(paused?'PAUSADO':'● REC')+' · ':'')+s.resolution+' · '+s.fps+' fps · bateria '+s.battery+'% · '+s.temperature+' °C'+(s.dualAvailable===false?' · dual indisponível':'');
}).catch(()=>{document.getElementById('status').textContent='sem conexão';});}
setInterval(poll,1000);poll();
</script></body></html>"""

    companion object {
        private const val TAG = "HaliCam"
        private const val BOUNDARY = "halicamframe"

        /** IPv4 local (Wi-Fi), para exibir o endereço da webcam. */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) { null }
    }
}
