#include <Arduino.h>
#include <HijelHID_BLEKeyboard.h>

/**
 * Controller Auto BMW iDrive / Media - ESP32-C3-WROOM-02
 * 
 * Configurație Pini:
 *   - Encoder Canal A : GPIO 0 (INPUT_PULLUP, activ LOW)
 *   - Encoder Canal B : GPIO 1 (INPUT_PULLUP, activ LOW)
 *   - Buton Encoder SW: GPIO 3 (INPUT_PULLUP, activ LOW) -> Scurt: ENTER / Lung: F12 (Launcher Return)
 *   - Buton Extern 1  : GPIO 4 (INPUT_PULLUP, activ LOW) -> PLAY / PAUSE
 *   - Buton Extern 2  : GPIO 5 (INPUT_PULLUP, activ LOW) -> PREVIOUS TRACK
 *   - Buton Extern 3  : GPIO 6 (INPUT_PULLUP, activ LOW) -> NEXT TRACK
 *   - USB CDC Nativ   : GPIO 18 (D-), GPIO 19 (D+)
 * 
 * Citire Encoder:
 *   - Shift-Register Debouncer eșantionat la 400 microsecunde în loop() (fără întreruperi, fără tabele)
 */

// Pini definiți
constexpr uint8_t PIN_ENC_A      = 0;
constexpr uint8_t PIN_ENC_B      = 1;
constexpr uint8_t PIN_BTN_SW     = 3;
constexpr uint8_t PIN_BTN_PLAY   = 4;
constexpr uint8_t PIN_BTN_PREV   = 5;
constexpr uint8_t PIN_BTN_NEXT   = 6;

// Instanță Bluetooth HID Keyboard
HijelHID_BLEKeyboard bleKeyboard("BMW iDrive", "BMW", 100);

// ============================================================================
// VARIABILE ENCODER (Shift-Register Debouncer la 400 us)
// ============================================================================
uint8_t histA = 0xFF, histB = 0xFF;
bool cleanA = HIGH, cleanB = HIGH, lastCleanA = HIGH;
unsigned long lastSampleUs = 0;
bool lastBleConnected = false;

// ============================================================================
// STRUCTURĂ ȘI VARIABILE PENTRU BUTOANE (Debounce non-blocant)
// ============================================================================
struct SimpleButton {
    uint8_t pin;
    bool lastReading;
    bool stableState;
    unsigned long lastEdgeMs;
};

SimpleButton btnPlay = { PIN_BTN_PLAY, HIGH, HIGH, 0 };
SimpleButton btnPrev = { PIN_BTN_PREV, HIGH, HIGH, 0 };
SimpleButton btnNext = { PIN_BTN_NEXT, HIGH, HIGH, 0 };

// Butonul SW (ax encoder) are suport pentru apăsare scurtă (ENTER) și lungă (F12)
SimpleButton btnSw = { PIN_BTN_SW, HIGH, HIGH, 0 };
unsigned long swPressStartMs = 0;
bool swLongPressHandled = false;

constexpr unsigned long BTN_DEBOUNCE_MS = 35;
constexpr unsigned long SW_LONG_PRESS_MS = 600;

