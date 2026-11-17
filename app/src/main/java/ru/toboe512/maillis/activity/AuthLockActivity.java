package ru.toboe512.maillis.activity;

import static android.widget.Toast.LENGTH_SHORT;
import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
import static androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;
import static ru.toboe512.maillis.config.Config.KEY_BIOMETRIC_ENABLED;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.Executor;


public class AuthLockActivity extends AppCompatActivity {

    private boolean isKeepOnScreen = true; // Флаг удержания системного сплэш-экрана

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 1. КРИТИЧЕСКИЙ ШАГ: Инициализируем системный SplashScreen ДО super.onCreate!
        // Этот метод сообщает Android 12+, что мы берем управление заставкой в свои руки
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);

        // 2. Приказываем системе удерживать нативный сплэш-экран на дисплее,
        // пока флаг isKeepOnScreen равен true (пока проверяется биометрия)
        splashScreen.setKeepOnScreenCondition(() -> isKeepOnScreen);

        // 3. Вызов setContentView больше НЕ НУЖЕН! Удаляем R.layout.activity_splash
        // Экран не будет моргать, нативная иконка плавно сменится окном BiometricPrompt.

        // Проверяем пользовательские настройки биометрии
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isBiometricRequired = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isBiometricRequired) {
                // Если защита отключена — гасим системный сплэш и мгновенно летим в чаты
                isKeepOnScreen = false;
                navigateToMain();
            } else {
                // Иначе — инициируем вызов датчиков отпечатка/PIN
                checkBiometricAvailability();
            }
        }, 300);
    }

    private void checkBiometricAvailability() {
        var biometricManager = BiometricManager.from(this);
        int authenticators = BIOMETRIC_STRONG | DEVICE_CREDENTIAL;

        if (biometricManager.canAuthenticate(authenticators) == BIOMETRIC_SUCCESS) {
            showBiometricPrompt(authenticators);
        } else {
            Log.w("MailLis_Bio", "На устройстве не настроена блокировка экрана.");
            isKeepOnScreen = false; // Отпускаем системный сплэш
            navigateToMain();
        }
    }

    private void showBiometricPrompt(int authenticators) {
        Executor executor = ContextCompat.getMainExecutor(this);

        var biometricPrompt = new BiometricPrompt(AuthLockActivity.this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Toast.makeText(AuthLockActivity.this, "Вход отменен", LENGTH_SHORT).show();
                        isKeepOnScreen = false;
                        finish();
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        isKeepOnScreen = false; // Отпускаем заставку — верификация пройдена!
                        navigateToMain();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });

        var builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Вход в MailLis")
                .setSubtitle("Подтвердите личность для доступа к P2P-чатам")
                .setAllowedAuthenticators(authenticators);

        if ((authenticators & DEVICE_CREDENTIAL) == 0) {
            builder.setNegativeButtonText("Выход");
        }

        // Вызываем окно сканера. Оно красиво наложится прямо поверх системного логотипа Android!
        biometricPrompt.authenticate(builder.build());
    }

    private void navigateToMain() {
        var intent = new Intent(AuthLockActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}