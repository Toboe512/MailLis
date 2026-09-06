package ru.toboe512.maillis.activity;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ChatsAdapter;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.dto.ChatPreview;


public class MainActivity extends AppCompatActivity { // Важно: наследуемся от AppCompatActivity

    private RecyclerView recyclerViewChats;
    private ChatsAdapter chatsAdapter;
    private List<ChatPreview> chatList;
    private ChatDbHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new ChatDbHelper(this);
        chatList = new ArrayList<>();

        chatsAdapter = new ChatsAdapter(this, chatList);

        // Настраиваем RecyclerView для списка чатов
        recyclerViewChats = findViewById(R.id.recyclerViewChats);
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewChats.setAdapter(chatsAdapter); // Привязываем готовый адаптер


        // Нижняя круглая кнопка "+" теперь открывает создание группового чата, как и должна
        View fabNewChat = findViewById(R.id.fabNewChat);
        fabNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, CreateChatActivity.class);
                startActivity(intent);
            }
        });
    }

    // Каждый раз, когда пользователь возвращается на этот экран, обновляем список чатов
    @Override
    protected void onResume() {
        super.onResume();
        loadChatsFromDatabase();
    }
    // ОС Android сама вызовет этот метод для отрисовки меню в AppCompat-панели
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    // ОС Android сама вызовет этот метод для обработки кликов по кнопкам в панели
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_add_contact) {
            Toast.makeText(this, "Открываем добавление контакта", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, AddContactActivity.class);
            startActivity(intent);
            return true;
        }

        if (id == R.id.action_settings) {
            Toast.makeText(this, "Открываем настройки", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }



    // Метод чтения активных чатов из SQLite
    private void loadChatsFromDatabase() {
        chatList.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Берем все чаты из таблицы chats
        Cursor cursor = db.query("chats", null, null, null, null, null, "timestamp DESC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                int idIndex = cursor.getColumnIndex("id");
                int titleIndex = cursor.getColumnIndex("title");
                int typeIndex = cursor.getColumnIndex("type");
                int lastMsgIndex = cursor.getColumnIndex("last_message");

                if (idIndex >= 0 && titleIndex >= 0 && typeIndex >= 0 && lastMsgIndex >= 0) {
                    int id = cursor.getInt(idIndex);
                    String title = cursor.getString(titleIndex);
                    String type = cursor.getString(typeIndex);
                    String lastMessage = cursor.getString(lastMsgIndex);

                    chatList.add(new ChatPreview(id, title, type, lastMessage));
                }
            }
            cursor.close();
        }
        db.close();

        // Уведомляем адаптер о необходимости перерисовки
        if (chatsAdapter != null) {
            chatsAdapter.notifyDataSetChanged();
        }
    }
}