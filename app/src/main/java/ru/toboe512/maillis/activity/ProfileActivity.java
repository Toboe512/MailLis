package ru.toboe512.maillis.activity;

import static android.widget.Toast.LENGTH_SHORT;
import static ru.toboe512.maillis.config.Config.DEFAULT_SYNC_INTERVAL_MS;
import static ru.toboe512.maillis.config.Config.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.config.Config.KEY_SYNC_INTERVAL;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.DecimalFormat;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;

public class ProfileActivity extends AppCompatActivity {

    private TextView textViewEmail;
    private TextView textViewPublicKey;
    private TextView textViewCacheSize;
    private Spinner spinnerInterval;
    private Button buttonCopy;
    private Button buttonClearCache;

    private SharedPreferences prefs;
    private CryptoService cryptoService;

    // Массив вариантов для Спиннера (строковые описания)
    private final String[] intervalLabels = {"20 секунд (По умолчанию)", "1 минута", "5 минут", "10 минут"};
    // Соответствующие значения интервалов в миллисекундах
    private final long[] intervalValues = {20000, 60000, 300000, 600000};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            cryptoService = new CryptoServiceImpl(this); // Инициализация криптосервиса Tink
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }

        // Связываем UI
        textViewEmail = findViewById(R.id.textViewProfileEmail);
        textViewPublicKey = findViewById(R.id.textViewPublicKeyBase64);
        textViewCacheSize = findViewById(R.id.textViewCacheSize);
        spinnerInterval = findViewById(R.id.spinnerSyncInterval);
        buttonCopy = findViewById(R.id.buttonCopyKey);
        buttonClearCache = findViewById(R.id.buttonClearCache);

        // 1. ВЫВОДИМ ДАННЫЕ ПРОФИЛЯ
        String myEmail = prefs.getString(KEY_EMAIL_LOGIN, "unknown@mail.ru");
        textViewEmail.setText(myEmail);

        // Получаем ваш публичный ключ Tink в Base64 для обмена с друзьями
        try {
            String myPublicKeyBase64 = cryptoService.getMyPublicKeyBas64();
            textViewPublicKey.setText(myPublicKeyBase64);

            // Обработка копирования ключа в буфер обмена Android
            buttonCopy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("MailLis_P2P_Key", myPublicKeyBase64);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Ключ скопирован в буфер обмена!", LENGTH_SHORT)
                            .show();
                }
            });
        } catch (Exception e) {
            textViewPublicKey.setText("Ошибка генерации/чтения ключа");
            e.printStackTrace();
        }

        // 2. НАСТРОЙКА ВЫБОРА ИНТЕРВАЛА СИНХРОНИЗАЦИИ
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, intervalLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInterval.setAdapter(adapter);

        // Подставляем текущий сохраненный выбор пользователя
        long currentInterval = prefs.getLong(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_MS);
        int defaultPosition = 0;
        for (int i = 0; i < intervalValues.length; i++) {
            if (intervalValues[i] == currentInterval) {
                defaultPosition = i;
                break;
            }
        }
        spinnerInterval.setSelection(defaultPosition);

        // Слушатель смены интервала синхронизации
        spinnerInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                long selectedIntervalMs = intervalValues[position];

                // Сохраняем в настройки
                prefs.edit().putLong(KEY_SYNC_INTERVAL, selectedIntervalMs).apply();

                // Оповещаем фоновую службу о смене интервала
                Intent serviceIntent = new Intent("ru.toboe512.maillis.INTERVAL_CHANGED");
                serviceIntent.setPackage(getPackageName());
                sendBroadcast(serviceIntent);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 3. УПРАВЛЕНИЕ КЭШЕМ ИЗОБРАЖЕНИЙ
        updateCacheSizeUi();

        buttonClearCache.setOnClickListener(v -> {
            // Конструируем стандартное нативное диалоговое окно Android
            new AlertDialog.Builder(ProfileActivity.this)
                    .setTitle("Очистка кэша изображений")
                    .setMessage("Вы уверены, что хотите очистить кэш? \n\n⚠️ " +
                            "Внимание: все загруженные и расшифрованные изображения в чатах будут удалены с устройства БЕЗВОЗВРАТНО.")
                    .setPositiveButton("Очистить", (dialog, which) -> {

                        // Пользователь подтвердил удаление — уходим в фоновый поток
                        new Thread(() -> {
                            // 1. Очищаем стандартную папку кэша (getCacheDir)
                            clearCacheFiles(getCacheDir());

                            // 2. ПОДСТРАХОВКА: Очищаем внутреннюю файловую папку (getFilesDir)
                            clearCacheFiles(getFilesDir());

                            // Возвращаемся на главный поток для перерисовки графики и вывода уведомления
                            runOnUiThread(() -> {
                                Toast.makeText(ProfileActivity.this, "Кэш мессенджера успешно очищен!", LENGTH_SHORT).show();
                                // Пересчитываем размер памяти на экране заново
                                updateCacheSizeUi();
                            });
                        }).start();

                    })
                    .setNegativeButton("Отмена", (dialog, which) -> {
                        // Закрываем окно без каких-либо действий, если пользователь передумал
                        dialog.dismiss();
                    })
                    .show(); // Принудительно выводим диалог на экран смартфона
        });
    }

    /**
     * Фоновое вычисление размера кэша, чтобы не фризить UI поток.
     */
    private void updateCacheSizeUi() {
        new Thread(() -> {
            // Считаем размер файлов в обеих системных папках приложения
            long sizeBytes = getFolderSize(getCacheDir()) + getFolderSize(getFilesDir());
            final String sizeString = formatSize(sizeBytes);

            runOnUiThread(() -> {
                if (textViewCacheSize != null) {
                    textViewCacheSize.setText("Занято: " + sizeString);
                }
            });
        }).start();
    }

    private long getFolderSize(File file) {
        long size = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    size += getFolderSize(child);
                }
            }
        } else {
            size = file.length();
        }
        return size;
    }

    /**
     * Рекурсивно очищает файлы кэша изображений в изолированной директории MailLis.
     * ИСПРАВЛЕНО: Стирает любые картинки вне зависимости от префикса их имени.
     */
    private void clearCacheFiles(File file) {
        if (file == null || !file.exists()) return;

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    // Если внутри кэша есть подпапка (например, Glide/Coil кэш) — заходим в неё
                    if (child.isDirectory()) {
                        clearCacheFiles(child);
                    } else {
                        // ЖЕСТКИЙ ФИКС: Стираем файл, если это картинка по расширению
                        // или если он содержит маркеры медиа-трафика мессенджера
                        String name = child.getName().toLowerCase();
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.startsWith("img_")) {
                            boolean deleted = child.delete();
                            android.util.Log.d("MailLis_Cache_Del", "Файл: " + child.getName() + " -> Удален: " + deleted);
                        }
                    }
                }
            }
        }
    }

    private String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#")
                .format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
