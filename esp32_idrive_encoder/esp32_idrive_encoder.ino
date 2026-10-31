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

volatile uint8_t  encPrevState = 0x03;
volatile int8_t   encSubStep   = 0;
volatile int16_t  encDelta     = 0;

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

    // Validam exact 1 pas doar la ajungerea in pozitia de repaus (detent = 11 / 0x03)
    if (currState == 0x03) {
        if (encSubStep <= -2) {
            encDelta++; // Rotire dreapta (CW) -> TAB
        } else if (encSubStep >= 2) {
            encDelta--; // Rotire stanga (CCW) -> SHIFT + TAB
        }
        encSubStep = 0;
    }
}

// Starea butonului KEY (activ LOW cu INPUT_PULLUP)
bool lastRawReading        = HIGH;
bool buttonStableState     = HIGH;
bool longPressTriggered    = false;
unsigned long lastEdgeMs   = 0;
unsigned long pressStartMs = 0;

void sendTabForward() {
    Serial.println("[iDrive] Rotire DREAPTA -> TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.press(KEY_TAB);
        delay(25);
        bleKeyboard.releaseAll();
    }
}

void sendShiftTabBackward() {
    Serial.println("[iDrive] Rotire STANGA -> SHIFT+TAB");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.press(KEY_LEFT_SHIFT);
        bleKeyboard.press(KEY_TAB);
        delay(25);
        bleKeyboard.releaseAll();
    }
}

void sendEnterShortPress() {
    Serial.println("[iDrive] Apasare SCURTA -> ENTER");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.press(KEY_RETURN);
        delay(25);
        bleKeyboard.releaseAll();
    }
}

void sendF12LongPress() {
    Serial.println("[iDrive] Apasare LUNGA -> F12");
    if (bleKeyboard.isConnected()) {
        bleKeyboard.press(KEY_F12);
        delay(25);
        bleKeyboard.releaseAll();
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
    Serial.printf("Initial pins -> S1(IO15)=%d, S2(IO16)=%d, KEY(IO21)=%d\n",
                  digitalRead(PIN_S1), digitalRead(PIN_S2), digitalRead(PIN_KEY));

    attachInterrupt(digitalPinToInterrupt(PIN_S1), onEncoderISR, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_S2), onEncoderISR, CHANGE);

    bleKeyboard.setDelay(10);
    bleKeyboard.begin();
    Serial.println("BLE HID pornit: 'BMW iDrive'");
}

void loop() {
    bleKeyboard.checkConnParamsUpdate();
    unsigned long now = millis();

    // 1. Procesam rotirea encoderului acumulata (1 pas per click in detent 11)
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
        if (steps > 0) delay(20);
    }
    while (steps < 0) {
        sendShiftTabBackward();
        steps++;
        if (steps < 0) delay(20);
    }

    // 2. Procesam butonul KEY (IO21) cu debounce pentru apasare scurta (ENTER) si lunga (F12)
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

    // 3. Diagnostic prin Serial
    if (Serial.available() > 0) {
        char cmd = (char)Serial.read();
        if (cmd == 't') sendTabForward();
        else if (cmd == 'b') sendShiftTabBackward();
        else if (cmd == 'e') sendEnterShortPress();
        else if (cmd == 'f') sendF12LongPress();
        else if (cmd == 'p') {
            Serial.printf("[Status] BLE=%d, S1(IO15)=%d, S2(IO16)=%d, KEY(IO21)=%d, subStep=%d\n",
                          bleKeyboard.isConnected() ? 1 : 0,
                          digitalRead(PIN_S1), digitalRead(PIN_S2), digitalRead(PIN_KEY),
                          (int)encSubStep);
        }
    }

    delay(2);
}
