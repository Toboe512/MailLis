package ru.toboe512.maillis.service;

import static android.content.Context.MODE_PRIVATE;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;
import static ru.toboe512.maillis.config.Config.UPDATE_INTERVAL_MS;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.MessageDto;

/**
 * Изолированный рабочий класс-конвейер для циклической фоновой синхронизации P2P почты.
 * Полностью освобождает класс службы от сетевой и бизнес-логики.
 */
public class UpdateRunnable implements Runnable {
    private static final String LOG_TAG = "MailLis_UpdateRunnable";
    private final MailLisPushService serviceContext;
    private final Handler updateHandler;
    private final DBService dbService;
    private final MailExecutorService mailExecutorService;

    public UpdateRunnable(MailLisPushService serviceContext, Handler updateHandler, DBService dbService, MailExecutorService mailExecutorService) {
        this.serviceContext = serviceContext;
        this.updateHandler = updateHandler;
        this.dbService = dbService;
        this.mailExecutorService = mailExecutorService;
    }

    @Override
    public void run() {
        SharedPreferences prefs = serviceContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Уходим в выделенный рабочий поток, чтобы сетевые блокировки IMAP/SMTP не фризили устройство
        new Thread(() -> {
            try {
                Log.d(LOG_TAG, "=== СТАРТ ЦИКЛА СИНХРОНИЗАЦИИ СЕТИ ===");
                // Выполняем синхронизацию писем по IMAP
                int messageCount = mailExecutorService.fetchIncomingOverNetwork(serviceContext, prefs);

                if (messageCount > 0) {
                    Log.i(LOG_TAG, "В базе данных обнаружено новое входящее сообщение! Генерируем Push-уведомление...");

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
                mailExecutorService.retryUnsentMessages(prefs);

                Log.d(LOG_TAG, "=== КОНЕЦ ЦИКЛА СИНХРОНИЗАЦИИ СЕТИ ===");
            } catch (Exception e) {
                Log.e(LOG_TAG, "Критический сбой внутри конвейера фоновой синхронизации", e);
            }
        }).start();

        // Планируем следующий автоматический опрос строго через 20 секунд
        updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
    }
}