package ru.toboe512.maillis.adapters;


import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.activity.ChatActivity;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.utils.ChatDiffCallback;

public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.ChatViewHolder> {

    private final List<ChatPreview> chatList;
    private final Context context;

    private final DBService dbService; // Помощник для удаления из SQLite

    public ChatsAdapter(Context context, List<ChatPreview> chatList, DBService dbService) {
        this.context = context;
        this.chatList = chatList;
        this.dbService = dbService;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_preview, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatPreview chat = chatList.get(position);
        holder.textViewName.setText(chat.getTitle());
        holder.textViewLastMessage.setText(chat.getLastMessage() != null ? chat.getLastMessage() : "Нет сообщений");
        holder.textViewType.setText(chat.getType().equals("GROUP") ? "Группа" : "Личный");

        // При клике на чат — открываем экран переписки ChatActivity
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, ChatActivity.class);
            // Передаем ID и имя чата, чтобы ChatActivity знала, какую переписку открывать
            intent.putExtra("CHAT_ID", chat.getId());
            intent.putExtra("CHAT_TITLE", chat.getTitle());
            context.startActivity(intent);
        });

        // ДОЛГИЙ КЛИК — вызывает контекстное меню удаления чата
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteChatDialog(chat, position);
            return true; // Перехватываем событие, чтобы обычный клик не срабатывал
        });
    }

    @Override
    public int getItemCount() {
        return chatList.size();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView textViewName, textViewLastMessage, textViewType;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewName = itemView.findViewById(R.id.textViewChatName);
            textViewLastMessage = itemView.findViewById(R.id.textViewLastMessage);
            textViewType = itemView.findViewById(R.id.textViewChatType);
        }
    }

    // Отображение диалогового окна с подтверждением
    private void showDeleteChatDialog(ChatPreview chat, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить чат");
        builder.setMessage("Вы уверены, что хотите удалить чат '" + chat.getTitle() + "' и всю историю переписки?");

        builder.setPositiveButton("Удалить", (dialog, which) -> deleteChatFromDatabase(chat.getId(), position));

        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Удаление записи из SQLite и анимация списка
    private void deleteChatFromDatabase(int chatId, int position) {
        // Удаляем сам чат. Связанные сообщения в SQLite удалятся автоматически благодаря ON DELETE CASCADE
        boolean deleted = dbService.deleteChatAndMessages(chatId);

        if (deleted) {
            // Удаляем объект из массива памяти устройства
            chatList.remove(position);

            // Плавная анимация удаления строки в RecyclerView
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, chatList.size());

            Toast.makeText(context, "Чат успешно удален", Toast.LENGTH_SHORT).show();
        } else {
            // Ошибка может быть, только если мы удаляем фейковые тестовые чаты, которых нет в физической БД
            Toast.makeText(context, "Локальный чат скрыт из списка", Toast.LENGTH_SHORT).show();
            chatList.remove(position);
            notifyItemRemoved(position);
        }
    }

    // Внутри вашего класса ChatsAdapter:
    public void updateChatsList(List<ChatPreview> newChats) {
        // 1. Вычисляем разницу между текущим списком в памяти адаптера (this.chatList) и новым из БД
        var diffCallback = new ChatDiffCallback(this.chatList, newChats);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        // 2. Обновляем данные в памяти адаптера без вызова .clear()
        this.chatList.clear();
        this.chatList.addAll(newChats);

        // 3. Магия: DiffUtil сам точечно вызовет notifyItemChanged, notifyItemInserted или notifyItemRemoved!
        diffResult.dispatchUpdatesTo(this);
    }

}
