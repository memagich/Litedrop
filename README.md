# Litedrop za Android

Pandan Windows programu **Litedrop**. Prima fajlove s računara **sam, bez ikakvog pitanja**, i sprema ih u **Downloads**.
Ti na računaru samo desni klik na fajl → *Send to* → *Litedrop*, i fajl se pojavi na telefonu.

Obrnuto: u bilo kojoj aplikaciji (galerija, fajlovi…) *Podijeli* → **Pošalji na PC**. Fajlovi idu u `Downloads\Litedrop` na računaru.

Zahtjevi: Android 10 ili noviji, telefon i računar na istom Wi-Fi-ju. Bez vanjskih biblioteka, pa je APK vrlo mali.

## Kako doći do APK-a

Android SDK nije bio dostupan tamo gdje sam pisao kod, pa APK nije izgrađen. Tri načina:

**A) GitHub Actions (najlakše, ništa ne instaliraš)**
1. Na github.com napravi **privatni** repozitorij.
2. Uploaduj *sadržaj* ove mape (ne mapu samu) tako da `build.gradle` i `app/` budu u korijenu repozitorija.
   Mapa `.github` mora biti uključena (ako je web upload preskoči, dodaj je ručno: *Add file → Create new file*, ime `.github/workflows/build.yml`).
3. Otvori karticu **Actions** → *Build APK* → *Run workflow* (ili se pokrene sam nakon uploada).
4. Kad završi (oko 3 minute), u dnu stranice u **Artifacts** preuzmi `litedrop-apk`, raspakuj, dobiješ `app-release.apk`.

**B) Android Studio:** *File → Open* na ovu mapu, pa *Build → Generate Signed App Bundle or APK*, ili u terminalu `./gradlew assembleRelease`.
APK je u `app/build/outputs/apk/release/`.

**C) Komandna linija** (treba JDK 17 i Android SDK 34): `./gradlew assembleRelease`.

## Prvo pokretanje

1. Instaliraj APK (Android će tražiti dozvolu za instalaciju iz nepoznatih izvora).
2. Na računaru pokreni `litedrop.exe` (otvori se stranica s PIN-om).
3. Na telefonu otvori Litedrop, dozvoli obavijesti, upiši PIN, dodirni **Upari**. Računar se nađe sam. Ako ne, upiši IP s PIN stranice.
4. Dodirni **Isključi uštedu baterije** i dozvoli. Bez toga Android može ugasiti prijem kad je ekran ugašen.
5. Na Xiaomi/HyperOS, Samsung, Huawei i sličnim telefonima dodatno uključi **Autostart** za Litedrop (Postavke → Aplikacije) i u postavkama baterije postavi **Bez ograničenja**.

To su jedina pitanja koja ćeš ikad vidjeti. Poslije toga sve radi samo: u traci obavijesti stoji tihi status ("Povezano s …"), a kad stigne fajl dobiješ obavijest "Primljeno".

## Kako radi

- Aplikacija drži otvorenu vezu s računarom (long-poll) pa novi fajl stiže u trenutku slanja, a ne po rasporedu. U mirovanju je to jedna otvorena veza, pa bi potrošnja trebala biti mala, ali nisam je mogao izmjeriti na telefonu.
- Pokreće se sama nakon paljenja telefona i nakon ažuriranja.
- Ako računar promijeni IP adresu, aplikacija ga ponovo nađe po imenu.
- Sav promet ide preko Wi-Fi-ja (ne troši mobilne podatke), čak i ako je VPN uključen.
- Ako ime fajla već postoji u Downloads, Android dodaje `(1)`.

## Ograničenja

- **APK nije isproban na stvarnom telefonu.** Mrežni dio (pronalazak, uparivanje, primanje, slanje, nastavak veze) je testiran protiv pravog PC servera (`core-test/run_core_test.sh`, 27 provjera), a Android kod se prevodi uz moje pojednostavljene Android potpise (to hvata greške u kodu, ne u ponašanju sistema), ali Android dio (servis, spremanje u Downloads, share meni) nisam mogao pokrenuti. Ako nešto ne radi, pošalji mi poruku greške ili `adb logcat` izlaz.
- Promet nije šifriran (običan HTTP), kao i na PC strani. Za kućni Wi-Fi je u redu, ne koristi na javnom.
- Folderi se ne šalju, samo fajlovi.
- Ključ za potpisivanje (`app/litedrop-release.jks`, lozinka `litedrop`) je u projektu da svaki build ima isti potpis i da se nova verzija može instalirati preko stare. Zato repozitorij drži **privatnim**. Ako ga ipak objaviš, napravi vlastiti ključ (`keytool -genkeypair …`) i promijeni ga u `app/build.gradle`.
- Android 15+ ograničava pokretanje nekih vrsta servisa odmah nakon paljenja telefona. Za ovaj tip (`connectedDevice`) ne bi trebalo važiti, ali nisam to mogao provjeriti. Ako prijem nakon restarta ne krene sam, otvori aplikaciju jednom.

## Struktura

- `app/src/main/java/com/litedrop/core/` čista Java mrežna jezgra (bez Android ovisnosti): `Api`, `Discovery`, `Json`
- `app/src/main/java/com/litedrop/app/` Android dio: `SyncService` (prijem i slanje u pozadini), `Saver` (Downloads), `MainActivity`, `ShareActivity`, `BootReceiver`
- `core-test/` JVM testovi jezgre protiv pravog servera. Pokretanje: `core-test/run_core_test.sh <putanja do litedrop binarke za tvoj OS>`
