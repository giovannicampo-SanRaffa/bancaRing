package org.ia.distribuita;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Un ATM dell'anello a token.
 *
 * Uso: java [-Dring.n=4 -Dring.port=5000 -Dring.hosts=h1,h2,..] -jar bancaRing.jar <id 1..N> [--rejoin] [d100 w200 ...]
 *
 * Il saldo non e' una variabile condivisa: viaggia dentro il token ("TOKEN;epoch;seq;saldo;applicate")
 * e lo tocca solo chi ha il token.
 *
 * Guasti (crash-stop):
 *  - un nodo inoltra il token e lo da' per consegnato solo dopo l'ACK; se il successore e' morto prova il prossimo.
 *  - ogni nodo si tiene l'ultimo stato visto (epoch, seq, saldo).
 *  - se per un po' non si vede il token (timeout diverso per id) parte il recupero: QUERY agli altri; se qualcuno
 *    l'ha visto da poco e' un falso allarme, altrimenti si rigenera con epoch+1 e il saldo piu' recente.
 *  - un token con (epoch,seq) non piu' recente di quello gia' visto e' un duplicato e si butta.
 *  - se resta un solo nodo vivo il token resta con lui e continua a fare le sue transazioni.
 *  - la coda delle transazioni e' su disco (atm<id>.queue) e una transazione esce solo dopo l'ACK. Se il nodo
 *    muore, la rifa' al riavvio; il vettore "ultimo n applicato per nodo" nel token evita di applicarla due volte.
 */
public class BancaRing {
    // Nodi configurabili da riga di comando (default: 4 su localhost, porta 5000+id)
    // i nodi sono fissi all'avvio; join/leave dinamici richiederebbero un accordo sulla lista dei membri
    static final int N = Integer.getInteger("ring.n", 4), BASE_PORT = Integer.getInteger("ring.port", 5000);
    static final String[] HOSTS = System.getProperty("ring.hosts", "").split(",");
    static final long INITIAL_BALANCE = 1000;
    static final int HOLD_MS = Integer.getInteger("ring.hold", 1500); // pausa in ogni nodo (ms), solo per leggere meglio il log
    static final int IO_MS = 1000;         // timeout di connessione e ACK
    static final int ALIVE_MS = HOLD_MS * 3;     // "token visto da poco" per le risposte a QUERY
    static final int TIMEOUT_MS = N * HOLD_MS + 4800; // piu' di un giro completo con margine
    static final int STEP_MS = 2000;       // ritardo per id: recupera per primo chi ha id minore

    final int id;
    // Transazioni in attesa, formato "n:w200" (n = numero progressivo). Salvate su disco, tolte dopo l'ACK.
    final Queue<String> pending = new ConcurrentLinkedQueue<>();
    final Path queueFile;
    long nextNo = 1;
    volatile String inFlight;              // transazione fatta su questo token ma non ancora confermata dall'ACK
    volatile boolean crashOnToken;         // solo per la demo: il nodo muore quando riceve il token

    // Ultimo stato del token che conosco (protetto da "this")
    long epoch, seq, balance;
    long[] applied = new long[N + 1];      // ultimo n applicato per ogni nodo (viaggia nel token)
    long lastActivity = System.currentTimeMillis();
    boolean armed;    // il watchdog parte solo dopo il primo token visto
    boolean holding;  // true mentre ho il token

    BancaRing(int id) {
        this.id = id;
        queueFile = Path.of("atm" + id + ".queue");
        try {
            if (Files.exists(queueFile)) {
                var l = Files.readAllLines(queueFile);
                nextNo = Long.parseLong(l.get(0));
                pending.addAll(l.subList(1, l.size()));
                if (l.size() > 1) log("recuperate " + (l.size() - 1) + " transazioni dal disco");
            }
        } catch (Exception e) { log("coda su disco illeggibile: " + e); }
    }

