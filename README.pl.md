# Flipper AI Bridge

Zadawaj pytania AI z Flippera Zero. Flipper wysyła zapytania do pośrednika (twojego telefonu z Androidem). Flipper wysyła pytanie przez Bluetooth Low Energy lub USB, telefon pyta API Anthropic (modele Claude) i odsyła odpowiedź na ekran Flippera.

<img src="./public/flipper-ai-banner.gif">

<div align="center">
<img src="https://img.shields.io/badge/FlipperAI-blue">
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="License: MIT"></a>
<a href="https://github.com/Nahida4479/Flipper-AI-Bridge/releases"><img src="https://img.shields.io/github/v/release/Nahida4479/Flipper-AI-Bridge?include_prereleases" alt="Latest release"></a>
</div>

## Funkcje

- Czat na ekranie Flippera: klawiatura ekranowa, pasek z czatem nad polem wpisywania, przewijanie.
- Bluetooth Low Energy i USB. Gdy Flipper jest podłączony do telefonu kablem USB, automatycznie używane jest USB, nawet jeśli Bluetooth też jest włączony.
- Podpowiedzi słów i zdań po polsku i angielsku (angielski, polski albo oba). Uczą się z tego, co piszesz. Przytrzymaj **OK**, aby przyjąć podpowiedź.
- Dwie zapisane rozmowy. Nowy czat przy zajętych obu miejscach zastępuje najstarszy. Każdy czat ma limit 500 KB.
- Aplikacja na Androida: klucz API zaszyfrowany (Android Keystore, AES-256-GCM), wybór modelu (Sonnet 5.5, Haiku 5.5, Opus 5.5), wybór effort (low, medium, high), działa przy wygaszonym ekranie.
- Aktualizacje aplikacji w telefonie z weryfikacją SHA-256 oraz instalacja jednym przyciskiem dla aplikacji zainstalowanej na Flipperze.
- English version: [README.md](README.md)

## Jak to działa

```mermaid
flowchart LR
    F["Flipper Zero<br/>aplikacja AI Bridge"] <-->|"USB (priorytet)<br/>albo BLE"| P["Telefon z Androidem<br/>usługa w tle"]
    P -->|HTTPS| A[("API Anthropic")]
    A -->|odpowiedź| P
```

## Struktura repozytorium

| Ścieżka | Zawartość |
| --- | --- |
| `flipper-app/` | Aplikacja na Flippera Zero |
| `android-app/` | Aplikacja na Androida |
| `release/` | Gotowy `ai_bridge.fap` |
| `tools/` | Generator słów pomocniczych i skrypt klucza podpisu |
| `.github/workflows/` | Action budujące `APK` i `.fap` oraz publikujące wydania |

Gałęzie: `main` to wersja stabilna, `beta` dostaje nowe zmiany do testów.

## Instalacja

