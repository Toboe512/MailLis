package ru.toboe512.maillis.adapters;


import android.app.AlertDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.dto.Message;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<Message> messageList;
    private final Context context;
    private final ChatDbHelper dbHelper;

    public MessagesAdapter(Context context, List<Message> messageList) {
        this.context = context;
        this.messageList = messageList;
        this.dbHelper = new ChatDbHelper(context);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messageList.get(position);
        holder.textViewText.setText(message.getText());

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.cardMessage.getLayoutParams();

        if (message.isMine()) {
            // Наше сообщение: прижимаем вправо, красим в приятный голубой, скрываем email
            params.removeRule(RelativeLayout.ALIGN_PARENT_START);
            params.addRule(RelativeLayout.ALIGN_PARENT_END);
            holder.layoutBackground.setBackgroundColor(Color.parseColor("#E3F2FD"));
            holder.textViewSender.setVisibility(View.GONE);
        } else {
            // Чужое сообщение: прижимаем влево, красим в серый, показываем автора
            params.removeRule(RelativeLayout.ALIGN_PARENT_END);
            params.addRule(RelativeLayout.ALIGN_PARENT_START);
            holder.layoutBackground.setBackgroundColor(Color.parseColor("#F5F5F5"));
            holder.textViewSender.setVisibility(View.VISIBLE);
            holder.textViewSender.setText(message.getSenderEmail());
        }
        holder.cardMessage.setLayoutParams(params);

        // ОБРАБОТКА ДОЛГОГО КЛИКА НА СООБЩЕНИЕ
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteMessageDialog(message, position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        CardView cardMessage;
        LinearLayout layoutBackground;
        TextView textViewSender, textViewText;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            cardMessage = itemView.findViewById(R.id.cardMessage);
            layoutBackground = itemView.findViewById(R.id.layoutMessageBackground);
            textViewSender = itemView.findViewById(R.id.textViewSender);
            textViewText = itemView.findViewById(R.id.textViewMessageText);
        }
    }

    // Всплывающее окно подтверждения удаления
    private void showDeleteMessageDialog(Message message, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить сообщение");
        builder.setMessage("Вы уверены, что хотите удалить это сообщение?");

        builder.setPositiveButton("Удалить", (dialog, which) -> {
            deleteMessageFromDb(message.getId(), message.getServerUid(), position);
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Удаление строки из SQLite
    private void deleteMessageFromDb(int messageId, int position) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Удаляем строго по первичному ключу ID сообщения
        int rowsDeleted = db.delete("messages", "id = ?", new String[]{String.valueOf(messageId)});
        db.close();

        if (rowsDeleted > 0) {
            messageList.remove(position);

            // Красивая плавная анимация исчезновения сообщения из списка
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, messageList.size());

            Toast.makeText(context, "Сообщение удалено", Toast.LENGTH_SHORT).show();
        } else {
            // Если сообщение было временным (не успело записаться в БД)
            messageList.remove(position);
            notifyItemRemoved(position);
        }
    }

    private void deleteMessageFromDb(int messageId, String serverUid, int position) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsDeleted = db.delete("messages", "id = ?", new String[]{String.valueOf(messageId)});
        db.close();

        if (rowsDeleted > 0) {
            messageList.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, messageList.size());

            // ЗАПУСКАЕМ СЕТЕВОЕ УДАЛЕНИЕ С СЕРВЕРА MAIL.RU
            deleteMessageFromMailRuServer(serverUid);
        }
    }

    // Фоновый метод для удаления письма по протоколу IMAP
    private void deleteMessageFromMailRuServer(final String serverUid) {
        if (serverUid == null || serverUid.isEmpty()) return;

        new Thread(() -> {
            jakarta.mail.Store store = null;
            jakarta.mail.Folder inbox = null;
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);
                String username = prefs.getString("email_login", "");
                String password = prefs.getString("email_password", "");
                String imapServer = prefs.getString("imap_server", "imap.mail.ru");

                if (username.isEmpty() || password.isEmpty()) return;

                java.util.Properties props = new java.util.Properties();
                props.put("mail.store.protocol", "imaps");
                props.put("mail.imaps.host", imapServer);
                props.put("mail.imaps.port", "993");

                jakarta.mail.Session session = jakarta.mail.Session.getInstance(props, null);
                store = session.getStore("imaps");
                store.connect(imapServer, username, password);

                // Открываем папку Входящие в режиме чтения и ЗАПИСИ (READ_WRITE), чтобы разрешить удаление
                inbox = store.getFolder("INBOX");
                inbox.open(jakarta.mail.Folder.READ_WRITE);

                int messageCount = inbox.getMessageCount();
                int startMessage = Math.max(1, messageCount - 30); // Ищем среди последних 30 писем
                jakarta.mail.Message[] messages = inbox.getMessages(startMessage, messageCount);

                for (jakarta.mail.Message msg : messages) {
                    String[] mimeIds = msg.getHeader("Message-ID");
                    if (mimeIds != null && mimeIds.length > 0 && mimeIds[0].equals(serverUid)) {

                        // ВЫСТАВЛЯЕМ СИСТЕМНЫЙ ФЛАГ УДАЛЕНИЯ НА СЕРВЕРЕ MAIL.RU
                        msg.setFlag(jakarta.mail.Flags.Flag.DELETED, true);
                        break;
                    }
                }

                // Закрываем папку с флагом true — это принудительно заставляет Mail.ru окончательно стереть помеченные письма (Expunging)
                inbox.close(true);
                store.close();

                // Выводим уведомление на главном потоке интерфейса
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "Сообщение стерто с сервера Mail.ru", Toast.LENGTH_SHORT).show()
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

}
