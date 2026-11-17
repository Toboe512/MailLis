package ru.toboe512.maillis.activity;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ContactsAdapter;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ContactDto;

public class GroupMembersActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_members);

        long chatId = getIntent().getLongExtra("CHAT_ID", -1);
        String chatName = getIntent().getStringExtra("CHAT_NAME");

        // 1. Находим текстовое поле заголовка и прописываем имя группы
        TextView textViewChatTitle = findViewById(R.id.textViewGroupMemberTitle);
        if (textViewChatTitle != null && chatName != null) {
            textViewChatTitle.setText(chatName.trim());
        }

        // 2. Находим нашу кастомную стрелочку назад и вешаем на неё закрытие экрана
        ImageButton buttonChatBack = findViewById(R.id.buttonGroupMemberBack);
        if (buttonChatBack != null) {
            buttonChatBack.setOnClickListener(v -> finish());
        }

        // Инициализация RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recyclerViewMembers);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        DBService dbService = DBServiceSQLite.getInstance(this);

        // Выгружаем данные в фоновом потоке для плавности интерфейса 120 Гц
        new Thread(() -> {
            List<ContactDto> membersList = dbService.getGroupMembersWithNames(chatId);

            runOnUiThread(() -> {
                // Переиспользуем ваш ContactsAdapter (передаем false или настраиваем его в режим простого просмотра без галочек)
                ContactsAdapter adapter = new ContactsAdapter(this, dbService, membersList);
                recyclerView.setAdapter(adapter);
            });
        }).start();
    }
}
