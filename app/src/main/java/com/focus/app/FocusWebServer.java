package com.focus.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

public class FocusWebServer extends NanoWSD {
    private static final String TAG = "FocusWebServer";
    public static final int PORT = 8080;

    public interface ActionListener {
        void onActionStart();
        void onActionPause();
        void onActionReset();
        void onActionAddTime(long addSeconds);
        void onActionSkip();
        void onActionSetMode(String mode);
        JSONObject onRequestSyncState();
    }

    private final Context context;
    private final ActionListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<FocusSocket> activeSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public FocusWebServer(Context context, ActionListener listener) {
        super(PORT);
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new FocusSocket(this, handshake);
    }

    @Override
    protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty() || uri.equals("/")) {
            uri = "/index.html";
        }
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        if (uri.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }

        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open("web/" + uri);
            String mime = getMimeType(uri);
            Response res = newChunkedResponse(Response.Status.OK, mime, is);
            addNoCacheHeaders(res);
            return res;
        } catch (IOException e) {
            try {
                InputStream is = context.getAssets().open("web/index.html");
                Response res = newChunkedResponse(Response.Status.OK, "text/html; charset=UTF-8", is);
                addNoCacheHeaders(res);
                return res;
            } catch (IOException ignored) {}
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + uri);
        }
    }

    private void addNoCacheHeaders(Response res) {
        res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        res.addHeader("Pragma", "no-cache");
        res.addHeader("Expires", "0");
        res.addHeader("Access-Control-Allow-Origin", "*");
    }

    private String getMimeType(String uri) {
        String lower = uri.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    void addSocket(FocusSocket socket) {
        activeSockets.add(socket);
        if (listener != null) {
            JSONObject state = listener.onRequestSyncState();
            if (state != null) {
                sendStateToSocket(socket, state);
            }
        }
    }

    void removeSocket(FocusSocket socket) {
        activeSockets.remove(socket);
    }

    public void broadcastState(String mode, String phase, long remainingSeconds, long totalSeconds, boolean isRunning) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("mode", mode);
            payload.put("phase", phase);
            payload.put("remainingSeconds", remainingSeconds);
            payload.put("totalSeconds", totalSeconds);
            payload.put("state", isRunning ? "running" : "paused");

            JSONObject msg = new JSONObject();
            msg.put("type", "SYNC_STATE");
            msg.put("payload", payload);
            msg.put("timestamp", System.currentTimeMillis());

            String jsonStr = msg.toString();
            for (FocusSocket socket : activeSockets) {
                if (socket.isOpen()) {
                    try {
                        socket.send(jsonStr);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to send frame to socket", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error broadcasting state", e);
        }
    }

    private void sendStateToSocket(FocusSocket socket, JSONObject payload) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "SYNC_STATE");
            msg.put("payload", payload);
            msg.put("timestamp", System.currentTimeMillis());
            if (socket.isOpen()) {
                socket.send(msg.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error sending state to socket", e);
        }
    }

    void handleClientMessage(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return;
        try {
            JSONObject msg = new JSONObject(jsonStr);
            String type = msg.optString("type", "");
            JSONObject payload = msg.optJSONObject("payload");

            mainHandler.post(() -> {
                if (listener == null) return;
                switch (type) {
                    case "ACTION_START":
                        listener.onActionStart();
                        break;
                    case "ACTION_PAUSE":
                        listener.onActionPause();
                        break;
                    case "ACTION_RESET":
                        listener.onActionReset();
                        break;
                    case "ACTION_ADD_TIME":
                        long secs = payload != null ? payload.optLong("seconds", 300) : 300;
                        listener.onActionAddTime(secs);
                        break;
                    case "ACTION_SKIP":
                        listener.onActionSkip();
                        break;
                    case "SET_MODE":
                        if (payload != null && payload.has("mode")) {
                            listener.onActionSetMode(payload.optString("mode"));
                        }
                        break;
                    case "REQUEST_SYNC":
                        JSONObject state = listener.onRequestSyncState();
                        if (state != null) {
                            for (FocusSocket s : activeSockets) {
                                sendStateToSocket(s, state);
                            }
                        }
                        break;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error parsing client message: " + jsonStr, e);
        }
    }

    private static class FocusSocket extends NanoWSD.WebSocket {
        private final FocusWebServer server;

        public FocusSocket(FocusWebServer server, NanoHTTPD.IHTTPSession handshake) {
            super(handshake);
            this.server = server;
        }

        @Override
        protected void onOpen() {
            server.addSocket(this);
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            server.removeSocket(this);
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame message) {
            server.handleClientMessage(message.getTextPayload());
        }

        @Override
        protected void onPong(NanoWSD.WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            server.removeSocket(this);
        }
    }
}
