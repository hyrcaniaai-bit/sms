package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RocketChatWebhookTest {

    @Test
    public void displaySenderDropsLeadingPlus() {
        assertEquals("989359509605", RocketChatWebhook.displaySender("+989359509605"));
    }

    @Test
    public void displaySenderKeepsOtherSendersUnchanged() {
        assertEquals("989359509605", RocketChatWebhook.displaySender("989359509605"));
        assertEquals("MyBank", RocketChatWebhook.displaySender("MyBank"));
        assertEquals("", RocketChatWebhook.displaySender(null));
    }
}
