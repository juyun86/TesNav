package com.garan.tesnav.ar;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import com.garan.tesnav.export.AuthenticatedC3HostDiscovery;

/** Opt-in receiver; C3 decides source eligibility. Never starts cameras or touches control. */
public final class C3ArCameraService extends Service {
    static final String SHIM = "com.autonavi.amapauto.gdarcameraservicedemo";
    private static final String DESC = "com.autonavi.amapauto.gdarcameraservice.IGDCameraService";
    private static final String CALLBACK = "com.autonavi.amapauto.gdarcameraservice.IGDCameraStateCallBack";
    private static final int NOTIFICATION = 3107;
    // A/B reconstruction of the 2026-10-22 late-night build that measured correct:
    // that build had the hardware H.264 path ON. Keeping both codecs switchable lets us
    // tell whether the publish path (YUV vs JPEG) changes AMap's AR viewport timing.
    private static final boolean NATIVE_AVC_ENABLED = true;
    // The embedded AMap pane is a 1795x1528 virtual display. AMap sizes its AR overlay
    // from the returned camera frame size, so we hand back exactly that.
    private static final int NATIVE_WIDTH = 1795, NATIVE_HEIGHT = 1528;
    static volatile String status = "未连接实景测试";
    static volatile int frames, published;
    static volatile boolean running;
    // Diagnostic only: one line per protocol event, deduped to 1 s per key.
    private static long lastNoteAt;
    private static String lastNoteKey = "";
    private static void note(String key) {
        long now = SystemClock.elapsedRealtime();
        if (key.equals(lastNoteKey) && now - lastNoteAt < 1000) return;
        lastNoteKey = key; lastNoteAt = now;
        android.util.Log.i("Tenengkai-Proto", key + " t=" + now);
    }
    private long lastCalibrationAt;
    private volatile long lastConsumeAt;
    private volatile String inputCodec = "jpeg";
    private volatile String resolvedHost = "none";
    private static final byte[] NO_JPEG = new byte[0];
    public static String displayStatus() { return status; }
    private volatile int generation;
    private volatile HttpURLConnection connection;
    private Thread worker;
    private android.net.wifi.WifiManager.WifiLock videoWifiLock;
    private volatile boolean roadOwned;
    private volatile boolean nativeOwned;
    private boolean nativeRequest;
    private int nativeAttempts;
    private final Runnable nativeRetry = () -> {
        synchronized (C3ArCameraService.this) {
            if (nativeOwned && running && this.sourceReady && !this.opened) requestNativeDisplay();
        }
    };
    private boolean sourceReady, openRequested;
    private long lastArrivalAt;
    private volatile int nativeArStatus = -1, nativeResponse = -1;
    private volatile long nativeStatusAt;
    private boolean shimBound;
    private final ServiceConnection shimConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (C3ArCameraService.this) {
                nativeRequest = true;
                if (sourceReady) requestNativeDisplay();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
        @Override public void onBindingDied(ComponentName name) {
            synchronized (C3ArCameraService.this) {
                if (shimBound) unbindService(this);
                shimBound = false;
            }
        }
    };
    private final BroadcastReceiver recorderReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int type = intent.getIntExtra("KEY_TYPE", -1);
            note("bcv type=" + type);
            if (type == 12113) {
                synchronized (C3ArCameraService.this) { sendRecorderStatus(sourceReady); }
            } else if (type == 12115) {
                // Observations only: broadcasts do not authorize frames or mark calibration valid.
                nativeArStatus = intent.getIntExtra("EXTRA_NOTIFY_AR_STATUS", -1);
                nativeStatusAt = SystemClock.elapsedRealtime();
                android.util.Log.i("Tenengkai-NativeAr", "nativeArStatus="+nativeArStatus);
            } else if (type == 12112) {
                nativeResponse = intent.getIntExtra("EXTRA_RESPONSE_CODE", -1);
                android.util.Log.i("Tenengkai-NativeAr", "modeResponse="+nativeResponse);
            } else if (type == 12116) {
                sendCameraIdentity();
            }
        }
    };
    private HandlerThread callbacks;
    private Handler callbackHandler;
    private IBinder callback;
    private final IBinder.DeathRecipient callbackDeath = this::releaseCallback;
    private SharedMemory shared;
    private ByteBuffer memory;
    private boolean opened;
    private boolean reopenAfterReconnect;
    private int width = 1280, height = 720, format = 4, openGeneration;
    private Surface output;
    private Bitmap target;
    private int[] pixels;
    private byte[] yuv;

    @Override public void onCreate() {
        super.onCreate();
        callbacks = new HandlerThread("c3-ar-callback"); callbacks.start();
        callbackHandler = new Handler(callbacks.getLooper());
        callbackHandler.post(consumeWatch);
        IntentFilter filter = new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recorderReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(recorderReceiver, filter);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("c3-ar", "C3 AR 只读实景", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        // A queued STOP can arrive before a newer foreground START during recreation.
        // Acknowledge the system foreground deadline before handling either command.
        showForegroundNotification();
        if (intent != null && "ROAD_STOP".equals(intent.getAction())) {
            if (roadOwned) stopTest();
            else if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || "STOP".equals(intent.getAction())) { stopTest(); return START_NOT_STICKY; }
        boolean changeInput = selectInputMode(intent.getAction());
        if (changeInput) {
            disconnectedStream();
            if (connection != null) connection.disconnect();
        }
        if ("ROAD_START".equals(intent.getAction())) {
            callbackHandler.removeCallbacks(nativeRetry);
            releaseCallback();
            if (shimBound) { unbindService(shimConnection); shimBound = false; }
        }
        if ("NATIVE_START".equals(intent.getAction())) {
            nativeAttempts = 0; callbackHandler.removeCallbacks(nativeRetry);
            // Refresh a binding invalidated by a compatibility APK replacement.
            if (shimBound) { unbindService(shimConnection); shimBound = false; }
            if (!shimBound) {
                Intent shim = new Intent().setComponent(new ComponentName(SHIM, SHIM+".CameraService"));
                shimBound = bindService(shim, shimConnection, BIND_AUTO_CREATE);
            }
            if (sourceReady) requestNativeDisplay();
        }
        String endpoint = getSharedPreferences("c3-ar", 0).getString("endpoint", "");
        String token = getSharedPreferences("c3-ar", 0).getString("token", "");
        if (!ArFrameFormat.validEndpoint(endpoint) || token == null || !token.matches("[a-fA-F0-9]{48}")) {
            status = "请先配置 C3 局域网地址和本次测试令牌";
            stopSelf(); return START_NOT_STICKY;
        }
        if (!running) {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                videoWifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 29
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Tenengkai:AR-video");
                videoWifiLock.setReferenceCounted(false);
                videoWifiLock.acquire();
            }
            running = true; frames = published = 0;
            int tokenId = ++generation;
            worker = new Thread(() -> receive(tokenId, endpoint, token), "c3-ar-video");
            worker.start();
        }
        return START_NOT_STICKY;
    }
    private boolean selectInputMode(String action) {
        if (!"ROAD_START".equals(action) && !"NATIVE_START".equals(action)) return false;
        boolean nextNative = "NATIVE_START".equals(action);
        boolean changed = running && nativeOwned != nextNative;
        // Native entry always yields the embedded pane to AMap from its first
        // frame. C3 projections remain available through the enhanced entry.
        com.garan.tesnav.ui.ArVideoFrames.setNativeArPreferred(nextNative);
        nativeOwned = nextNative; roadOwned = !nextNative; nativeRequest = nextNative;
        return changed;
    }
    private void showForegroundNotification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, C3ArCameraService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, C3ArActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(NOTIFICATION, new Notification.Builder(this, "c3-ar")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("特会开 · C3 AR 只读实景")
                .setContentText("不代表 AR 标定完成；点击管理").setContentIntent(settings)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build());
    }
    private boolean current(int token) { return running && generation == token; }
    private static final class ClockSample {
        final double epoch;
        final long started, completed;
        ClockSample(double epoch, long started, long completed) {
            this.epoch = epoch; this.started = started; this.completed = completed;
        }
        long roundTrip() { return completed-started; }
    }
    private ClockSample sampleClock(String endpoint, String token) throws IOException {
        HttpURLConnection clock = (HttpURLConnection)new URL(new URL(endpoint), "/clock").openConnection();
        try {
            clock.setInstanceFollowRedirects(false);
            clock.setUseCaches(false);
            clock.setRequestProperty("Authorization", "Bearer " + token);
            clock.setConnectTimeout(1000); clock.setReadTimeout(1000);
            long started = SystemClock.elapsedRealtime();
            if (clock.getResponseCode() != 200) throw new IOException("Clock unavailable");
            try (DataInputStream input = new DataInputStream(clock.getInputStream())) {
                byte[] bytes = new byte[64];
                int count = 0, value;
                while ((value = input.read()) != -1) {
                    if (count == bytes.length) throw new IOException("Invalid clock sample");
                    bytes[count++] = (byte)value;
                }
                double epoch = Double.parseDouble(new String(bytes, 0, count, java.nio.charset.StandardCharsets.US_ASCII));
                if (!Double.isFinite(epoch) || epoch <= 0) throw new IOException("Invalid clock sample");
                return new ClockSample(epoch, started, SystemClock.elapsedRealtime());
            }
        } finally { clock.disconnect(); }
    }
    private String resolveEndpoint(String configuredEndpoint) {
        SharedPreferences prefs = getSharedPreferences("c3-ar", 0);
        configuredEndpoint = prefs.getString("endpoint", configuredEndpoint);
        String host = AuthenticatedC3HostDiscovery.discoverHost(getApplicationContext());
        resolvedHost = host == null ? "manual" : host;
        android.util.Log.i("Tenengkai-C3Discovery", "signedHost=" + resolvedHost);
        String resolved = host == null ? configuredEndpoint : ArFrameFormat.endpointForHost(configuredEndpoint, host);
        // Persist only authenticated discovery; retain the existing port and credentials.
        if (host != null && !resolved.equals(configuredEndpoint) &&
                configuredEndpoint.equals(prefs.getString("endpoint", ""))) {
            prefs.edit().putString("endpoint", resolved).apply();
        }
        return resolved;
    }
    private void receive(int tokenId, String endpoint, String token) {
        long deadline = roadOwned || nativeOwned ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + 20 * 60_000L;
        boolean avcAllowed=true;
        try {
            // Active AR survives hotspot changes. Preview retains its bounded budget;
            // HTTP 401/409 still return immediately through the existing response gate.
            int budget = opened ? 20 : 5;
            for (int attempt = 0; current(tokenId) && (roadOwned || nativeOwned || attempt < budget) && SystemClock.elapsedRealtime() < deadline; attempt = Math.min(attempt + 1, 30)) {
                HttpURLConnection socket = null;
                Thread clockWorker = null;
                ArH264Decoder avc = null;
                try {
                    status = attempt == 0 ? "正在签名发现并连接 C3 实景服务" : "视频连接中断，正在有限重连";
                    final String attemptEndpoint = resolveEndpoint(endpoint);
                    socket = (HttpURLConnection)new URL(attemptEndpoint).openConnection();
                    socket.setInstanceFollowRedirects(false);
                    socket.setRequestProperty("Authorization", "Bearer " + token);
                    socket.setRequestProperty("X-C3-AR-Geometry", "1");
                    boolean inputNative = nativeOwned;
                    if (inputNative) socket.setRequestProperty("X-C3-AR-Native", "1");
                    boolean requestAvc=inputNative && avcAllowed && NATIVE_AVC_ENABLED;
                    if (requestAvc) socket.setRequestProperty("X-C3-AR-Codec", "h264");
                    socket.setConnectTimeout(4_000); socket.setReadTimeout(8_000);
                    connection = socket;
                    if (!current(tokenId)) break;
                    long requestStarted = SystemClock.elapsedRealtime();
                    int code = socket.getResponseCode();
                    if (code == 423) throw new IOException("Previous video connection closing");
                    if (requestAvc && (code==406 || code==503)) { avcAllowed=false;attempt=0;continue; }
                    if (code != 200) {
                        status = code == 401 ? "测试令牌无效，请重新配置" :
                                code == 409 ? "C3 拒绝供帧：源条件未满足或已有接收端" : "C3 服务返回 HTTP " + code;
                        return;
                    }
                    String geometryProtocol = socket.getHeaderField("X-C3-AR-Protocol");
                    String epochHeader = socket.getHeaderField("X-C3-AR-Epoch-Ms");
                    double epoch = epochHeader == null ? Double.NaN : Double.parseDouble(epochHeader);
                    java.util.concurrent.atomic.AtomicReference<ClockSample> timing =
                            new java.util.concurrent.atomic.AtomicReference<>(
                                    new ClockSample(epoch, requestStarted, SystemClock.elapsedRealtime()));
                    if ("3".equals(geometryProtocol)) {
                        if (!inputNative || !requestAvc) throw new IOException("Unexpected AVC response");
                        inputCodec="h264";
                        avc=new ArH264Decoder((packet,image)->{
                            if (!current(tokenId) || nativeOwned!=inputNative) return;
                            if (packet.geometry.isEmpty()) throw new IOException("AVC geometry missing");
                            frames++;
                            sourceArrival();
                            ClockSample sample=timing.get();
                            long frameAt=com.garan.tesnav.ui.ArVideoFrames.publish(NO_JPEG,packet.geometry,sample.epoch,sample.started);
                            if (frameAt<0) return;
                            if (!publishCalibration(packet)) {
                                status="Waiting for valid C3 camera height";
                                return;
                            }
                            sourceFrame();resumeConsumer();
                            if (SystemClock.elapsedRealtime()-frameAt<=500) publishYuv(image);
                            status="已接收 C3 实景 · 硬解";
                        });
                    } else inputCodec="jpeg";
                    clockWorker = new Thread(() -> {
                        java.util.ArrayDeque<ClockSample> samples = new java.util.ArrayDeque<>();
                        while (current(tokenId) && !Thread.currentThread().isInterrupted()) {
                            try {
                                ClockSample fresh = sampleClock(attemptEndpoint, token);
                                long now = SystemClock.elapsedRealtime();
                                samples.addLast(fresh);
                                while (!samples.isEmpty() && now-samples.peekFirst().completed > 2000) samples.removeFirst();
                                ClockSample best = null;
                                for (ClockSample candidate : samples) {
                                    // The shortest round trip gives the tightest safe age upper bound.
                                    if (best == null || candidate.roundTrip() < best.roundTrip()) best = candidate;
                                }
                                if (best != null) timing.set(best);
                            }
                            catch (IOException | RuntimeException ignored) { /* Old sample expires; never reset age on arrival. */ }
                            try { Thread.sleep(1000); }
                            catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
                        }
                    }, "AR-clock");
                    clockWorker.start();
                    try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                        while (current(tokenId) && SystemClock.elapsedRealtime() < deadline) {
                            int length = in.readInt();
                            if (!ArFrameFormat.validJpegLength(length)) throw new IOException("Invalid frame size");
                            byte[] jpeg = new byte[length]; in.readFully(jpeg);
                            if (nativeOwned != inputNative) throw new IOException("Video input mode changed");
                            if (avc!=null) {
                                int before=frames;
                                try {
                                    ArH264Packet packet=ArH264Packet.decode(jpeg,geometryProtocol);
                                    avc.accept(packet);
                                }
                                catch (IOException | RuntimeException failure) { avcAllowed=false;throw failure; }
                                if (frames>before) attempt=0;
                                inputCodec="h264/"+avc.codecName();
                                continue;
                            }
                            ArVideoPacket packet = ArVideoPacket.decode(jpeg, geometryProtocol);
                            jpeg = packet.jpeg; length = jpeg.length;
                            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(jpeg, 0, length, bounds);
                            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 4096 || bounds.outHeight > 2160)
                                throw new IOException("Invalid image dimensions");
                            if (!current(tokenId)) return;
                            frames++; status = "已接收 C3 实景 · 只读测试";
                            sourceArrival();
                            // A recovered stream gets a fresh bounded reconnect budget.
                            attempt = 0;
                            ClockSample sample = timing.get();
                            long frameAt = com.garan.tesnav.ui.ArVideoFrames.publish(jpeg, packet.geometry, sample.epoch, sample.started);
                            if (frameAt < 0) continue;
                            if (inputNative) {
                                status="Native AR requires calibrated H264 input";
                                continue;
                            }
                            sourceFrame();
                            resumeConsumer();
                            if (!needsFrame()) continue;
                            Bitmap image = BitmapFactory.decodeByteArray(jpeg, 0, length);
                            if (image == null) throw new IOException("Invalid JPEG");
                            try {
                                if (current(tokenId) && SystemClock.elapsedRealtime()-frameAt <= 500) publish(image);
                            } finally { image.recycle(); }
                        }
                        if (current(tokenId)) status = "本次实景测试已结束";
                        return;
                    }
                } catch (IOException | RuntimeException e) {
                    if (!current(tokenId)) return;
                    // 2026-10-23: keep AMap's camera session alive across a transient socket
                    // failure. Announcing "camera closed / disconnected" here is what ended
                    // the AR session for good (AMap then calls stopPreview and never comes
                    // back). Only the socket is retried; frames resume when it returns.
                    note("soft-reconnect " + e.getClass().getSimpleName());
                    status = "视频重连中（" + e.getClass().getSimpleName() + "）";
                } finally {
                    if (avc!=null) avc.close();
                    if (clockWorker != null) clockWorker.interrupt();
                    if (socket != null) socket.disconnect();
                    if (connection == socket) connection = null;
                }
                if (SystemClock.elapsedRealtime() >= deadline) break;
                if (attempt < 4) Thread.sleep(1000L << attempt);
                else Thread.sleep(10_000L);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        finally {
            new Handler(getMainLooper()).post(() -> { if (generation == tokenId) stopTest(); });
        }
    }

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String[] callers = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (callers == null || !Arrays.asList(callers).contains(SHIM)) throw new SecurityException("AR shim only");
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESC); return true; }
            data.enforceInterface(DESC); data.readString(); // Caller label is not authentication.
            try {
                int result = 1;
                note("binder code=" + code);
                synchronized (C3ArCameraService.this) {
                    switch (code) {
                        case 1:
                            IBinder receiver = data.readStrongBinder();
                            if (receiver != callback) {
                                setCallback(receiver);
                                if (callback != null) callback.linkToDeath(callbackDeath, 0);
                            }
                            notifyState(sourceReady ? 1 : 2);
                            break;
                        case 2: data.readStrongBinder(); releaseCallback(); break;
                        case 5: result = sourceReady ? 1 : 0; break;
                        case 6: result = opened ? 1 : 0; break;
                        case 7:
                            if (data.readInt() != 0) {
                                int nextFormat = data.readInt(); data.readInt(); data.readInt();
                                int nextWidth = data.readInt(), nextHeight = data.readInt(); data.readString();
                                ArFrameFormat.imageSize(nextWidth, nextHeight, storageFormat(nextFormat));
                                if (nextFormat != format || nextWidth != width || nextHeight != height) {
                                    if (opened) notifyState(4);
                                    closeMemory();
                                }
                                format = nextFormat; width = nextWidth; height = nextHeight;
                            }
                            if (output != null) { output.release(); output = null; }
                            if (data.readInt() != 0) output = Surface.CREATOR.createFromParcel(data);
                            break;
                        case 8:
                            openRequested = true;
                            resumeConsumer();
                            break;
                        case 9: case 10:
                            openRequested = false;
                            if (opened) notifyState(4);
                            closeMemory(); break;
                        default: return super.onTransact(code, data, reply, flags);
                    }
                }
                reply.writeNoException(); reply.writeInt(result);
            } catch (Exception e) {
                reply.writeNoException(); reply.writeInt(0);
                status = "高德 AR 接口失败（" + e.getClass().getSimpleName() + "）";
            }
            return true;
        }
    };
    private void openMemory(int request) {
        Parcel temp = Parcel.obtain(), data = Parcel.obtain(), reply = Parcel.obtain();
        ParcelFileDescriptor fd = null;
        int ticket = -1;
        try {
            IBinder receiver;
            synchronized (this) {
                if (request != openGeneration || !sourceReady || !openRequested || callback == null) return;
                closeMemory(); ticket = openGeneration;
                receiver = callback;
                // 2026-10-23: returning the display size here made AMap render a blank
                // preview, so the returned size must stay equal to the requested one.
                int size = ArFrameFormat.imageSize(width, height, storageFormat(format));
                shared = SharedMemory.create("C3AR", size + 20); memory = shared.mapReadWrite(); memory.put(0, (byte)1);
                shared.writeToParcel(temp, 0); temp.setDataPosition(0); fd = temp.readFileDescriptor();
                data.writeInterfaceToken(CALLBACK); data.writeInt(1); fd.writeToParcel(data, 0); data.writeInt(1);
                data.writeInt(format); data.writeInt(0); data.writeInt(0); data.writeInt(width); data.writeInt(height);
                data.writeString("C3-road"); data.writeInt(size); data.writeString("C3AR");
            }
            // Never hold the memory lock across a remote callback: AMap may query opened state from it.
            if (!receiver.transact(3, data, reply, 0)) throw new IOException("AR callback rejected");
            reply.readException();
            synchronized (this) {
                if (ticket == openGeneration && sourceReady && openRequested && receiver == callback) {
                    opened = true; callbackHandler.removeCallbacks(nativeRetry);
                    com.garan.tesnav.ui.ArVideoFrames.setNativeCameraOpen(nativeOwned);
                }
            }
        } catch (Exception e) {
            synchronized (this) { if (ticket == openGeneration) closeMemory(); }
            status = "高德共享图像打开失败（" + e.getClass().getSimpleName() + "）";
        } finally {
            if (fd != null) try { fd.close(); } catch (IOException ignored) {}
            temp.recycle(); data.recycle(); reply.recycle();
        }
    }
    private synchronized void publish(Bitmap source) {
        if (!opened || memory == null || memory.get(0) != 1) { note("no-consume flag!=1"); return; }
        lastConsumeAt = SystemClock.elapsedRealtime();
        if (target == null) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pixels = new int[width * height]; yuv = new byte[ArFrameFormat.imageSize(width, height, storageFormat(format))];
        }
        // Preserve aspect ratio. Black padding still requires fresh AR calibration; do not stretch the optics.
        Canvas canvas = new Canvas(target); canvas.drawColor(Color.BLACK);
        float scale = Math.min(width / (float)source.getWidth(), height / (float)source.getHeight());
        float w = source.getWidth() * scale, h = source.getHeight() * scale;
        canvas.drawBitmap(source, null, new RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        target.getPixels(pixels, 0, width, 0, 0, width, height);
        ArFrameFormat.toYuv(pixels, yuv, width, height, storageFormat(format));
        memory.position(20); memory.put(yuv); memory.put(0, (byte)0); published++;
        if (output != null && output.isValid()) {
            Canvas surfaceCanvas = null;
            try { surfaceCanvas = output.lockCanvas(null); surfaceCanvas.drawBitmap(target, null,
                    new Rect(0, 0, surfaceCanvas.getWidth(), surfaceCanvas.getHeight()), null); }
            catch (RuntimeException ignored) { /* Shared-memory consumer is independent of optional Surface. */ }
            finally { if (surfaceCanvas != null) output.unlockCanvasAndPost(surfaceCanvas); }
        }
    }
    private synchronized void publishYuv(android.media.Image image) throws IOException {
        if (!opened || memory==null || memory.get(0)!=1) return;
        if (output!=null && output.isValid()) throw new IOException("AVC Surface consumer requires compatibility mode");
        android.graphics.Rect crop=image.getCropRect();
        if (crop.width()!=width || crop.height()!=height) throw new IOException("AVC recorder size mismatch");
        int size=ArFrameFormat.imageSize(width,height,storageFormat(format));
        if (yuv==null || yuv.length!=size) yuv=new byte[size];
        android.media.Image.Plane[] p=image.getPlanes();
        ArYuv420.copy(new ArYuv420.Plane(p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride()),
            new ArYuv420.Plane(p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride()),
            new ArYuv420.Plane(p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride()),
            image.getWidth(),image.getHeight(),crop.left,crop.top,width,height,storageFormat(format),yuv);
        memory.position(20);memory.put(yuv);memory.put(0,(byte)0);published++;
    }
    private synchronized boolean needsFrame() {
        return opened && memory!=null && memory.get(0)==1;
    }
    private static int storageFormat(int standardFormat) {
        return ArFrameFormat.recorderStorageFormat(standardFormat);
    }
    private synchronized void sourceFrame() {
        if (!sourceReady) {
            sourceReady = true; sendRecorderStatus(true); notifyState(1);
            callbackHandler.removeCallbacks(freshnessCheck);
            callbackHandler.postDelayed(freshnessCheck, 3_001);
        }
        if (nativeRequest) requestNativeDisplay();
    }
    private synchronized void sourceArrival() { lastArrivalAt = SystemClock.elapsedRealtime(); }
    // Diagnostic only: detects the moment AMap stops pulling frames from the
    // shared buffer while the video session stays open.
    private final Runnable consumeWatch = new Runnable() { public void run() {
        synchronized (C3ArCameraService.this) {
            if (running && opened && lastConsumeAt > 0) {
                long idle = SystemClock.elapsedRealtime() - lastConsumeAt;
                if (idle > 1500) note("consume-stalled idleMs=" + idle);
            }
        }
        callbackHandler.postDelayed(this, 700);
    }};
    private final Runnable freshnessCheck = new Runnable() { public void run() {
        synchronized (C3ArCameraService.this) {
            if (!running || !sourceReady) return;
            // A valid packet proves the camera connection is alive even while a
            // transient clock sample makes that packet too old to display.
            // Display publication still keeps its separate 500ms age gate.
            long remaining = 3_001-(SystemClock.elapsedRealtime()-lastArrivalAt);
            if (remaining <= 0) {
                // Do not tear the AR session down: telling AMap the camera closed makes it
                // stop preview and stay dead until the user re-enters. Break the stalled
                // read instead so the retry loop reconnects and frames resume.
                note("stall-reconnect");
                status = "视频停滞，正在重连";
                HttpURLConnection stalled = connection;
                if (stalled != null) try { stalled.disconnect(); } catch (RuntimeException ignored) { }
                callbackHandler.postDelayed(this, 3_001);
                return;
            }
            callbackHandler.postDelayed(this, remaining);
        }
    }};
    private void requestNativeDisplay() {
        nativeRequest = false;
        if (nativeAttempts >= 3) return;
        nativeAttempts++;
        callbackHandler.post(() -> sendBroadcast(new Intent("AUTONAVI_STANDARD_BROADCAST_RECV")
            .setComponent(new ComponentName("com.autonavi.amapauto", "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"))
            .putExtra("KEY_TYPE",12112).putExtra("EXTRA_NAVI_TYPE",1).putExtra("EXTRA_CRUISE_TYPE",1)));
        callbackHandler.removeCallbacks(nativeRetry);
        callbackHandler.postDelayed(nativeRetry, nativeAttempts*1_000L);
    }
    private void sendRecorderStatus(boolean ready) {
        callbackHandler.post(() -> sendBroadcast(new Intent("AUTONAVI_STANDARD_BROADCAST_RECV")
            .setPackage("com.autonavi.amapauto").putExtra("KEY_TYPE",12114).putExtra("EXTRA_CAMERA_STATE",ready ? 1 : 0)));
    }
    private synchronized boolean publishCalibration(ArH264Packet packet) throws IOException {
        long now = SystemClock.elapsedRealtime();
        byte[] bytes = ArCameraCalibration.encode(packet, now);
        if (!ArCameraCalibration.hasValidHeight(bytes)) return false;
        if (now-lastCalibrationAt < 1000) return true;
        File dir = getExternalFilesDir(null);
        if (dir == null) throw new IOException("Calibration storage unavailable");
        File staged = new File(dir,"c3-amap-camera.tmp"), current = new File(dir,"c3-amap-camera.bin");
        try (FileOutputStream stream = new FileOutputStream(staged)) { stream.write(bytes); }
        if (!staged.renameTo(current)) throw new IOException("Calibration publish failed");
        lastCalibrationAt = now;
        return true;
    }
    private synchronized void clearCalibration() {
        File dir = getExternalFilesDir(null);
        if (dir != null) {
            new File(dir,"c3-amap-camera.bin").delete();
            new File(dir,"c3-amap-camera.tmp").delete();
        }
        lastCalibrationAt = 0;
    }
    private void sendCameraIdentity() {
        // Identity only; validated optics arrive with the authenticated video metadata.
        callbackHandler.post(() -> sendBroadcast(new Intent("AUTONAVI_STANDARD_BROADCAST_RECV")
            .setComponent(new ComponentName("com.autonavi.amapauto", "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"))
            .putExtra("KEY_TYPE",12116)
            .putExtra("cameraDisplay","C3RemoteDisplay")
            .putExtra("productModel",Build.MODEL)
            .putExtra("cameraName","C3NarrowRoad")
            .putExtra("productName","comma3")
            .putExtra("imu","none")
            .putExtra("cameraConnect","network")
            .putExtra("apkName",SHIM)
            .putExtra("serviceAction","com.autonavi.amapauto.gdarcameraservice")
            .putExtra("common",false)));
        android.util.Log.i("Tenengkai-NativeAr", "cameraIdentity=C3NarrowRoad common=false optics=frame-metadata");
    }
    private void notifyState(int code) {
        IBinder receiver = callback;
        note("notifyState code=" + code + " hasCb=" + (receiver != null));
        if (receiver == null) return;
        callbackHandler.post(() -> {
            Parcel data=Parcel.obtain(), reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(CALLBACK);
                if (code == 4) { data.writeInt(0); data.writeString(""); }
                receiver.transact(code,data,reply,0); reply.readException();
            } catch (RemoteException | RuntimeException e) {
                android.util.Log.w("Tenengkai-NativeAR","callback_failed code="+code+" type="+e.getClass().getSimpleName());
            } finally { data.recycle(); reply.recycle(); }
        });
    }
    private synchronized void disconnectedStream() {
        clearCalibration();
        com.garan.tesnav.ui.ArVideoFrames.clear();
        boolean resume = openRequested || reopenAfterReconnect;
        if (opened) notifyState(4);
        if (sourceReady) { sourceReady = false; sendRecorderStatus(false); notifyState(2); }
        closeMemory(); reopenAfterReconnect = resume;
    }
    private synchronized void resumeConsumer() {
        if ((!openRequested && !reopenAfterReconnect) || opened || callback == null || !sourceReady) return;
        reopenAfterReconnect = false;
        int request = ++openGeneration;
        callbackHandler.post(() -> openMemory(request));
    }
    private synchronized void closeMemory() {
        ++openGeneration; opened = false; reopenAfterReconnect = false;
        com.garan.tesnav.ui.ArVideoFrames.setNativeCameraOpen(false);
        if (memory != null) { SharedMemory.unmap(memory); memory = null; }
        if (shared != null) { shared.close(); shared = null; }
        if (target != null) { target.recycle(); target = null; }
        pixels = null; yuv = null;
    }
    private synchronized void releaseCallback() {
        setCallback(null);
        if (output != null) { output.release(); output = null; }
    }
    private synchronized void setCallback(IBinder receiver) {
        if (opened) notifyState(4);
        closeMemory();
        openRequested = false;
        if (callback != null) {
            try { callback.unlinkToDeath(callbackDeath, 0); } catch (java.util.NoSuchElementException ignored) {}
        }
        callback = receiver;
    }
    private void stopTest() {
        com.garan.tesnav.ui.ArVideoFrames.clear();
        com.garan.tesnav.ui.ArVideoFrames.setNativeArPreferred(false);
        running = false; ++generation;
        HttpURLConnection socket = connection; if (socket != null) socket.disconnect();
        if (worker != null) { worker.interrupt(); worker = null; }
        if (videoWifiLock != null) {
            if (videoWifiLock.isHeld()) videoWifiLock.release();
            videoWifiLock = null;
        }
        disconnectedStream(); openRequested = false; nativeRequest = false;
        callbackHandler.removeCallbacks(freshnessCheck);
        callbackHandler.removeCallbacks(nativeRetry);
        releaseCallback(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        if (shimBound) { unbindService(shimConnection); shimBound = false; }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onTimeout(int startId, int fgsType) { status = "系统已结束 AR 视频服务"; stopTest(); }
    @Override protected synchronized void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("frames="+frames+" published="+published+" running="+running+" ready="+sourceReady+
            " opened="+opened+" requested="+openRequested+" format="+format+" size="+width+"x"+height);
        writer.println("nativeArStatus="+nativeArStatus+" nativeStatusAgeMs="+
            (nativeStatusAt == 0 ? -1 : SystemClock.elapsedRealtime()-nativeStatusAt)+" modeResponse="+nativeResponse);
        writer.println("inputMode="+(nativeOwned ? "native" : roadOwned ? "enhanced" : "preview")+
            " codec="+inputCodec+" resolvedHost="+resolvedHost+" status="+status);
    }
    @Override public void onDestroy() { stopTest(); unregisterReceiver(recorderReceiver); callbacks.quitSafely(); super.onDestroy(); }
}
