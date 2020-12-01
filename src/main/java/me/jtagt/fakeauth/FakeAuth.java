package me.jtagt.fakeauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;

public final class FakeAuth extends Plugin implements Listener {
    private final HashMap<UUID, UUID> activePlayers = new HashMap<>();

    @Override
    public void onEnable() {
        this.getProxy().getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() { }

    public static String insertDashUUID(String uuid) {
        StringBuilder sb = new StringBuilder(uuid);
        sb.insert(8, "-");
        sb = new StringBuilder(sb.toString());
        sb.insert(13, "-");
        sb = new StringBuilder(sb.toString());
        sb.insert(18, "-");
        sb = new StringBuilder(sb.toString());
        sb.insert(23, "-");

        return sb.toString();
    }

    public static String getMojangResponse(String targetURL) throws IOException {
        URL url = new URL(targetURL);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer content = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();

        return content.toString();
    }

    public static String executeGet(String targetURL) {
        HttpURLConnection connection = null;

        try {
            //Create connection
            URL url = new URL(targetURL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty("Connection", "keep-alive");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            //Send request
            DataOutputStream wr = new DataOutputStream (
                    connection.getOutputStream());
            wr.close();

            //Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder(); // or StringBuffer if Java version 5+
            String line;
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            return response.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Deprecated
    @EventHandler
    public void onPlayerConnected(ServerConnectedEvent event) {
        if (event.getServer().getInfo().getName().equals("smpserver")) return;

        new Thread(() -> {
            try {
                Thread.sleep(1500);
                event.getPlayer().sendMessage(ChatColor.AQUA + "Enter the username you want to be.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public ProxiedPlayer getPlayerFromConnection(Connection connection) {
        Collection<ProxiedPlayer> players = this.getProxy().getPlayers();
        ProxiedPlayer proxiedPlayer = null;

        for (ProxiedPlayer player : players) {
            if (player.getSocketAddress() == connection.getSocketAddress()) {
                proxiedPlayer = player;
            }
        }

        return proxiedPlayer;
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event)  {
        new Thread(() -> {
            if (event.getMessage().startsWith("/")) return;

            ProxiedPlayer player = getPlayerFromConnection(event.getSender());
            if (!player.getServer().getInfo().getName().equals("smpserver")) {
                String message = event.getMessage();
                String result = executeGet("https://mc-heads.net/minecraft/profile/" + message);

                JsonParser jsonParser = new JsonParser();
                JsonObject jsonObject = jsonParser.parse(result).getAsJsonObject();
                ArrayList<LoginResult.Property> properties = new ArrayList<>();

                String mojangResult = null;
                try {
                    mojangResult = getMojangResponse("https://sessionserver.mojang.com/session/minecraft/profile/" + jsonObject.get("id").getAsString() + "?unsigned=false");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                JsonObject jsonObject1 = jsonParser.parse(mojangResult).getAsJsonObject();
                JsonArray jsonArray = jsonObject1.getAsJsonArray("properties");
                for (JsonElement property : jsonArray) {
                    JsonObject data = property.getAsJsonObject();

                    properties.add(new LoginResult.Property(data.get("name").getAsString(), data.get("value").getAsString(), data.get("signature").getAsString()));
                }

                LoginResult.Property[] properties1;
                properties1 = properties.toArray(new LoginResult.Property[0]);

                try {
                    updatePlayer(player, jsonObject.get("id").getAsString(), jsonObject.get("name").getAsString(), properties1);
                } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void updatePlayer(ProxiedPlayer proxiedPlayer, String uuid, String username, LoginResult.Property[] properties) throws NoSuchFieldException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        activePlayers.put(UUID.fromString(insertDashUUID(uuid)), proxiedPlayer.getUniqueId());

        Field nameField = proxiedPlayer.getPendingConnection().getClass().getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(proxiedPlayer.getPendingConnection(), username);

        Field namePlayerField = proxiedPlayer.getClass().getDeclaredField("name");
        namePlayerField.setAccessible(true);
        namePlayerField.set(proxiedPlayer, username);

        Field uniqueIdField = proxiedPlayer.getPendingConnection().getClass().getDeclaredField("uniqueId");
        Field offlineIdField = proxiedPlayer.getPendingConnection().getClass().getDeclaredField("offlineId");

        uniqueIdField.setAccessible(true);
        offlineIdField.setAccessible(true);

        uniqueIdField.set(proxiedPlayer.getPendingConnection(), UUID.fromString(insertDashUUID(uuid)));
        offlineIdField.set(proxiedPlayer.getPendingConnection(), UUID.fromString(insertDashUUID(uuid)));

        LoginResult loginRequest = new LoginResult(uuid, username, properties);

        Field loginProfilePlayer = proxiedPlayer.getPendingConnection().getClass().getDeclaredField("loginProfile");
        loginProfilePlayer.setAccessible(true);
        loginProfilePlayer.set(proxiedPlayer.getPendingConnection(), loginRequest);

        proxiedPlayer.connect(this.getProxy().getServerInfo("smpserver"));
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID player = activePlayers.get(event.getPlayer().getUniqueId());

        ProxiedPlayer player1 = this.getProxy().getPlayer(player);
        player1.disconnect();

        activePlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerAuthed(PostLoginEvent event) throws NoSuchFieldException {
        Field nameField = event.getPlayer().getPendingConnection().getClass().getDeclaredField("loginProfile");
        nameField.setAccessible(true);
    }
}
