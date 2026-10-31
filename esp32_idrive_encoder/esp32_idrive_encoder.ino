/**
 * BMW E60 iDrive Controller - ESP32-S3 N16R8 BLE HID Firmware
 *
 * Pinout Encoder:
 *   KEY (Push Button) -> GPIO 21 (IO21)
 *   S2                -> GPIO 16 (IO16)
 *   S1                -> GPIO 15 (IO15)
 *
 * Comenzi transmise prin Bluetooth LE HID ("BMW iDrive"):
 *   - Rotire dreapta (CW)  : TAB
 *   - Rotire stanga  (CCW) : SHIFT + TAB
 *   - Apasare scurta (KEY) : ENTER (KEY_RETURN)
 *   - Apasare lunga  (KEY) : F12   (KEY_F12)
 */

#include <Arduino.h>
#include <BleKeyboard.h>

// Pinii encoderului conform legaturilor fizice
constexpr uint8_t PIN_KEY = 21; // KEY -> IO21
constexpr uint8_t PIN_S2  = 16; // S2  -> IO16
constexpr uint8_t PIN_S1  = 15; // S1  -> IO15

// Timpi de debounce si apasare lunga pentru butonul KEY
constexpr unsigned long DEBOUNCE_MS   = 30;
constexpr unsigned long LONG_PRESS_MS = 600;

BleKeyboard bleKeyboard("BMW iDrive", "BMW", 100);

// Tabel de tranzitii Gray-code pentru decodare quadratura fara bounce
static const int8_t DRAM_ATTR ENC_TABLE[16] = {
     0, -1,  1,  0,
     1,  0,  0, -1,
    -1,  0,  0,  1,
     0,  1, -1,  0
};

volatile uint8_t encPrevState = 0x03;
volatile int8_t  encSubStep   = 0;
volatile int16_t encDelta     = 0;

void IRAM_ATTR onEncoderISR() {
    uint8_t s1 = (uint8_t)digitalRead(PIN_S1);
    uint8_t s2 = (uint8_t)digitalRead(PIN_S2);
    uint8_t currState = (s1 << 1) | s2;

    if (currState == encPrevState) {
        return;
    }

    uint8_t idx = (encPrevState << 2) | currState;
    encSubStep += ENC_TABLE[idx];
    encPrevState = currState;

    // La pozitia de repaus (detent = 11), validam pasul complet
    if (currState == 0x03) {
        if (encSubStep >= 2) {
            encDelta++;
        } else if (encSubStep <= -2) {
            encDelta--;
        }
        encSubStep = 0;
    }
}

// Starea butonului KEY (activ LOW cu INPUT_PULLUP)
bool lastRawReading      = HIGH;
bool buttonStableState   = HIGH;
bool longPressTriggered  = false;
unsigned long lastEdgeMs = 0;
unsigned long pressStartMs = 0;

void sendTabForward() {
    Serial.println("[iDrive] Rotire DREAPTA -> TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.write(KEY_TAB);
    }
}

void sendShiftTabBackward() {
    Serial.println("[iDrive] Rotire STANGA -> SHIFT+TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.press(KEY_LEFT_SHIFT);
        bleKeyboard.press(KEY_TAB);
        delay(12);
        bleKeyboard.releaseAll();
    }
}

void sendEnterShortPress() {
    Serial.println("[iDrive] Apasare SCURTA -> ENTER");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.write(KEY_RETURN);
    }
}

void sendF12LongPress() {
    Serial.println("[iDrive] Apasare LUNGA -> F12");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.write(KEY_F12);
    }
}

void setup() {
    Serial.begin(115200);
    delay(100);
    Serial.println("Pornire BMW iDrive BLE Controller (ESP32-S3 N16R8)...");

    pinMode(PIN_KEY, INPUT_PULLUP);
    pinMode(PIN_S1,  INPUT_PULLUP);
    pinMode(PIN_S2,  INPUT_PULLUP);

    // Initializam starea curenta a encoderului
    encPrevState = ((uint8_t)digitalRead(PIN_S1) << 1) | (uint8_t)digitalRead(PIN_S2);

    attachInterrupt(digitalPinToInterrupt(PIN_S1), onEncoderISR, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_S2), onEncoderISR, CHANGE);

    bleKeyboard.begin();
    Serial.println("BLE HID pornit: 'BMW iDrive'");
}

void loop() {
    // 1. Procesam rotirea encoderului acumulata in ISR
    int16_t steps = 0;
    noInterrupts();
    if (encDelta != 0) {
        steps = encDelta;
        encDelta = 0;
    }
    interrupts();

    while (steps > 0) {
        sendTabForward();
        steps--;
        if (steps > 0) delay(15);
    }
    while (steps < 0) {
        sendShiftTabBackward();
        steps++;
        if (steps < 0) delay(15);
    }

    // 2. Procesam butonul KEY (IO21) cu debounce pentru apasare scurta (ENTER) si lunga (F12)
    unsigned long now = millis();
    bool rawReading = digitalRead(PIN_KEY);

    if (rawReading != lastRawReading) {
        lastEdgeMs = now;
        lastRawReading = rawReading;
    }

    if ((now - lastEdgeMs) >= DEBOUNCE_MS) {
        if (rawReading != buttonStableState) {
            buttonStableState = rawReading;

            if (buttonStableState == LOW) {
                // Butonul a fost apasat
                pressStartMs = now;
                longPressTriggered = false;
            } else {
                // Butonul a fost eliberat
                if (!longPressTriggered) {
                    sendEnterShortPress();
                }
            }
        }
    }

    // Declansam F12 imediat ce pragul de apasare lunga este atins (fara a astepta eliberarea)
    if (buttonStableState == LOW && !longPressTriggered && (now - pressStartMs) >= LONG_PRESS_MS) {
        longPressTriggered = true;
        sendF12LongPress();
    }

    delay(2);
}
