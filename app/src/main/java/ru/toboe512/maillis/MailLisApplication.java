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

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance();
        mailExecutorService = MailExecutorServiceImpl.getInstance(emailClient, dbService,this);
        if (mailExecutorService != null) {
            return;
        }
    }
}
