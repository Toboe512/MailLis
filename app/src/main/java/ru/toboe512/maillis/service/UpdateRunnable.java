package ru.toboe512.maillis.service;

import static android.content.Context.MODE_PRIVATE;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.MessageDto;

/**
 * Изолированный рабочий класс-конвейер для циклической фоновой синхронизации P2P почты.
 * Полностью освобождает класс службы от сетевой и бизнес-логики.
 */
public class UpdateRunnable implements Runnable {

    private static final String TAG = "MailLis_SyncRunnable";
    private static final String PREFS_NAME = "MailLis_Prefs"; // Проверьте имя вашей константы
    private static final long UPDATE_INTERVAL_MS = 20000;      // Интервал опроса 20 секунд

    private final MailLisPushService serviceContext;
    private final Handler updateHandler;
    private final EmailClient emailClient;
    private final DBService dbService;

    public UpdateRunnable(MailLisPushService serviceContext, Handler updateHandler,
                          EmailClient emailClient, DBService dbService) {
        this.serviceContext = serviceContext;
        this.updateHandler = updateHandler;
        this.emailClient = emailClient;
        this.dbService = dbService;
    }

    @Override
    public void run() {
        SharedPreferences prefs = serviceContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Уходим в выделенный рабочий поток, чтобы сетевые блокировки IMAP/SMTP не фризили устройство
        new Thread(() -> {
            try {
                Log.d(TAG, "=== СТАРТ ЦИКЛА СИНХРОНИЗАЦИИ СЕТИ ===");

                // Запоминаем общее количество сообщений в SQLite ДО сессии скачивания писем чата
                int previousMessagesCount = dbService.getTotalMessagesCount();

                // Выполняем синхронизацию писем по IMAP
                emailClient.fetchIncomingOverNetwork(serviceContext, prefs);

                // Считываем количество сообщений ПОСЛЕ завершения IMAP-запроса
                int currentMessagesCount = dbService.getTotalMessagesCount();

                // ПРОВЕРКА НА НОВЫЕ ПИСЬМА: Если количество строк в SQLite увеличилось — генерируем Push!
                if (currentMessagesCount > previousMessagesCount) {
                    Log.i(TAG, "В базе данных обнаружено новое входящее сообщение! Генерируем Push-уведомление...");

                    // 1. Сигнал для Главного экрана (списка чатов)
                    Intent updateChatsIntent = new Intent("ru.toboe512.maillis.UPDATE_CHATS");
                    updateChatsIntent.setPackage(serviceContext.getPackageName()); // Защита Android 14+
                    serviceContext.sendBroadcast(updateChatsIntent);

                    // 2. Специальный сигнал для экрана открытого чата (динамическое обновление галочек)
                    Intent updateMessagesIntent = new Intent("ru.toboe512.maillis.UPDATE_MESSAGES");
                    updateMessagesIntent.setPackage(serviceContext.getPackageName());
                    serviceContext.sendBroadcast(updateMessagesIntent);

                    // Извлекаем последнее входящее сообщение для генерации системного пуша
                    MessageDto lastIncoming = dbService.getLastIncomingMessage();
                    if (lastIncoming != null && !lastIncoming.isMine()) {
                        // Вызываем метод показа уведомления через публичный мост службы
                        serviceContext.showPushNotification(lastIncoming.getSenderEmail(), lastIncoming.getText());
                    }
                }

                // Запускаем очередь автоматического ретрая (переотправка локально застрявших писем в FIFO)
                emailClient.retryUnsentMessages(prefs);

                Log.d(TAG, "=== КОНЕЦ ЦИКЛА СИНХРОНИЗАЦИИ СЕТИ ===");

            } catch (Exception e) {
                Log.e(TAG, "Критический сбой внутри конвейера фоновой синхронизации", e);
                e.printStackTrace();
            }
        }).start();

        // Планируем следующий автоматический опрос строго через 20 секунд
        updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
    }
}