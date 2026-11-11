# Apre 4 finestre PowerShell (una per ATM) e avvia i nodi.
# Uso:  .\demo.ps1 [-Scenario base|crash]
#   base  = esempio del testo: ATM2 w200, ATM3 d100, ATM4 w500  (1000 -> 800 -> 900 -> 400)
#   crash = come base, ma ATM3 muore con il token: si vede rilevazione, rigenerazione e anello a 3 nodi
# Richiede: target\bancaRing.jar (mvn package) e Java 25 nel PATH (o JAVA_HOME impostato).
param([ValidateSet('base','crash')][string]$Scenario = 'base')

$dir = $PSScriptRoot
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $java = 'java' }
if (-not (Test-Path "$dir\target\bancaRing.jar")) { throw "Manca target\bancaRing.jar: esegui prima 'mvn package'" }

$tx = @{ 1 = ''; 2 = 'w200'; 3 = 'd100'; 4 = 'w500' }
if ($Scenario -eq 'crash') { $tx[3] = 'd100 crash' }

# ATM1 per ultimo: crea il token e deve trovare gli altri nodi gia' in ascolto.
foreach ($id in 4,3,2,1) {
    $cmd = "`$Host.UI.RawUI.WindowTitle='ATM$id'; Set-Location '$dir'; & '$java' -jar target\bancaRing.jar $id $($tx[$id])"
    Start-Process powershell -ArgumentList '-NoExit','-Command',$cmd
    Start-Sleep -Milliseconds 700
}
