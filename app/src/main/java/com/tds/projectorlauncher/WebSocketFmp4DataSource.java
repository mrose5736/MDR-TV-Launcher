package com.tds.projectorlauncher;

import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.util.ArrayDeque;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Presents binary WebSocket messages as one continuous byte stream. UniFi's
 * camera endpoint sends an fMP4 initialization segment followed by moof/mdat
 * fragments, so Media3 can consume the stream with its fragmented MP4
 * extractor and Android's hardware codecs.
 */
@UnstableApi
final class WebSocketFmp4DataSource extends BaseDataSource {
    private static final long OPEN_TIMEOUT_MS = 10_000L;
    private static final long MAX_QUEUED_BYTES = 32L * 1024L * 1024L;

    static final class Factory implements DataSource.Factory {
        private final OkHttpClient client;

        Factory(OkHttpClient client) {
            this.client = client;
        }

        @Override
        public DataSource createDataSource() {
            return new WebSocketFmp4DataSource(client);
        }
    }

    private final OkHttpClient client;
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();

    private Uri uri;
    private WebSocket webSocket;
    private byte[] currentChunk;
    private int currentOffset;
    private long queuedBytes;
    private boolean socketOpened;
    private boolean streamEnded;
    private boolean transferActive;
    private IOException failure;

    private WebSocketFmp4DataSource(OkHttpClient client) {
        super(true);
        this.client = client;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        closeSocket();
        synchronized (lock) {
            chunks.clear();
            currentChunk = null;
            currentOffset = 0;
            queuedBytes = 0;
            socketOpened = false;
            streamEnded = false;
            failure = null;
        }
        uri = dataSpec.uri;
        transferInitializing(dataSpec);

        Request request = new Request.Builder().url(uri.toString()).build();
        webSocket = client.newWebSocket(request, new Listener());

        long deadline = SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS;
        synchronized (lock) {
            while (!socketOpened && failure == null) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    failure = new IOException("Timed out opening camera WebSocket");
                    break;
                }
                waitForData(remaining);
            }
            if (failure != null) {
                closeSocket();
                throw failure;
            }
        }

        transferActive = true;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }

        int copied;
        synchronized (lock) {
            while ((currentChunk == null || currentOffset >= currentChunk.length)
                    && chunks.isEmpty() && failure == null && !streamEnded) {
                waitForData(0);
            }
            if (failure != null) {
                throw failure;
            }
            if ((currentChunk == null || currentOffset >= currentChunk.length) && chunks.isEmpty()) {
                return C.RESULT_END_OF_INPUT;
            }
            if (currentChunk == null || currentOffset >= currentChunk.length) {
                currentChunk = chunks.removeFirst();
                currentOffset = 0;
            }
            copied = Math.min(length, currentChunk.length - currentOffset);
            System.arraycopy(currentChunk, currentOffset, buffer, offset, copied);
            currentOffset += copied;
            queuedBytes -= copied;
            if (currentOffset >= currentChunk.length) {
                currentChunk = null;
                currentOffset = 0;
            }
        }
        bytesTransferred(copied);
        return copied;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        closeSocket();
        synchronized (lock) {
            chunks.clear();
            currentChunk = null;
            currentOffset = 0;
            queuedBytes = 0;
            streamEnded = true;
            lock.notifyAll();
        }
        if (transferActive) {
            transferActive = false;
            transferEnded();
        }
        uri = null;
    }

    private void enqueue(byte[] bytes) {
        if (bytes.length == 0) {
            return;
        }
        synchronized (lock) {
            if (streamEnded || failure != null) {
                return;
            }
            if (queuedBytes + bytes.length > MAX_QUEUED_BYTES) {
                failure = new IOException("Camera stream buffer exceeded 32 MB");
                closeSocket();
            } else {
                chunks.addLast(bytes);
                queuedBytes += bytes.length;
            }
            lock.notifyAll();
        }
    }

    private void waitForData(long timeoutMs) throws IOException {
        try {
            if (timeoutMs > 0) {
                lock.wait(timeoutMs);
            } else {
                lock.wait();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading camera stream", error);
        }
    }

    private void closeSocket() {
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.cancel();
        }
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket socket, Response response) {
            synchronized (lock) {
                socketOpened = true;
                lock.notifyAll();
            }
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            enqueue(bytes.toByteArray());
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            enqueue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            synchronized (lock) {
                streamEnded = true;
                lock.notifyAll();
            }
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            synchronized (lock) {
                failure = error instanceof IOException
                        ? (IOException) error
                        : new IOException("Camera WebSocket failed", error);
                lock.notifyAll();
            }
        }
    }
}