    static String host(int to) { return to <= HOSTS.length && !HOSTS[to - 1].isBlank() ? HOSTS[to - 1] : "localhost"; }
    static String enc(long[] v) { return Arrays.stream(v).boxed().map(String::valueOf).collect(Collectors.joining(",")); }
    static long[] dec(String v) { return Arrays.stream(v.split(",")).mapToLong(Long::parseLong).toArray(); }

    /** Riscrive la coda su disco (da chiamare dentro synchronized (pending)). */
    void persist() {
        var l = new ArrayList<String>();
        l.add(String.valueOf(nextNo));
        l.addAll(pending);
        try { Files.write(queueFile, l); } catch (IOException e) { log("scrittura coda fallita: " + e); }
    }

    public static void main(String[] args) throws Exception {
        BancaRing n = new BancaRing(Integer.parseInt(args[0]));
        if (n.id < 1 || n.id > N) throw new IllegalArgumentException("id deve essere 1.." + N);
        boolean rejoin = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--rejoin")) rejoin = true; else n.enqueue(args[i]);
        }
        Thread.startVirtualThread(n::watchdog);
        Thread.startVirtualThread(n::readStdin);
        n.log("avviato su porta " + (BASE_PORT + n.id) + ", successore = ATM" + (n.id % N + 1));
        // ATM1 crea il token iniziale, tranne se si sta riavviando dopo un guasto (--rejoin)
        if (n.id == 1 && !rejoin) Thread.startVirtualThread(() -> n.onToken(1, 0, INITIAL_BALANCE, new long[N + 1]));
        n.serve();
    }

    // ---------------------------------------------------------------- rete

    /** Accetta connessioni: un messaggio = una riga, risposta = una riga. */
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
                out.println("ACK");          // rispondo subito, cosi' il mittente puo' liberarsi del token
                tok = m;
            } else if (m[0].equals("QUERY")) {
                synchronized (this) {
                    boolean alive = holding || System.currentTimeMillis() - lastActivity < ALIVE_MS;
                    out.println("STATE;" + (alive ? 1 : 0) + ";" + epoch + ";" + seq + ";" + balance + ";" + enc(applied));
                    lastActivity = System.currentTimeMillis(); // chi mi interroga sta recuperando, non gli faccio concorrenza
                }
            }
        } catch (Exception ignored) { }
        if (tok != null) onToken(Long.parseLong(tok[1]), Long.parseLong(tok[2]), Long.parseLong(tok[3]), dec(tok[4]));
    }

    /** Manda una riga a un nodo e torna la risposta (null se non risponde). */
    static String ask(int to, String msg) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host(to), BASE_PORT + to), IO_MS);
            s.setSoTimeout(IO_MS);
            new PrintWriter(s.getOutputStream(), true).println(msg);
            return new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ token ring

    /** Ho il token (ricevuto o rigenerato): sezione critica e poi inoltro. */
    void onToken(long e, long s, long b, long[] v) {
        synchronized (this) {
            if (e < epoch || (e == epoch && s <= seq)) { // vecchio o duplicato, lo butto
                log("token scartato (vecchio/duplicato) epoch=" + e + " seq=" + s);
                return;
            }
            epoch = e; seq = s; balance = b; applied = v; inFlight = null; armed = true; holding = true;
            lastActivity = System.currentTimeMillis();
        }
        log("TOKEN RICEVUTO (epoch=" + e + " seq=" + s + " saldo=" + b + ")");

        // ===== SEZIONE CRITICA =====
        String tx = pending.peek();
        if (tx != null) { inFlight = tx; transact(tx); }
        // ============================

        if (crashOnToken) { log("*** CRASH simulato: il token e' perso con me ***"); Runtime.getRuntime().halt(1); }
        sleep(HOLD_MS);
        forward();
    }

    /** Esegue una transazione sul saldo del token. */
    void transact(String entry) {
        long no = Long.parseLong(entry.substring(0, entry.indexOf(':')));
        String tx = entry.substring(entry.indexOf(':') + 1);
        synchronized (this) {
            if (no <= applied[id]) { log("transazione " + tx + " (n=" + no + ") gia' applicata: scarto"); return; }
            applied[id] = no;
        }
        char type = tx.charAt(0);
        long amt = Long.parseLong(tx.substring(1));
        String name = (type == 'd' ? "DEPOSITO " : "PRELIEVO ") + amt;
        log("INIZIO transazione: " + name);
        synchronized (this) {
            long old = balance;                                   // 1. leggo
            if (type == 'w' && amt > old) {                       // 2. controllo
                log("transazione RIFIUTATA: saldo insufficiente (" + old + ")");
            } else {
                balance = type == 'd' ? old + amt : old - amt;    // 3-4. aggiorno il saldo (che sta nel token)
            }
        }
        sleep(HOLD_MS / 2);
        log("FINE transazione: " + name + " -> saldo aggiornato = " + balance); // 5. log
    }

    /** Manda il token al successore; se e' morto prova i successivi. */
    void forward() {
        while (true) {
            long e, s, b; long[] v;
            synchronized (this) { seq++; e = epoch; s = seq; b = balance; v = applied.clone(); } // tengo una copia di quello che invio
            for (int k = 1; k < N; k++) {
                int to = (id - 1 + k) % N + 1;
                if ("ACK".equals(ask(to, "TOKEN;" + e + ";" + s + ";" + b + ";" + enc(v)))) {
                    log("TOKEN INOLTRATO a ATM" + to + (k > 1 ? " (saltati " + (k - 1) + " nodi morti)" : ""));
                    String done = inFlight;   // con l'ACK la transazione e' confermata, ora esce dalla coda
                    if (done != null) { synchronized (pending) { pending.remove(done); persist(); } inFlight = null; }
                    synchronized (this) { holding = false; lastActivity = System.currentTimeMillis(); }
                    return;
                }
                log("ATM" + to + " non raggiungibile");
            }
            // Sono rimasto solo: il token gira "da me a me". Confermo la transazione appena fatta e faccio la prossima.
            log("nessun altro nodo raggiungibile: il token resta con me");
            String done = inFlight;
            if (done != null) { synchronized (pending) { pending.remove(done); persist(); } inFlight = null; }
            String tx = pending.peek();
            if (tx != null) { inFlight = tx; transact(tx); }
            sleep(HOLD_MS);
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

    /** Il token sembra perso: chiedo agli altri e, lo rigenero con epoch+1. */
    void recover() {
        long be, bs, bb; long[] bv;
        synchronized (this) { lastActivity = System.currentTimeMillis(); be = epoch; bs = seq; bb = balance; bv = applied; }
        log("TIMEOUT: token sospetto perso, interrogo gli altri nodi");
        for (int j = 1; j <= N; j++) {
            if (j == id) continue;
            String r = ask(j, "QUERY");
            if (r == null) { log("ATM" + j + " non risponde (morto)"); continue; }
            String[] m = r.split(";");
            if (m[1].equals("1")) { log("ATM" + j + " ha visto il token da poco: falso allarme"); return; }
            long e = Long.parseLong(m[2]), s = Long.parseLong(m[3]), b = Long.parseLong(m[4]);
            if (e > be || (e == be && s > bs)) { be = e; bs = s; bb = b; bv = dec(m[5]); } // tengo la copia piu' recente
        }
        log("TOKEN RIGENERATO: epoch " + (be + 1) + ", saldo recuperato = " + bb);
        onToken(be + 1, bs, bb, bv);
    }

    // ------------------------------------------------------------- utilita'

    /** Comandi: d100, w200 (anche "d 100") e crash. */
    void enqueue(String cmd) {
        cmd = cmd.replace(" ", "").toLowerCase();
        if (cmd.equals("crash")) { crashOnToken = true; log("al prossimo token il nodo andra' in crash"); }
        else if (cmd.matches("[dw]\\d+")) {
            synchronized (pending) { pending.add(nextNo++ + ":" + cmd); persist(); }
            log("transazione in coda: " + cmd);
        }
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
