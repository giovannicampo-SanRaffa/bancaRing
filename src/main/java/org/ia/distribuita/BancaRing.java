package org.ia.distribuita;

import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Nodo ATM di un anello logico a token con tolleranza ai guasti.
 *
 * Uso: java -jar bancaRing.jar <id 1..4> [--rejoin] [d100 w200 ...]
 *
 * IDEA CHIAVE: il saldo NON e' in una variabile condivisa, viaggia DENTRO il token
 * ("TOKEN;epoch;seq;saldo"). Solo chi ha il token puo' leggerlo/modificarlo.
 *
 * Tolleranza ai guasti (modello crash-stop, nodi che muoiono, token che si perde):
 *  - ACK: chi inoltra il token lo considera consegnato solo dopo l'ACK del successore;
 *    se il successore e' morto prova il successivo (ring riconfigurato: salto i nodi morti).
 *  - Ogni nodo tiene una copia dell'ultimo stato visto (epoch, seq, saldo): e' la "memoria" del token.
 *  - Watchdog: se non vede il token per TIMEOUT (scaglionato per id) lancia il recupero:
 *    interroga gli altri (QUERY); se qualcuno ha visto il token da poco e' un falso allarme,
 *    altrimenti rigenera il token con epoch+1 e il saldo piu' recente tra le copie.
 *  - epoch: un token con (epoch,seq) <= a quello gia' visto e' vecchio/duplicato e viene scartato,
 *    cosi' resta un solo token anche se ne nasce uno "fantasma".
 */
public class BancaRing {
    static final int N = 4, BASE_PORT = 5000;
    static final long INITIAL_BALANCE = 1000;
    static final int HOLD_MS = 800;        // pausa per rendere leggibile la circolazione
    static final int IO_MS = 1000;         // timeout connessione/ACK
    static final int ALIVE_MS = 2500;      // "token visto da poco" nelle risposte a QUERY
    static final int TIMEOUT_MS = 8000;    // > un giro completo (~4 * HOLD_MS) con margine
    static final int STEP_MS = 2000;       // scaglionamento per id: il nodo con id minore recupera per primo

    final int id;
    final Queue<String> pending = new ConcurrentLinkedQueue<>(); // transazioni locali in attesa (es. "w200")
    volatile boolean crashOnToken;         // solo per la demo: il nodo muore mentre ha il token

    // Stato noto del token, protetto da "this". Aggiornato a ogni ricezione e a ogni inoltro.
    long epoch, seq, balance;
    long lastActivity = System.currentTimeMillis();
    boolean armed;    // watchdog attivo solo dopo aver visto il token almeno una volta
    boolean holding;  // true mentre il nodo e' in possesso del token

    BancaRing(int id) { this.id = id; }