1. Pobierz `flipper-ai-bridge.apk` ze strony [Releases](https://github.com/Nahida4479/Flipper-AI-Bridge/releases) i zainstaluj na telefonie. 
2. Otwórz aplikację w telefonie, nadaj uprawnienia, wpisz klucz API Anthropic i naciśnij `Start`.
3. Wgraj aplikację na Flippera na jeden z dwóch sposobów:
   - w aplikacji w telefonie naciśnij `Install on Flipper` (Flipper połączony przez USB lub Bluetooth), albo
   - skopiuj `ai_bridge.fap` do `SD:/apps/<folder>/` przez qFlipper lub lab.flipper.net. Mechanizm słów pomocniczych jest wbudowany w `.fap` i Flipper rozpakowuje je przy pierwszym starcie.
4. **Bluetooth:** na telefonie naciśnij `Scan for Flipper` i wybierz Flippera z listy.
5. **USB:** podłącz Flippera do telefonu. Zaakceptuj jednorazowe uprawnienie USB.
6. Na Flipperze uruchom aplikację i wybierz `Chat`.

### Sterowanie na Flipperze

| Przycisk | Akcja |
| --- | --- |
| Krzyżak | Poruszanie po klawiaturze. |
| OK | Wciśnięcie klawisza |
| Przytrzymaj OK | Przyjęcie podpowiedzi |
| Back | Kasowanie znaku. Przy pustym polu: powrót do menu |
| Przytrzymaj Back | Powrót do menu |

### Język

Oficjalny firmware Flippera nie udostępnia aplikacjom ustawienia języka interfejsu, więc ****język podpowiedzi** ustawia się w aplikacji (`Settings -> Hints language`). **Domyślnie angielski**. Interfejs aplikacji jest po angielsku. AI odpowiada po polsku lub angielsku (w języku pytania), a przy innych językach po angielsku.

## Aktualizacje i sumy kontrolne

Aplikacja w telefonie pyta GitHuba o najnowsze wydanie przy otwarciu (i po `Check for updates`). Wersje beta są uwzględniane tylko po włączeniu tej opcji.

<<<<<<< HEAD
Aplikacja w telefonie pyta GitHuba o najnowsze wydanie przy otwarciu (i po `Check for updates`). Nowa wersja jest proponowana tylko wtedy, gdy ma wyższy numer niż zainstalowana. Wersje beta są uwzględniane tylko po włączeniu opcji.

Przed instalacją plik jest pobierany, a jego SHA-256 porównywane z digestem podanym przez GitHub dla tego pliku oraz z plikiem `SHA256SUMS` z wydania. Jeśli którejś sumy brakuje, są niezgodne albo hash się nie zgadza, nic nie jest instalowane. Wgranie aplikacji na Flippera jest dodatkowo sprawdzane sumą MD5 na Flipperze.

To chroni przed uszkodzonym lub podmienionym plikiem. Nie chroni przed kimś, kto kontroluje to repozytorium, więc dbaj o repozytorium i jego sekrety. Dodatkowo Android odrzuci APK podpisany innym kluczem niż zainstalowana aplikacja.

### Klucz podpisu (potrzebny do aktualizacji w aplikacji)

Android zainstaluje aktualizację tylko, gdy jest podpisana tym samym kluczem co zainstalowana aplikacja. Workflow wydań potrzebuje więc stałego klucza:

1. Uruchom raz `tools/make_keystore.sh` (wymaga `keytool` z dowolnego JDK).
2. Dodaj do repozytorium cztery sekrety, które wypisze (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
3. Przechowuj plik `.jks` w bezpiecznym miejscu. Jego utrata oznacza, że użytkownicy muszą raz odinstalować aplikację, żeby ją zaktualizować.

Bez sekretów APK jest podpisany kluczem tymczasowym i da się go tylko zainstalować od nowa.

## Protokół

Oba łącza przesyłają jedną wiadomość UTF-8 na linię (`\n` w tekście zapisywane jako `\\n`):

| Kierunek | Linia | Znaczenie |
| --- | --- | --- |
| Telefon do Flippera | `H` | Hello, aplikacja w telefonie jest |
| Flipper do telefonu | `P` | Ping, odpowiedź to `H` |
| Flipper do telefonu | `N` | Nowa rozmowa |
| Flipper do telefonu | `U:<tekst>` / `B:<tekst>` | Wcześniejsza wiadomość użytkownika / AI (odtworzenie kontekstu po wznowieniu czatu) |
| Flipper do telefonu | `Q:<lang>:<tekst>` | Pytanie (`lang` to `en`, `pl` albo `both`) |
| Telefon do Flippera | `S:<tekst>` | Status |
| Telefon do Flippera | `A:<kawałek>` | Część odpowiedzi |
| Telefon do Flippera | `R` | Zacznij odpowiedź od nowa (wysyłane, gdy odpowiedź jest dostarczana drugi raz po zerwaniu łącza) |
| Telefon do Flippera | `E` | Koniec odpowiedzi |
| Flipper do telefonu | `K` | Odpowiedź odebrana, telefon może o niej zapomnieć |
| Telefon do Flippera | `X:<tekst>` | Błąd |

Instalacja aplikacji na Flippera używa własnego protokołu RPC Flippera (protobuf, usługa storage), a nie tego protokołu.
=======
Przed instalacją plik jest pobierany, a jego SHA-256 jest porównywane z plikiem `SHA256SUMS` z releases. Jeśli którejś sumy brakuje, są niezgodne albo hash się nie zgadza, nic nie jest instalowane. Wgranie aplikacji na Flippera jest dodatkowo sprawdzane sumą MD5 na Flipperze.
>>>>>>> d3b46e4 (Update README.md)

## Bezpieczeństwo

- Klucz API jest zapisany __zaszyfrowany kluczem Android Keystore__, którego nie da się wyeksportować, pokazywany tylko jako kropki i wysyłany wyłącznie do `https://api.anthropic.com`.
- Historia czatu na Flipperze leży na karcie SD jako zwykły tekst.
- Łącze Bluetooth używa parowania Flippera z kodem PIN.

## Licencja 
Licencja [MIT](./LICENSE)