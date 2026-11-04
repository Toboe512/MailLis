package ru.toboe512.maillis.utils;

import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;

import androidx.recyclerview.widget.DiffUtil;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;

public class ChatDiffCallback extends DiffUtil.Callback {

    private final List<ChatPreview> oldList;
    private final List<ChatPreview> newList;

    public ChatDiffCallback(List<ChatPreview> oldList, List<ChatPreview> newList) {
        this.oldList = oldList;
        this.newList = newList;
    }

    @Override
    public int getOldListSize() {
        return oldList != null ? oldList.size() : 0;
    }

    @Override
    public int getNewListSize() {
        return newList != null ? newList.size() : 0;
    }

    // Проверяем, являются ли элементы одной и той же комнатой чата (сравниваем по уникальному ID)
    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return oldList.get(oldItemPosition).getId() == newList.get(newItemPosition).getId();
    }

    // Проверяем, изменилось ли содержимое внутри чата (текст последнего сообщения или время)
    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        ChatPreview oldChat = oldList.get(oldItemPosition);
        ChatPreview newChat = newList.get(newItemPosition);

        boolean oldChaNoNull = oldChat != null;
        boolean newChaNoNull = newChat != null;
        String oldChatTitle = oldChaNoNull && oldChat.getTitle() != null
                ? oldChat.getTitle() : EMPTI_STRING;
        String oldChatLastMessage = oldChaNoNull && oldChat.getLastMessage() != null
                ? oldChat.getLastMessage() : EMPTI_STRING;



        // Сравниваем все поля, которые влияют на отображение карточки чата на экране
        return oldChaNoNull &&
                newChaNoNull &&
                oldChatTitle.equals(newChat.getTitle()) &&
                oldChatLastMessage.equals(newChat.getLastMessage()) &&
                oldChat.isHandShake() == newChat.isHandShake();
    }
}
