# TODO — analiza projektu `mobile-videdit`

## 1) Cel programu i aktualny zakres

Aplikacja `mobile-videdit` to mobilny edytor wideo na Androida, którego główne zadania to:
- wczytanie jednego lub dwóch plików wideo,
- transkodowanie pojedynczego klipu (zmiana rozdzielczości/FPS/bitrate),
- kadrowanie przestrzenne (crop) i przycinanie czasowe (trim),
- łączenie dwóch materiałów w jeden plik wynikowy,
- zapis i udostępnienie efektu.

Aktualna architektura jest czytelnie rozdzielona na warstwę UI (`MainActivity`), warstwę stanu (`VideoEditorViewModel`) i warstwę silnika FFmpeg (`VideoProcessor`).

---

## 2) Najważniejsze obserwacje techniczne

### Mocne strony
- Dobra separacja odpowiedzialności pomiędzy UI, logiką stanu i przetwarzaniem FFmpeg.
- Użycie `ViewModel` + `LiveData` + korutyn, co upraszcza zarządzanie cyklem życia ekranu.
- Podstawowa walidacja parametrów trim po stronie logiki biznesowej.
- Dobre podejście do kompatybilności wyjścia (`libx264` + `aac`, `+faststart`).

### Obszary ryzyka
- Brak testów jednostkowych i testów integracyjnych dla budowania komend FFmpeg.
- Brak jednolitej internacjonalizacji tekstów (część komunikatów jest „na sztywno” w kodzie).
- Brak raportowania postępu z FFmpeg (UI pokazuje stan binarny, bez procentu/progresu etapów).
- Potencjalne problemy wydajności/pamięci przy dużych plikach i braku strategii cleanup cache.
- Brak twardej walidacji crop względem realnych wymiarów materiału (np. zakresy X/Y/W/H).
- Klasa `MainActivity` jest dość rozbudowana i skupia zbyt dużo logiki UI.

---

## 3) TODO priorytetowe (P0 — krytyczne dla stabilności)

- [ ] **Dodać testy jednostkowe dla `VideoProcessor` (parsery i builder komendy).**
  - Zakres: `parseBitrateValue`, `parseFpsValue`, `parseResolutionFilter`, składanie filtrów crop+scale, trim `-ss/-to`.
  - Cel: wyeliminowanie regresji przy rozwoju opcji edycji.

- [ ] **Dodać pełną walidację danych wejściowych przed uruchomieniem FFmpeg.**
  - Sprawdzać: dodatnie wartości crop, granice crop względem szer./wys. źródła, sensowność trim względem długości materiału.
  - Ujednolicić błędy walidacji do jednego modułu (np. `VideoParamsValidator`).

- [ ] **Zaimplementować bezpieczne i deterministyczne czyszczenie plików tymczasowych.**
  - Dotyczy kopii URI w cache i plików pomocniczych.
  - Dodać politykę retencji (np. cleanup przy starcie aplikacji + limit rozmiaru cache).

- [ ] **Dodać mechanizm anulowania trwającego procesu FFmpeg.**
  - Wymagany przy długich transkodowaniach.
  - UI: przycisk „Anuluj”, stan przejściowy i komunikat końcowy.

---

## 4) TODO wysoki priorytet (P1 — jakość produktu)

- [ ] **Przenieść wszystkie teksty użytkownika do `strings.xml`.**
  - Obecnie część tekstów jest osadzona w Kotlinie.
  - Przygotować podstawę pod lokalizacje (PL/EN).

- [ ] **Dodać raportowanie postępu przetwarzania.**
  - Odczyt postępu z logów/statystyk FFmpeg i mapowanie na procent/etap.
  - UI: pasek postępu + przybliżony ETA.

- [ ] **Refaktoryzacja `MainActivity` do podejścia bardziej modułowego.**
  - Wydzielenie obsługi formularza parametrów i sekcji merge do osobnych komponentów.
  - Rozważyć migrację do architektury MVVM + `StateFlow`.

- [ ] **Usprawnić obsługę błędów technicznych.**
  - Lepsze mapowanie typowych błędów FFmpeg na czytelne komunikaty użytkownika.
  - Logowanie diagnostyczne dla R&D (np. skrócone logi sesji + identyfikator zadania).

---

## 5) TODO średni priorytet (P2 — rozwój funkcjonalny)

- [ ] **Rozszerzyć operacje edycyjne o rotację i zmianę proporcji obrazu.**
- [ ] **Dodać batch processing (kolejka wielu zadań).**
- [ ] **Dodać profile eksportu (np. YouTube/Instagram/TikTok).**
- [ ] **Dodać opcję zachowania/konwersji ścieżek audio (np. bitrate audio, mute).**
- [ ] **Dodać metadane zadania i historię eksportów w aplikacji.**

---

## 6) TODO techniczne (utrzymanie i DevEx)

- [ ] **Dodać pipeline CI (lint + testy + build debug).**
- [ ] **Włączyć statyczną analizę jakości (detekt/ktlint).**
- [ ] **Dodać testy instrumentacyjne dla kluczowych ścieżek UI.**
- [ ] **Utworzyć dokument „FFmpeg command matrix” z przypadkami wejścia/wyjścia.**
- [ ] **Dodać metryki wydajności (czas transkodowania, rozmiar wejścia/wyjścia).**

---

## 7) Proponowana kolejność realizacji

1. Testy jednostkowe + walidacja parametrów (P0).
2. Cleanup cache + anulowanie procesu (P0).
3. Internacjonalizacja i poprawa komunikatów błędów (P1).
4. Progress tracking i refaktoryzacja UI (P1).
5. Rozszerzenia funkcjonalne i automatyzacja CI (P2).

---

## 8) Definicja ukończenia (DoD) dla pierwszego etapu

- Pokrycie testami krytycznych fragmentów budowania komend FFmpeg.
- Brak uruchamiania FFmpeg dla nieprawidłowych parametrów wejściowych.
- Widoczna i udokumentowana strategia zarządzania plikami tymczasowymi.
- Użytkownik może anulować długie przetwarzanie bez restartu aplikacji.
- Wszystkie komunikaty użytkownika trzymane w zasobach lokalizacyjnych.
