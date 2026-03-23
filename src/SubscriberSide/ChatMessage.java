package SubscriberSide;

import java.io.Serializable;
import java.util.Date;

public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String sender;
    private final String message;
    private final Date timestamp;
    private final boolean isOutgoing; // true if sent by us

    public ChatMessage(String sender, String message, boolean isOutgoing) {
        this.sender = sender;
        this.message = message;
        this.timestamp = new Date();
        this.isOutgoing = isOutgoing;
    }

    public String getSender() { return sender; }
    public String getMessage() { return message; }
    public Date getTimestamp() { return timestamp; }
    public boolean isOutgoing() { return isOutgoing; }

    @Override
    public String toString() {
        String prefix = isOutgoing ? "Me: " : sender + ": ";
        return String.format("[%tH:%tM] %s%s", timestamp, timestamp, prefix, message);
    }
}