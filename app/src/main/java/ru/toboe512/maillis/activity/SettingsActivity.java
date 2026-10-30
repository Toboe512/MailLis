package ru.toboe512.maillis.activity;

import static android.content.Context.MODE_PRIVATE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_PASSWORD;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_IMAP_SERVER;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_PORT;
import static ru.toboe512.maillis.consts.Consts.KEY_SMTP_SERVER;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.Properties;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import ru.toboe512.maillis.R;

public class SettingsActivity extends AppCompatActivity {

    private EditText editTextImapServer;
    private EditText editTextImapPort;
    private EditText editTextSmtpServer;
    private EditText editTextSmtpPort;
    private EditText editTextEmailLogin;
    private EditText editTextEmailPassword;
    private Button buttonTestConnection;
    private Button buttonSaveSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Стрелочка назад
        // ИСПРАВЛЕНИЕ: Делаем системные часы и иконки статус-бара ТЁМНЫМИ и читаемыми
        // Получаем управление системными окнами (WindowInsetsController)
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true); // true = тёмные значки (для светлого фона), false = белые значки

        // 1. Настраиваем Toolbar и кнопку "Назад"
        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        //   Помним наш надежный хак: вешаем прямой клик на стрелочку навигации
        toolbar.setNavigationOnClickListener(v -> finish());

        // Связываем переменные с элементами интерфейса XML
        editTextImapServer = findViewById(R.id.editTextImapServer);
        editTextImapPort = findViewById(R.id.editTextImapPort);
        editTextSmtpServer = findViewById(R.id.editTextSmtpServer);
        editTextSmtpPort = findViewById(R.id.editTextSmtpPort);
        editTextEmailLogin = findViewById(R.id.editTextEmailLogin);
        editTextEmailPassword = findViewById(R.id.editTextEmailPassword);
        buttonTestConnection = findViewById(R.id.buttonTestConnection);
        buttonSaveSettings = findViewById(R.id.buttonSaveSettings);

        // Загружаем ранее сохраненные данные при открытии экрана
        loadSavedSettings();

        // Кнопка "Сохранить"
        buttonSaveSettings.setOnClickListener(v -> saveSettings());

        // РЕАЛИЗАЦИЯ КНОПКИ: Проверить соединение
        buttonTestConnection.setOnClickListener(v -> {
            // Считываем то, что введено в поля прямо сейчас
            final String imapHost = editTextImapServer.getText().toString().trim();
            final String imapPort = editTextImapPort.getText().toString().trim();
            final String smtpHost = editTextSmtpServer.getText().toString().trim();
            final String smtpPort = editTextSmtpPort.getText().toString().trim();
            final String user = editTextEmailLogin.getText().toString().trim();
            final String pass = editTextEmailPassword.getText().toString().trim();

            // Валидация полей перед запуском сети
            if (user.isEmpty() || pass.isEmpty() || imapHost.isEmpty() || smtpHost.isEmpty()) {
                Toast.makeText(SettingsActivity.this, "Пожалуйста, заполните все поля!", LENGTH_LONG).show();
                return;
            }

            Toast.makeText(SettingsActivity.this, "Начинаем проверку конфигурации...", LENGTH_SHORT).show();

            // Запускаем сетевой тест на фоновом потоке
            testMailConnectionOverNetwork(imapHost, imapPort, smtpHost, smtpPort, user, pass);
        });
    }

    // Обработка нажатия на стрелочку "Назад" в верхнем углу
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher(); // Закрывает экран настроек и возвращает пользователя назад
        return true;
    }


    // Фоновый метод тестирования IMAP + SMTP
    private void testMailConnectionOverNetwork(final String imapHost, final String imapPort,
                                               final String smtpHost, final String smtpPort,
                                               final String user, final String pass) {
        new Thread(() -> {
            boolean imapSuccess = false;
            boolean smtpSuccess = false;
            String errorMessage = EMPTI_STRING;

            // --- ШАГ 1: ТЕСТИРУЕМ ВХОДЯЩУЮ ПОЧТУ (IMAP) ---
            try {
                var imapProps = new Properties();
                String protocol = imapHost.contains("pop") ? "pop3s" : "imaps";

                imapProps.put("mail.store.protocol", protocol);
                imapProps.put("mail." + protocol + ".host", imapHost);
                imapProps.put("mail." + protocol + ".port", imapPort);
                imapProps.put("mail." + protocol + ".timeout", "10000"); // 10 сек лимит
                imapProps.put("mail." + protocol + ".connectiontimeout", "10000");
                imapProps.put("mail." + protocol + ".ssl.trust", "*");
                imapProps.put("mail." + protocol + ".socketFactory.class", "javax.net.ssl.SSLSocketFactory");

                Session imapSession = Session.getInstance(imapProps, null);
                Store store = imapSession.getStore(protocol);

                // Пробуем физически подключиться к ящику
                store.connect(imapHost, user, pass);
                store.close();
                imapSuccess = true;

            } catch (AuthenticationFailedException e) {
                errorMessage += "Ошибка IMAP: Неверный логин или Пароль приложения! (Проверьте, что сгенерировали 16-значный код в браузере)\n";
            } catch (MessagingException e) {
                errorMessage += "Ошибка IMAP (Сеть/Порты): " + e.getLocalizedMessage() + "\n";
            } catch (Exception e) {
                errorMessage += "Ошибка IMAP: " + e.getMessage() + "\n";
            }

            // --- ШАГ 2: ТЕСТИРУЕМ ИСХОДЯЩУЮ ПОЧТУ (SMTP) ---
            try {
                var smtpProps = new Properties();
                smtpProps.put("mail.smtp.host", smtpHost);
                smtpProps.put("mail.smtp.port", smtpPort);
                smtpProps.put("mail.smtp.auth", "true");
                smtpProps.put("mail.smtp.timeout", "10000");
                smtpProps.put("mail.smtp.connectiontimeout", "10000");
                smtpProps.put("mail.smtp.ssl.enable", "true");
                smtpProps.put("mail.smtp.ssl.trust", "*");
                smtpProps.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                smtpProps.put("mail.smtp.socketFactory.port", smtpPort);

                Session smtpSession = Session.getInstance(smtpProps, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                });

                Transport transport = smtpSession.getTransport("smtp");
                // Пробуем физически подключиться к SMTP шлюзу
                transport.connect(smtpHost, user, pass);
                transport.close();
                smtpSuccess = true;
            } catch (Exception e) {
                errorMessage += "Ошибка SMTP: " + e.getLocalizedMessage();
            }

            // --- ШАГ 3: ВЫВОДИМ РЕЗУЛЬТАТ НА ЭКРАН (Главный поток) ---
            final boolean finalImap = imapSuccess;
            final boolean finalSmtp = smtpSuccess;
            final String finalError = errorMessage;

            runOnUiThread(() -> {
                if (finalImap && finalSmtp) {
                    Toast.makeText(SettingsActivity.this, "✨ Соединение успешно установлено! Все сервера работают.", LENGTH_LONG).show();
                } else {
                    // Показываем детальный текст ошибки, если где-то споткнулись
                    Toast.makeText(SettingsActivity.this, "❌ Ошибка авторизации:\n" + finalError, LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // Метод сохранения данных в память устройства
    private void saveSettings() {
        var sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // Записываем строковые значения из полей ввода
        editor.putString(KEY_IMAP_SERVER, editTextImapServer.getText().toString().trim());
        editor.putString(KEY_IMAP_PORT, editTextImapPort.getText().toString().trim());
        editor.putString(KEY_SMTP_SERVER, editTextSmtpServer.getText().toString().trim());
        editor.putString(KEY_SMTP_PORT, editTextSmtpPort.getText().toString().trim());
        editor.putString(KEY_EMAIL_LOGIN, editTextEmailLogin.getText().toString().trim());
        editor.putString(KEY_EMAIL_PASSWORD, editTextEmailPassword.getText().toString().trim());

        // Считываем то, что введено в поля прямо сейчас
        final String imapHost = editTextImapServer.getText().toString().trim();
        final String imapPort = editTextImapPort.getText().toString().trim();
        final String smtpHost = editTextSmtpServer.getText().toString().trim();
        final String smtpPort = editTextSmtpPort.getText().toString().trim();
        final String user = editTextEmailLogin.getText().toString().trim();
        final String pass = editTextEmailPassword.getText().toString().trim();

        // Валидация полей перед запуском сети
        if (user.isEmpty() || pass.isEmpty() || imapHost.isEmpty() ||
                smtpHost.isEmpty() || imapPort.isEmpty() || smtpPort.isEmpty()) {
            Toast.makeText(SettingsActivity.this, "Пожалуйста, заполните все поля!", LENGTH_LONG)
                    .show();
            return;
        }

        // Применяем изменения асинхронно на фоновом потоке
        editor.apply();

        Toast.makeText(this, "Настройки успешно сохранены!", LENGTH_SHORT).show();
        finish(); // Закрываем экран и возвращаемся на главный
    }

    // Метод загрузки данных из памяти при старте экрана
    private void loadSavedSettings() {
        var sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Второй аргумент — это значение по умолчанию, если в памяти еще ничего нет
        editTextImapServer.setText(sharedPreferences.getString(KEY_IMAP_SERVER, "imap.mail.ru"));
        editTextImapPort.setText(sharedPreferences.getString(KEY_IMAP_PORT, "993"));
        editTextSmtpServer.setText(sharedPreferences.getString(KEY_SMTP_SERVER, "smtp.mail.ru"));
        editTextSmtpPort.setText(sharedPreferences.getString(KEY_SMTP_PORT, "465"));

        // Логин и пароль оставляем пустыми по умолчанию
        editTextEmailLogin.setText(sharedPreferences.getString(KEY_EMAIL_LOGIN, EMPTI_STRING));
        editTextEmailPassword.setText(sharedPreferences.getString(KEY_EMAIL_PASSWORD, EMPTI_STRING));
    }

}