    public static void main(String[] args) throws Exception {
        BancaRing n = new BancaRing(Integer.parseInt(args[0]));
        boolean rejoin = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--rejoin")) rejoin = true; else n.enqueue(args[i]);
        }
        Thread.startVirtualThread(n::watchdog);
        Thread.startVirtualThread(n::readStdin);
        n.log("avviato su localhost:" + (BASE_PORT + n.id) + ", successore = ATM" + (n.id % N + 1));
        // ATM1 crea il token iniziale, tranne se si sta riavviando dopo un guasto (--rejoin)
        if (n.id == 1 && !rejoin) Thread.startVirtualThread(() -> n.onToken(1, 0, INITIAL_BALANCE));
        n.serve();
    }

    // ---------------------------------------------------------------- rete

    /** Accetta connessioni: ogni messaggio e' una riga di testo con risposta di una riga. */
    void serve() throws IOException {
        try (ServerSocket ss = new ServerSocket(BASE_PORT + id)) {
            while (true) {
                Socket s = ss.accept();
                Thread.startVirtualThread(() -> handle(s));
            }
        }
    }

    void handle(Socket s) {
        String[] tok = null;
        try (s) {
            s.setSoTimeout(IO_MS);
            var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            var out = new PrintWriter(s.getOutputStream(), true);
            String[] m = in.readLine().split(";");
            if (m[0].equals("TOKEN")) {
                out.println("ACK");          // ACK subito: il mittente puo' dimenticare il token
                tok = m;
            } else if (m[0].equals("QUERY")) {
                synchronized (this) {
                    boolean alive = holding || System.currentTimeMillis() - lastActivity < ALIVE_MS;
                    out.println("STATE;" + (alive ? 1 : 0) + ";" + epoch + ";" + seq + ";" + balance);
                    lastActivity = System.currentTimeMillis(); // chi mi interroga sta recuperando: non competo
                }
            }
        } catch (Exception ignored) { }
        if (tok != null) onToken(Long.parseLong(tok[1]), Long.parseLong(tok[2]), Long.parseLong(tok[3]));
    }

    /** Invia una riga a un nodo e restituisce la risposta, null se irraggiungibile. */
    static String ask(int to, String msg) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", BASE_PORT + to), IO_MS);
            s.setSoTimeout(IO_MS);
            new PrintWriter(s.getOutputStream(), true).println(msg);
            return new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ token ring

    /** Il nodo ha ricevuto (o rigenerato) il token: sezione critica + inoltro. */
    void onToken(long e, long s, long b) {
        synchronized (this) {
            if (e < epoch || (e == epoch && s <= seq)) { // vecchio o duplicato: scarto
                log("token scartato (vecchio/duplicato) epoch=" + e + " seq=" + s);
                return;
            }
            epoch = e; seq = s; balance = b; armed = true; holding = true;
            lastActivity = System.currentTimeMillis();
        }
        log("TOKEN RICEVUTO (epoch=" + e + " seq=" + s + " saldo=" + b + ")");

        // ===== SEZIONE CRITICA: solo chi ha il token arriva qui =====
        String tx = pending.poll();
        if (tx != null) transact(tx);
        // ============================================================

        if (crashOnToken) { log("*** CRASH simulato: il token e' perso con me ***"); Runtime.getRuntime().halt(1); }
        sleep(HOLD_MS);
        forward();
    }

    /** Transazione atomica: lettura, validazione, aggiornamento, scrittura nel token, log. */
    void transact(String tx) {
        char type = tx.charAt(0);
        long amt = Long.parseLong(tx.substring(1));
        String name = (type == 'd' ? "DEPOSITO " : "PRELIEVO ") + amt;
        log("INIZIO transazione: " + name);
        synchronized (this) {
            long old = balance;                                   // 1. lettura
            if (type == 'w' && amt > old) {                       // 2. validazione
                log("transazione RIFIUTATA: saldo insufficiente (" + old + ")");
            } else {
                balance = type == 'd' ? old + amt : old - amt;    // 3-4. aggiornamento e scrittura (nel token)
            }
        }
        sleep(HOLD_MS / 2);
        log("FINE transazione: " + name + " -> saldo aggiornato = " + balance); // 5. log
    }

    /** Inoltra il token al successore; se e' morto prova i successivi (anello ricostruito). */
    void forward() {
        long e, s, b;
        synchronized (this) { seq++; e = epoch; s = seq; b = balance; } // lo stato locale = copia di cio' che invio
        while (true) {
            for (int k = 1; k < N; k++) {
                int to = (id - 1 + k) % N + 1;
                if ("ACK".equals(ask(to, "TOKEN;" + e + ";" + s + ";" + b))) {
                    log("TOKEN INOLTRATO a ATM" + to + (k > 1 ? " (saltati " + (k - 1) + " nodi morti)" : ""));
                    synchronized (this) { holding = false; lastActivity = System.currentTimeMillis(); }
                    return;
                }
                log("ATM" + to + " non raggiungibile");
            }
            log("nessun nodo raggiungibile: trattengo il token e riprovo");
            sleep(1000);
        }
    }

    // ------------------------------------------------- rilevazione e recupero

    void watchdog() {
        while (true) {
            sleep(500);
            boolean fire;
            synchronized (this) {
                fire = armed && !holding && System.currentTimeMillis() - lastActivity > TIMEOUT_MS + (long) id * STEP_MS;
            }
            if (fire) recover();
        }
    }

    /** Il token sembra perso: verifica con gli altri, poi rigenera con epoch+1 e l'ultimo saldo noto. */
    void recover() {
        long be, bs, bb;
        synchronized (this) { lastActivity = System.currentTimeMillis(); be = epoch; bs = seq; bb = balance; }
        log("TIMEOUT: token sospetto perso, interrogo gli altri nodi");
        for (int j = 1; j <= N; j++) {
            if (j == id) continue;
            String r = ask(j, "QUERY");
            if (r == null) { log("ATM" + j + " non risponde (morto)"); continue; }
            String[] m = r.split(";");
            if (m[1].equals("1")) { log("ATM" + j + " ha visto il token da poco: falso allarme"); return; }
            long e = Long.parseLong(m[2]), s = Long.parseLong(m[3]), b = Long.parseLong(m[4]);
            if (e > be || (e == be && s > bs)) { be = e; bs = s; bb = b; } // copia piu' recente
        }
        log("TOKEN RIGENERATO: epoch " + (be + 1) + ", saldo recuperato = " + bb);
        onToken(be + 1, bs, bb);
    }

    // ------------------------------------------------------------- utilita'

    /** Accetta "d100"/"w200" (anche "d 100"). */
    void enqueue(String cmd) {
        cmd = cmd.replace(" ", "").toLowerCase();
        if (cmd.equals("crash")) { crashOnToken = true; log("al prossimo token il nodo andra' in crash"); }
        else if (cmd.matches("[dw]\\d+")) { pending.add(cmd); log("transazione in coda: " + cmd); }
        else if (!cmd.isEmpty()) log("comando non valido (usa: d100, w200, crash)");
    }

    void readStdin() {
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) enqueue(sc.nextLine());
    }

    void log(String m) {
        System.out.println("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] [ATM" + id + "] " + m);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