// ============================================================================
// FUNCȚII TRIMITERE COMENZI HID
// ============================================================================
void sendRotaryRight() {
    Serial.println("[ENC] Rotire DREAPTA -> TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(KEY_TAB);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendRotaryLeft() {
    Serial.println("[ENC] Rotire STANGA -> SHIFT + TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(KEY_TAB, KEY_MOD_LSHIFT);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendEnterKey() {
    Serial.println("[BTN SW] Apasare Scurta -> ENTER");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(KEY_RETURN);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendF12Key() {
    Serial.println("[BTN SW] Apasare Lunga -> F12 (Home Return)");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(KEY_F12);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendMediaPlayPause() {
    Serial.println("[BTN 1] Media -> PLAY / PAUSE");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(MEDIA_PLAY_PAUSE);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendMediaPrevious() {
    Serial.println("[BTN 2] Media -> PREVIOUS TRACK");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(MEDIA_PREV_TRACK);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

void sendMediaNext() {
    Serial.println("[BTN 3] Media -> NEXT TRACK");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.tap(MEDIA_NEXT_TRACK);
    } else {
        Serial.println("  [AVERTISMENT] Comanda netransmisa: telefonul NU este conectat prin Bluetooth!");
    }
}

// ============================================================================
// ACTUALIZARE BUTON SIMPLU
// ============================================================================
void checkButton(SimpleButton &btn, unsigned long nowMs, void (*onPress)()) {
    bool raw = digitalRead(btn.pin);
    if (raw != btn.lastReading) {
        btn.lastEdgeMs = nowMs;
        btn.lastReading = raw;
    }

    if ((nowMs - btn.lastEdgeMs) >= BTN_DEBOUNCE_MS) {
        if (raw != btn.stableState) {
            btn.stableState = raw;
            // Activ pe LOW (apăsat)
            if (btn.stableState == LOW) {
                onPress();
            }
        }
    }
}

// ============================================================================
// ACTUALIZARE BUTON SW CU SUPORT PENTRU CLICK SCURT / LUNG
// ============================================================================
void checkSwButton(unsigned long nowMs) {
    bool raw = digitalRead(btnSw.pin);
    if (raw != btnSw.lastReading) {
        btnSw.lastEdgeMs = nowMs;
        btnSw.lastReading = raw;
    }

    if ((nowMs - btnSw.lastEdgeMs) >= BTN_DEBOUNCE_MS) {
        if (raw != btnSw.stableState) {
            btnSw.stableState = raw;

            if (btnSw.stableState == LOW) {
                // A fost apăsat
                swPressStartMs = nowMs;
                swLongPressHandled = false;
            } else {
                // A fost eliberat
                if (!swLongPressHandled) {
                    sendEnterKey();
                }
            }
        }
    }

    // Declanșare apăsare lungă dacă a fost ținut apăsat peste prag
    if (btnSw.stableState == LOW && !swLongPressHandled) {
        if (nowMs - swPressStartMs >= SW_LONG_PRESS_MS) {
            swLongPressHandled = true;
            sendF12Key();
        }
    }
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
    Serial.begin(115200);

    // Toți pinii de intrare sunt conectați la GND și folosesc rezistoarele interne INPUT_PULLUP
    pinMode(PIN_ENC_A,    INPUT_PULLUP);
    pinMode(PIN_ENC_B,    INPUT_PULLUP);
    pinMode(PIN_BTN_SW,   INPUT_PULLUP);
    pinMode(PIN_BTN_PLAY, INPUT_PULLUP);
    pinMode(PIN_BTN_PREV, INPUT_PULLUP);
    pinMode(PIN_BTN_NEXT, INPUT_PULLUP);

    // Inițializare citiri encoder
    cleanA = digitalRead(PIN_ENC_A);
    cleanB = digitalRead(PIN_ENC_B);
    lastCleanA = cleanA;
    lastSampleUs = micros();

    bleKeyboard.setTapDelay(20);
    bleKeyboard.setKeyGap(20);
    bleKeyboard.begin();

    Serial.println("=========================================");
    Serial.println(" ESP32-C3 Auto Controller (BMW iDrive)");
    Serial.println(" Firmware initializat cu succes!");
    Serial.println(" Asteptare conexiune Bluetooth BLE...");
    Serial.println("=========================================");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================
void loop() {
    // ------------------------------------------------------------------------
    // 1. REGULA OBLIGATORIE: Shift-Register Debouncer la 400 microsecunde
    // ------------------------------------------------------------------------
    if (micros() - lastSampleUs >= 400) {
        lastSampleUs = micros();
        histA = ((histA << 1) | digitalRead(PIN_ENC_A)) & 0x0F;
        histB = ((histB << 1) | digitalRead(PIN_ENC_B)) & 0x0F;

        if (histA == 0x00) cleanA = LOW;
        else if (histA == 0x0F) cleanA = HIGH;

        if (histB == 0x00) cleanB = LOW;
        else if (histB == 0x0F) cleanB = HIGH;

        if (cleanA == LOW && lastCleanA == HIGH) {
            if (cleanB == HIGH) {
                // Rotire DREAPTA (+)
                sendRotaryRight();
            } else {
                // Rotire STANGA (-)
                sendRotaryLeft();
            }
        }
        lastCleanA = cleanA;
    }

    // ------------------------------------------------------------------------
    // 2. PROCESARE BUTOANE (Non-blocant, rulează la fiecare ciclu de loop)
    // ------------------------------------------------------------------------
    unsigned long nowMs = millis();
    checkSwButton(nowMs);
    checkButton(btnPlay, nowMs, sendMediaPlayPause);
    checkButton(btnPrev, nowMs, sendMediaPrevious);
    checkButton(btnNext, nowMs, sendMediaNext);

    // ------------------------------------------------------------------------
    // NOTIFICARE STARE CONEXIUNE BLUETOOTH
    // ------------------------------------------------------------------------
    bool isConn = bleKeyboard.isConnected();
    if (isConn != lastBleConnected) {
        lastBleConnected = isConn;
        if (isConn) {
            Serial.println("\n***************************************************");
            Serial.println("  >>> [BLE] TELEFONUL S-A CONECTAT CU SUCCES! <<<");
            Serial.println("  Toate comenzile (Encoder + Butoane) sunt active!");
            Serial.println("***************************************************\n");
        } else {
            Serial.println("\n>>> [BLE] Telefonul s-a DECONECTAT! Asteptare reconectare... <<<\n");
        }
    }

    // ------------------------------------------------------------------------
    // 3. COMENZI DE TEST PRIN SERIAL CDC (Opțional pt depanare USB)
    // ------------------------------------------------------------------------
    if (Serial.available() > 0) {
        char c = (char)Serial.read();
        if (c == 'r') sendRotaryRight();
        else if (c == 'l') sendRotaryLeft();
        else if (c == 'e') sendEnterKey();
        else if (c == 'f') sendF12Key();
        else if (c == 'p') sendMediaPlayPause();
        else if (c == '<') sendMediaPrevious();
        else if (c == '>') sendMediaNext();
        else if (c == '?') {
            Serial.printf("[Status] BLE Connected: %d (Paired: %d) | A=%d B=%d SW=%d BTN1=%d BTN2=%d BTN3=%d\n",
                          bleKeyboard.isConnected() ? 1 : 0,
                          bleKeyboard.isPaired() ? 1 : 0,
                          digitalRead(PIN_ENC_A), digitalRead(PIN_ENC_B),
                          digitalRead(PIN_BTN_SW), digitalRead(PIN_BTN_PLAY),
                          digitalRead(PIN_BTN_PREV), digitalRead(PIN_BTN_NEXT));
        }
    }
}
