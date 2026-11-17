package ru.toboe512.maillis;

import android.app.Application;

import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.service.MailExecutorService;
import ru.toboe512.maillis.service.MailExecutorServiceImpl;

public class MailLisApplication extends Application {

    private DBService dbService;
    private EmailClient emailClient;
    private MailExecutorService mailExecutorService;

    @Override
    public void onCreate() {
        super.onCreate();
        if (dbService == null) {
            dbService = DBServiceSQLite.getInstance(this);
        }
        if (emailClient == null) {
            emailClient = EmailClientImpl.getInstance();
        }
        if (mailExecutorService == null) {
            mailExecutorService = MailExecutorServiceImpl.getInstance(emailClient, dbService, this);
        }
    }
}
