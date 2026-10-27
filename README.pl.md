# Flipper Claude Bridge

Zadawaj pytania Claude'owi z Flippera Zero. Flipper nie ma Wi-Fi, więc pośrednikiem jest telefon z Androidem: Flipper wysyła pytanie przez Bluetooth LE lub USB, telefon pyta API Claude'a i odsyła odpowiedź na ekran Flippera.

English version: [README.md](README.md)

## Funkcje

- Czat z Claude'em na ekranie Flippera: klawiatura ekranowa, pasek z czatem nad polem wpisywania, przewijanie.
- Bluetooth LE i USB. Gdy Flipper jest podłączony do telefonu kablem USB, automatycznie używane jest USB, nawet jeśli Bluetooth też jest włączony. Bez USB działa Bluetooth.
- Podpowiedzi słów i zdań w trakcie pisania po polsku i angielsku (do wyboru: angielski, polski albo oba naraz).
  - Wbudowane słowniki (ok. 9000 słów na język, po 216 KB) na karcie SD.
  - Aplikacja uczy się z tego, co wysyłasz. Najpierw podpowiada słowa z Twojej historii. Całe zdanie podpowiada dopiero, gdy użyłeś go co najmniej 2 razy.
  - Polskie litery są dopasowywane dokładnie (`cz` podpowie `cześć`).
  - Przytrzymaj OK (albo wybierz klawisz `Tab` na ekranie), aby przyjąć podpowiedź.
- Dwie zapisane rozmowy. Nowy czat przy zajętych obu miejscach zastępuje najstarszy. Każdy czat ma limit 500 KB.
- Klucz API w telefonie jest szyfrowany w Android Keystore (AES-256-GCM).
- Usługa w telefonie działa przy wygaszonym ekranie, bez wake locków, bez odpytywania i bez ciągłego skanowania, więc prawie nie zużywa baterii.
- Claude jest proszony o bardzo krótkie odpowiedzi w czystym tekście. Odpowiada po polsku lub angielsku (w języku pytania), a przy innych językach po angielsku.

## Struktura repozytorium

| Ścieżka | Zawartość |
| --- | --- |
| `flipper-app/` | Aplikacja na Flippera Zero (C, `ufbt`) |
| `android-app/` | Aplikacja na Androida (Kotlin, Jetpack Compose) |
| `release/` | Gotowy `claude_bridge.fap` (ze słownikami w środku) |
| `tools/make_dict.py` | Generator słowników |
| `.github/workflows/` | GitHub Actions budujące APK i `.fap` |

Gałęzie: `main` to stabilna pierwsza wersja, `beta` dostaje nowe zmiany do testów.

## Instalacja

### Flipper

1. Skopiuj jeden plik `claude_bridge.fap` do `SD:/apps/Tools/`.
2. Uruchom `Apps -> Tools -> Claude Bridge`. Przy pierwszym starcie aplikacja sama rozpakuje słowniki zapisane w pliku `.fap` do `SD:/apps_data/claude_bridge/dict/`.

Albo zbuduj samodzielnie: `cd flipper-app && ufbt` (lub `ufbt launch` przy podłączonym Flipperze).

### Android

Pobierz APK z uruchomienia workflow `Build Android APK` w GitHub Actions (artefakt `flipper-claude-bridge-apk`) i zainstaluj. Lokalna budowa: JDK 17 i Android SDK, potem `gradle :app:assembleDebug` w `android-app/`.

## Użycie

1. W telefonie: otwórz aplikację, nadaj uprawnienia, wpisz klucz API Claude'a i naciśnij `Save key`, potem `Start`.
2. Na Flipperze: uruchom Claude Bridge.
3. Bluetooth: w telefonie naciśnij `Scan for Flipper` i wybierz swojego Flippera (lista zawiera też sparowane urządzenia; Flipper już połączony z telefonem się nie reklamuje, więc widać go tylko na liście sparowanych). Zaakceptuj parowanie (Flipper pokaże PIN). Później połączenie wraca samo.
4. USB: podłącz Flippera do telefonu kablem USB-C (OTG). Zaakceptuj uprawnienie USB i wybierz "zawsze". Potem USB jest używane automatycznie.
5. Na Flipperze wybierz `Chat` i pisz.

### Sterowanie na Flipperze

| Przycisk | Akcja |
| --- | --- |
| Krzyżak | Poruszanie po klawiaturze. W górę z górnego rzędu wybiera pasek czatu, potem góra/dół przewija |
| OK | Wciśnięcie klawisza |
| Przytrzymaj OK | Przyjęcie podpowiedzi |
| Back | Kasowanie znaku. Przy pustym polu: powrót do menu |
| Przytrzymaj Back | Powrót do menu |

Dolny rząd klawiatury: warstwa (`abc` / `ABC` jedna litera / `PL1` polskie litery, cyfry, symbole), `Del`, `Space`, `Tab` (przyjmij podpowiedź), `Send`.

Polskie litery wpisujesz na warstwie `PL1`. Czcionka ekranu Flippera nie ma polskich liter, więc na Flipperze wyświetlają się bez ogonków, ale do Claude'a trafia tekst z polskimi znakami.

### Język

Oficjalny firmware Flippera nie udostępnia aplikacjom ustawienia języka interfejsu, więc język podpowiedzi ustawia się w aplikacji (`Settings -> Hints language`: English, Polish, Both). Domyślnie jest angielski. Interfejs aplikacji jest po angielsku.

## Jak to działa

```
Aplikacja Flipper  <-- BLE serial / USB CDC -->  Usługa Android  --HTTPS-->  API Claude
```

Oba łącza używają tego samego protokołu liniowego (UTF-8, jedna wiadomość na linię, `\n` zapisywane jako `\\n`). Tabela komunikatów jest w [README.md](README.md).

Flipper wybiera USB, gdy telefon otworzył port USB, w przeciwnym razie Bluetooth.

## Bezpieczeństwo

- Klucz API jest zapisany zaszyfrowany kluczem Android Keystore, którego nie da się wyeksportować. Kopie zapasowe są wyłączone.
- Klucz jest wysyłany tylko do `https://api.anthropic.com`.
- Historia czatu na Flipperze leży na karcie SD jako zwykły tekst, jak wszystko inne na niej.
- Łącze Bluetooth używa parowania Flippera z kodem PIN.
