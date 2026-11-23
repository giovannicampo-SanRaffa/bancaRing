# Apre 4 finestre PowerShell (una per ATM) disposte a griglia 2x2 sullo schermo, cosi' si registra tutto insieme.
#   +-------+-------+
#   | ATM1  | ATM2  |
#   +-------+-------+
#   | ATM4  | ATM3  |
#   +-------+-------+
# ATM2, ATM3, ATM4 partono subito; ATM1 aspetta un Invio: quando lo premi crea il token e inizia il giro.
#
# Uso:  .\demo.ps1 [-Scenario base|crash|vuoto]
#   base  = esempio del testo: ATM2 w200, ATM3 d100, ATM4 w500  (1000 -> 800 -> 900 -> 400)
#   crash = come base, ma ATM3 muore appena riceve il token
#   vuoto = nessuna transazione, le digiti tu nelle finestre (es. d100, w50, crash)
# Richiede: target\bancaRing.jar (mvn package) e Java 21 nel PATH (o JAVA_HOME impostato).
# Le finestre sono console classiche (conhost), non Windows Terminal, per poterle posizionare.
param([ValidateSet('base','crash','vuoto')][string]$Scenario = 'base')

$dir = $PSScriptRoot
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $java = 'java' }
if (-not (Test-Path "$dir\target\bancaRing.jar")) { throw "Manca target\bancaRing.jar: esegui prima 'mvn package'" }

$tx = @{ 1 = ''; 2 = 'w200'; 3 = 'd100'; 4 = 'w500' }
if ($Scenario -eq 'crash') { $tx[3] = 'd100 crash' }
if ($Scenario -eq 'vuoto') { $tx = @{ 1 = ''; 2 = ''; 3 = ''; 4 = '' } }

Add-Type -AssemblyName System.Windows.Forms
$wa = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$w = [int]($wa.Width / 2); $h = [int]($wa.Height / 2)
# angolo (colonna, riga) di ogni finestra
$pos = @{ 1 = @(0,0); 2 = @(1,0); 3 = @(1,1); 4 = @(0,1) }

# Ogni finestra si sposta da sola con GetConsoleWindow/MoveWindow.
$move = 'Add-Type ''using System;using System.Runtime.InteropServices;public class W{[DllImport("kernel32.dll")]public static extern IntPtr GetConsoleWindow();[DllImport("user32.dll")]public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int h2,bool r);}''; '

foreach ($id in 4,3,2,1) {
    $x = $wa.X + $pos[$id][0] * $w; $y = $wa.Y + $pos[$id][1] * $h
    $wait = if ($id -eq 1) { "Read-Host 'Premi Invio per avviare ATM1 (crea il token)' | Out-Null; " } else { '' }
    $c = $move + "[W]::MoveWindow([W]::GetConsoleWindow(),$x,$y,$w,$h,`$true) | Out-Null; " +
         "`$Host.UI.RawUI.WindowTitle='ATM$id'; Set-Location '$dir'; $wait& '$java' -jar target\bancaRing.jar $id $($tx[$id])"
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($c))
    Start-Process conhost.exe -ArgumentList "powershell.exe -NoExit -EncodedCommand $enc"
    Start-Sleep -Milliseconds 500
}
