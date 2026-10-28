package ru.toboe512.maillis.adapters;


import static android.content.Context.MODE_PRIVATE;
import static android.widget.Toast.LENGTH_SHORT;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.PREFS_NAME;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.activity.ChatActivity;
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
        String chatTitle = chat.getTitle() != null ? chat.getTitle() : EMPTI_STRING;
        holder.textViewName.setText(chatTitle);
        holder.textViewLastMessage.setText(chat.getLastMessage() != null ? chat.getLastMessage() : "Нет сообщений");
        holder.textViewType.setText(chat.isGroup() ? "Группа" : "Личный");

        // При клике на чат — открываем экран переписки ChatActivity
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, ChatActivity.class);
            // Передаем ID и имя чата, чтобы ChatActivity знала, какую переписку открывать
            intent.putExtra("CHAT_ID", chat.getId());
            intent.putExtra("CHAT_TITLE", chatTitle);
            intent.putExtra("IS_GROUP", chat.isGroup()); // КРИТИЧЕСКИ ВАЖНО: передаем тип чата!
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
        var builder = new AlertDialog.Builder(context);
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
        boolean isDeleted;
        List<String> members = new ArrayList<>();
        ChatPreview chatPreview = chatList.get(position);

        if (chatPreview == null) {
            return;
        }
        if (chatPreview.isGroup()) {
            // Загружаем наш собственный логин из SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String myEmail = prefs.getString(KEY_EMAIL_LOGIN, "my@mail.ru").trim();
            //Вытаскиваем email участников, чтобы зачистить их ключи из public_keys
            members = dbService.getGroupMembers(chatId, myEmail);
            //  dbService.deleteGroupMembersByRemoteId(chatId);
            isDeleted = dbService.deleteGroupChatAndMessages(chatId);
        } else {
            // Удаляем данные из SQLite
            members.add(dbService.getRecipientEmailByChatId(chatId));
            isDeleted = dbService.deleteChatAndMessages(chatId);
        }
//        if (isDeleted) {
//            // 3. Если группа стерта, убираем из кэша публичные ключи участников
//            for (String memberEmail : members) {
//                dbService.deletePublicKeyByEmail(memberEmail);
//            }
//        }

        if (isDeleted) {
            // Удаляем объект из массива памяти устройства
            chatList.remove(position);

            // Плавная анимация удаления строки в RecyclerView
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, chatList.size());

            Toast.makeText(context, "Чат успешно удален", LENGTH_SHORT).show();
        } else {
            // Ошибка может быть, только если мы удаляем фейковые тестовые чаты, которых нет в физической БД
            Toast.makeText(context, "Локальный чат скрыт из списка", LENGTH_SHORT).show();
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
