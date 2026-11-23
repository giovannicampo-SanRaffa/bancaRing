# bancaRing - Transazioni bancarie distribuite con mutua esclusione a Token Ring

Progetto di Intelligenza Artificiale Distribuita. Quattro ATM (processi separati su `localhost`) modificano lo stesso
saldo bancario. La mutua esclusione è garantita solo dall'algoritmo Token Ring; in più il sistema regge
il crash di un nodo e la perdita del token (che viene rigenerato).

- **Repository GitHub:** https://github.com/giovannicampo-SanRaffa/bancaRing
- **Video dimostrativo:** [video/campo_giovanni_tokenRing.mp4](https://github.com/giovannicampo-SanRaffa/bancaRing/blob/main/video/campo_giovanni_tokenRing.mp4)

## 1. Istruzioni di esecuzione

| Elemento | Valore |
|---|---|
| Linguaggio | Java 21 |
| Build | Maven 3.9+ |
| Librerie / dipendenze | nessuna (solo JDK: `java.net`, `java.util.concurrent`) |
| Porte usate | `5001`-`5004` su localhost (ATMn ascolta su `5000+n`) |

**Compilazione** (dalla cartella del progetto, con JDK 21 attivo):

```
mvn package
```

Produce `target/bancaRing.jar`.

**Avvio dei 4 nodi** - un terminale per nodo, ognuno nella cartella del progetto. Il numero passato al programma è l'id
del nodo: ogni terminale = un processo JVM = un ATM, con memoria privata propria. Avviare **ATM1 per ultimo**
(crea il token e deve trovare gli altri già in ascolto).

| Terminale | Comando |
|---|---|
| 4 | `java -jar target/bancaRing.jar 4 w500` |
| 3 | `java -jar target/bancaRing.jar 3 d100` |
| 2 | `java -jar target/bancaRing.jar 2 w200` |
| 1 (ultimo) | `java -jar target/bancaRing.jar 1` |

Gli argomenti dopo l'id sono transazioni da mettere in coda: `dN` = deposito di N, `wN` = prelievo di N.
Si possono anche digitare a runtime nel terminale del nodo (es. `d100`, `w 50`). Il comando `crash` fa morire il nodo
non appena riceve il token (serve alla demo del guasto).

**Avvio automatico (Windows):** `.\demo.ps1` apre 4 finestre disposte a griglia 2x2 (ATM1 in alto a sinistra, ATM2 in alto a destra, ATM3 in basso a destra, ATM4 in basso a sinistra). ATM2-4 partono subito, ATM1 aspetta un Invio: quando lo premi crea il token e inizia il giro. Scenari: `-Scenario base` (esempio del testo), `crash` (ATM3 muore appena riceve il token), `vuoto` (nessuna transazione, le digiti tu). Se le finestre non si dispongono da sole, si sistemano a mano con Win+frecce.

**Far cadere un nodo a mano:** nel riquadro del nodo premi `Ctrl+C` (il nodo muore subito, e se aveva il token lo
perde), oppure scrivi `crash` e il nodo muore quando riceve il token. Gli altri se ne accorgono e rigenerano il token.
**Riavviarlo:** nello stesso riquadro, `java -jar target/bancaRing.jar <id> --rejoin`.


## 2. Come funziona

Gli ATM sono in un anello logico `ATM1 -> ATM2 -> ATM3 -> ATM4 -> ATM1`. Di norma ognuno parla solo con il suo successore.

- **Il saldo sta dentro il token.** Non c'è nessuna variabile condivisa: il saldo esiste solo nel messaggio
  `TOKEN;epoch;seq;saldo;applicate`. Può toccarlo solo chi ha il token in mano, quindi due ATM non possono mai
  modificarlo insieme.
- **Messaggi** (TCP, una riga di testo e una di risposta): `TOKEN;...` -> `ACK`;
  `QUERY` -> `STATE;vivo;epoch;seq;saldo;applicate` (serve solo quando si recupera un token perso).
- **Sezione critica:** una transazione per ogni passaggio del token. Si legge il saldo, si controlla (un prelievo
  più grande del saldo viene rifiutato), si aggiorna il saldo nel token e si scrive il log. La transazione conta come
  confermata quando il successore risponde ACK al token inoltrato.
- Se un nodo non ha niente in coda gira subito il token (c'è una pausa di 1,5 s solo per rendere leggibile il log; si cambia con `-Dring.hold=1000`, in millisecondi).

## 3. Guasti (modello crash-stop)

| Problema | Cosa succede |
|---|---|
| Il successore è morto | Il token si considera consegnato solo dopo l'`ACK`. Se non arriva, si prova col nodo dopo (i morti vengono saltati). |
| Il token si perde (muore chi lo aveva) | Ogni nodo ricorda l'ultimo stato visto (epoch, seq, saldo). Se per `N * pausa + 4,8 s + 2 s * id` (con i valori di default, 10,8 s + 2 s * id) non vede passare il token parte il recupero; chi ha l'id più basso parte per primo. |
| Falso allarme (token solo lento) | Prima di rigenerare, il nodo manda `QUERY` agli altri: se qualcuno ha visto il token da poco, non fa niente. |
| Rigenerazione | Si prende la copia più recente tra i nodi che rispondono e si crea un token con `epoch + 1`. |
| Due token | Un token con `(epoch, seq)` non più recente di quello già visto viene buttato. |
| Muoiono più nodi | Non serve nessun quorum: i nodi rimasti continuano a passarsi il token (se ne restano 2 su 4, se lo passano tra loro). Se ne rimane uno solo, il token resta con lui e lui continua a fare le sue transazioni. Se un nodo rientra con `--rejoin`, al giro dopo riceve il token. |
| Un nodo muore dopo una transazione | La coda è salvata su disco (`atm<id>.queue`) e una transazione esce dalla coda solo dopo l'`ACK`. Nel token c'è anche, per ogni nodo, l'ultimo numero di transazione applicato: al riavvio con `--rejoin` la transazione viene rifatta, ma mai due volte. |
| Quali nodi ci sono | Si decide all'avvio: `-Dring.n=5 -Dring.port=6000 -Dring.hosts=h1,h2,h3,h4,h5` (default: 4 nodi su localhost, porta `5000+id`).  |

Limiti: se la rete si spezza (partizione) le due parti possono rigenerare ciascuna il proprio token e i saldi possono divergere, non lo gestiamo; i nodi sono fissi dall'avvio (per aggiungerne o toglierne a runtime servirebbe un accordo sulla lista dei
membri); non gestiamo nodi che mentono (solo crash); la coda su disco funziona se il disco del nodo sopravvive al riavvio.

## 4. Esempi di log

**Scenario base** (saldo iniziale 1000; ATM2 w200, ATM3 d100, ATM4 w500):

```
[ATM1] TOKEN RICEVUTO (epoch=1 seq=0 saldo=1000)
[ATM1] TOKEN INOLTRATO a ATM2
[ATM2] TOKEN RICEVUTO (epoch=1 seq=1 saldo=1000)
[ATM2] INIZIO transazione: PRELIEVO 200
[ATM2] FINE transazione: PRELIEVO 200 -> saldo aggiornato = 800
[ATM2] TOKEN INOLTRATO a ATM3
[ATM3] TOKEN RICEVUTO (epoch=1 seq=2 saldo=800)
[ATM3] INIZIO transazione: DEPOSITO 100
[ATM3] FINE transazione: DEPOSITO 100 -> saldo aggiornato = 900
...
[ATM4] FINE transazione: PRELIEVO 500 -> saldo aggiornato = 400
```

**Scenario crash** (ATM3 muore con il token, estratto di una esecuzione reale):

```
[ATM3] *** CRASH simulato: il token e' perso con me ***
[ATM1] TIMEOUT: token sospetto perso, interrogo gli altri nodi
[ATM1] ATM3 non risponde (morto)
[ATM1] TOKEN RIGENERATO: epoch 2, saldo recuperato = 850
[ATM2] ATM3 non raggiungibile
[ATM2] TOKEN INOLTRATO a ATM4 (saltati 1 nodi morti)
[ATM4] FINE transazione: PRELIEVO 500 -> saldo aggiornato = 350
```

Il saldo 850 è l'ultimo confermato prima che ATM3 ricevesse il token. Il deposito di ATM3 non era stato inoltrato, quindi resta nella sua coda su disco e viene rifatto (una volta sola) se ATM3 riparte con `--rejoin`.

## 5. Contenuto del repository

- `src/main/java/org/ia/distribuita/BancaRing.java` - tutto il codice (token, messaggi, sezione critica, recupero)
- `pom.xml` - build Maven (Java 21)
- `demo.ps1` - avvio automatico dei 4 terminali
- `video/campo_giovanni_tokenRing.mp4` - video dimostrativo

