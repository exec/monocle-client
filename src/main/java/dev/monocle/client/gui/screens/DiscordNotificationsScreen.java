package dev.monocle.client.gui.screens;

import com.google.gson.JsonObject;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.systems.bots.Bots;
import static dev.monocle.client.MonocleClient.mc;

/** Credentials stay in the host's private file, outside profiles and worker configuration. */
public final class DiscordNotificationsScreen extends WindowScreen {
    private final Bots bots;
    private String pastedToken="";
    public DiscordNotificationsScreen(GuiTheme theme,Bots bots){super(theme,"Discord owner notifications");this.bots=bots;}
    @Override public void initWidgets(){
        JsonObject state=bots.notifications().status();
        add(theme.label("Owner DMs for failures, disconnects, deaths, safety logouts and finished/cancelled jobs. Delivery is asynchronous. No slash commands.",550)).expandX();
        add(theme.label("Enable Discord notifications"));var enabled=add(theme.checkbox(state.get("enabled").getAsBoolean())).widget();
        enabled.tooltip="Enable Discord owner notifications";
        add(theme.label("Owner Discord user ID"));var owner=add(theme.textBox(state.get("ownerId").getAsString())).expandX().widget();
        var feedback=add(theme.label(state.toString(),550)).expandX().widget();
        add(theme.button("Paste bot token privately from clipboard")).expandX().widget().action=()->{
            pastedToken=mc.keyboardHandler.getClipboard().strip();feedback.set("Token held privately until Save. Its value is never displayed.");
        };
        add(theme.button("Save settings")).expandX().widget().action=()->{
            try {JsonObject request=new JsonObject();request.addProperty("enabled",enabled.checked);request.addProperty("ownerId",owner.get());request.addProperty("botToken",pastedToken);feedback.set(bots.notifications().configure(request).toString());pastedToken="";}
            catch(RuntimeException e){feedback.set(e.getMessage());}
        };
        add(theme.button("Send test DM")).expandX().widget().action=()->{
            try {feedback.set(bots.notifications().test().toString()+" · delivery queued; reopen to check status");}catch(RuntimeException e){feedback.set(e.getMessage());}
        };
        add(theme.button("Forget token and disable")).expandX().widget().action=()->{
            try {JsonObject request=new JsonObject();request.addProperty("enabled",false);request.addProperty("clearToken",true);feedback.set(bots.notifications().configure(request).toString());pastedToken="";enabled.checked=false;}
            catch(RuntimeException e){feedback.set(e.getMessage());}
        };
    }
    @Override public void onClose(){pastedToken="";super.onClose();}
}
