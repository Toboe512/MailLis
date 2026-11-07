package ru.toboe512.maillis.activity;

import static android.widget.Toast.LENGTH_SHORT;
import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
import static androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;

import static ru.toboe512.maillis.config.Config.KEY_BIOMETRIC_ENABLED;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.config.Config;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Включаем поддержку EdgeToEdge для красивого бесшовного фона
        EdgeToEdge.enable(this);

        // Отображаем лаконичный сплэш-экран (создайте простой layout с вашим логотипом)
        setContentView(R.layout.activity_splash);

        // Добавляем проверку пользовательских настроек из SharedPreferences [source]
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isBiometricRequired = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // УЛЬТИМАТИВНЫЙ ФИКС: Если пользователь выключил защиту в настройках аккаунта —
            // мы мгновенно перенаправляем его в MainActivity, минуя вызов датчиков смартфона!
            if (!isBiometricRequired) {
                navigateToMain();
            } else {
                // Иначе — честно требуем палец, лицо или системный PIN-код
                checkBiometricAvailability();
            }
        }, 300);
    }

    private void checkBiometricAvailability() {
        BiometricManager biometricManager = BiometricManager.from(this);

        // ИСПРАВЛЕНИЕ: Разрешаем сильную биометрию ИЛИ обычный PIN-код/пароль телефона (DEVICE_CREDENTIAL)
        int authenticators = BIOMETRIC_STRONG | DEVICE_CREDENTIAL;

        if (biometricManager.canAuthenticate(authenticators) == BIOMETRIC_SUCCESS) {// Датчики или пароль настроены — выводим диалог
            showBiometricPrompt(authenticators);
        } else {// Если на телефоне вообще отключена какая-либо защита (даже пин-кода нет)
            Log.w("MailLis_Bio", "На устройстве не настроена блокировка экрана.");
            navigateToMain();
        }
    }

    private void showBiometricPrompt(int authenticators) {
        Executor executor = ContextCompat.getMainExecutor(this);

        var biometricPrompt = new BiometricPrompt(SplashActivity.this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Toast.makeText(SplashActivity.this, "Вход отменен", LENGTH_SHORT).show();
                        finish();
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        navigateToMain(); // Успех
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Вход в MailLis")
                .setSubtitle("Подтвердите личность для доступа к P2P-чатам")
                .setAllowedAuthenticators(authenticators); // ИСПРАВЛЕНИЕ: Передаем наш всеядный фильтр

        // ВНИМАНИЕ: Если мы разрешаем вход по PIN-коду (DEVICE_CREDENTIAL),
        // метод setNegativeButtonText вызывать НЕЛЬЗЯ (Android сам нарисует системную кнопку отмены)
        // Поэтому условие:
        if ((authenticators & DEVICE_CREDENTIAL) == 0) {
            builder.setNegativeButtonText("Выход");
        }

        biometricPrompt.authenticate(builder.build());
    }

    private void navigateToMain() {
        var intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish(); // Обязательно уничтожаем Splash, чтобы пользователь не вернулся на него кнопкой "Назад"
    }
}
