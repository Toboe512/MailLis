package ru.toboe512.maillis.service;

import static android.app.Notification.DEFAULT_ALL;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static androidx.core.app.NotificationCompat.PRIORITY_HIGH;
import static androidx.core.app.NotificationCompat.PRIORITY_LOW;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import ru.toboe512.maillis.activity.MainActivity;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.db.DBServiceSQLite;


/**
 * Децентрализованная Foreground-служба (Передний сервис) для мессенджера MailLis.
 * Обеспечивает непрерывный фоновый опрос IMAP-сервера на наличие новых зашифрованных P2P писем,
 * осуществляет автоматический ретрай неотправленных сообщений и генерирует Push-уведомления.
 */
public class MailLisPushService extends Service {

    private static final String TAG = "MailLis_PushService";
    private static final String PUSH_CHANNEL_ID = "maillis_push_channel";
    private static final String SERVICE_CHANNEL_ID = "maillis_service_channel";
    private static final int SYSTEM_SERVICE_ID = 202;

    private Handler updateHandler;
    private UpdateRunnable updateRunnable; // Поле для нашего выделенного класса
    private EmailClient emailClient;
    private DBServiceSQLite dbService;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Служба MailLisPushService успешно создана в памяти системы");

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        updateHandler = new Handler(Looper.getMainLooper());

        // Создаем обязательные для Android 8.0+ каналы уведомлений
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Запуск фонового конвейера синхронизации P2P почты");

        // ИСПРАВЛЕНИЕ: Оборачиваем создание Foreground-уведомления в проверку!
        // Если updateRunnable == null, значит служба стартует ПЕРВЫЙ РАЗ в памяти устройства,
        // и нам действительно нужно повесить плашку в шторку системы.
        if (updateRunnable == null) {
            // 1. ТРЕБОВАНИЕ ANDROID: Принудительно привязываем Foreground-плашку в шторку системы,
            // чтобы Android не выгрузил мессенджер из оперативной памяти при блокировке экрана.
            var notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    notificationIntent,
                    FLAG_IMMUTABLE
            );


            Notification serviceNotification = new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                    .setContentTitle("MailLis защищает вашу переписку")
                    .setContentText("Ожидание новых зашифрованных сообщений...")
                    .setSmallIcon(android.R.drawable.ic_menu_send)
                    .setContentIntent(pendingIntent)
                    .setPriority(PRIORITY_LOW) // Низкий приоритет, чтобы плашка висела тихо
                    .setOngoing(true) // Пользователь не сможет случайно смахнуть её
                    .build();

            // Запускаем службу в привилегированном Foreground-режиме
            startForeground(SYSTEM_SERVICE_ID, serviceNotification);

            // 2. ИНИЦИАЛИЗАЦИЯ И ЗАПУСК ЕДИНОГО ЦИКЛА СИНХРОНИЗАЦИИ СЕТИ

            // Инициализируем наш выделенный класс, передавая зависимости через конструктор
            updateRunnable = new UpdateRunnable(this, updateHandler, emailClient, dbService);

            // Производим мгновенный первый запуск конвейера синхронизации
            updateHandler.post(updateRunnable);

        } else {
            // Если updateRunnable != null — служба уже работает, цикл запущен, плашка на месте.
            // Просто игнорируем повторный вызов startForeground, чтобы не дублировать уведомления!
            Log.d(TAG, "Повторный вызов onStartCommand. Синхронизация и Foreground плашка уже активны.");
        }

        // START_STICKY принудительно заставит Android автоматически перезапустить службу со всеми компонентами,
        // если операционная система временно убьет её из-за экстремальной нехватки памяти (например, при запуске тяжелой игры)
        return START_STICKY;
    }

    /**
     * Конструирует и выводит на экран смартфона настоящее всплывающее Push-уведомление о новом сообщении.
     */
    public void showPushNotification(String sender, String messageText) {
        var intent = new Intent(this, MainActivity.class);
        // Флаг FLAG_MUTABLE обеспечивает корректную работу интентов на Android 12-16
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                FLAG_MUTABLE
        );

        Notification pushNotification = new NotificationCompat.Builder(this, PUSH_CHANNEL_ID)
                .setContentTitle("🔒 Новое безопасное сообщение")
                .setContentText(sender + ": " + messageText) // Выводим автора и расшифрованный текст
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true) // Уведомление автоматически исчезнет из шторки после клика по нему
                .setContentIntent(pendingIntent)
                .setPriority(PRIORITY_HIGH) // Принудительно заставляет пуш "выплыть" поверх всех окон
                .setDefaults(DEFAULT_ALL) // Включает системный звук, вибрацию и светодиод смартфона
                .build();

        var notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            // Используем таймстемп в качестве уникального ID, чтобы новые пуши выстраивались пачкой и не затирали друг друга
            notificationManager.notify((int) System.currentTimeMillis(), pushNotification);
        }
    }

    /**
     * Создает необходимые системные каналы (Notification Channels) для Android 8.0 и выше.
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // 1. Канал для всплывающих PUSH-уведомлений о сообщениях (Высокий приоритет со звуком)
                var pushChannel = new NotificationChannel(
                        PUSH_CHANNEL_ID,
                        "Новые P2P сообщения",
                        NotificationManager.IMPORTANCE_HIGH
                );
                pushChannel.setDescription("Всплывающие уведомления о входящих зашифрованных сообщениях MailLis");
                pushChannel.enableLights(true);
                pushChannel.enableVibration(true);
                manager.createNotificationChannel(pushChannel);

                // 2. Канал для системной плашки постоянной службы (Низкий приоритет, чтобы не жужжал вхолостую)
                var serviceChannel = new NotificationChannel(SERVICE_CHANNEL_ID, "Работа службы в фоне", IMPORTANCE_LOW);
                serviceChannel.setDescription("Индикатор стабильной работы P2P-клиента MailLis в фоне");
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Служба MailLisPushService выгружается из оперативной памяти");
        super.onDestroy();// На всякий случай очищаем хэндлер от бесконечных повторений runnable, исключая утечки контекста
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Сервис работает в автономном режиме без привязки через ServiceConnection, возвращаем null
        return null;
    }
}