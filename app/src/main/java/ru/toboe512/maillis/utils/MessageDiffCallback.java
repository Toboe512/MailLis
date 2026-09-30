package ru.toboe512.maillis.utils;

import androidx.recyclerview.widget.DiffUtil;

import java.util.List;

import ru.toboe512.maillis.dto.MessageDto;

public class MessageDiffCallback extends DiffUtil.Callback {

    private final List<MessageDto> oldList;
    private final List<MessageDto> newList;

    public MessageDiffCallback(List<MessageDto> oldList, List<MessageDto> newList) {
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

    // Сравниваем сообщения по уникальному локальному ID из SQLite
    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return oldList.get(oldItemPosition).getId() == newList.get(newItemPosition).getId();
    }

    // Проверяем, изменилось ли что-то внутри самого сообщения (например, прилетел server_uid)
    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        MessageDto oldMsg = oldList.get(oldItemPosition);
        MessageDto newMsg = newList.get(newItemPosition);

        if (oldMsg == null || newMsg == null) {
            return false;
        }

        // Если текст, время или UID сервера изменились — элемент будет точечно перерисован
        boolean changText = oldMsg.getText() == null || oldMsg.getText().equals(newMsg.getText());
        boolean changeServerUid = oldMsg.getServerUid() == null || oldMsg.getServerUid().equals(newMsg.getServerUid());
        return changText && oldMsg.getTimestamp() == newMsg.getTimestamp() && changeServerUid;
    }
}