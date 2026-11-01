package com.freddyq.diplay.colorprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import dalvik.system.PathClassLoader;
import java.io.FileInputStream;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Normal OEM SDK client in this diagnostic's own UID/process. No injection or permission changes. */
final class NativeColorAccess {
    interface Listener { void log(String message); void ready(boolean ready); }
    private static final String OEM_PACKAGE = "com.wt.tinnovesmartscene";
    private static final String OEM_SHA = "dc43014c84ef7272919d7a03ce1ec8a51656d568906f392a737f81b7d9ed31e1";
    private final Listener listener;
    private volatile boolean active = true;
    private Object car, manager;
    private Integer original;
    NativeColorAccess(Listener listener) { this.listener = listener; }
    NativeColorAccess(Listener listener, Object manager, int original) {
        this(listener); this.manager = manager; this.original = original;
    }
    void connect(Context context, Handler worker) throws Exception {
        if (!"S311_MCA".equals(Build.MODEL)) throw new IllegalStateException("仅核验本车S311_MCA");
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(OEM_PACKAGE, 0);
        if (!OEM_SHA.equals(sha256(info.sourceDir))) throw new IllegalStateException("原厂SDK版本与分析原件不同，停止");
        ClassLoader loader = new PathClassLoader(info.sourceDir, context.getClassLoader());
        Class<?> carClass = loader.loadClass("com.openos.virtualcar.VirtualCar");
        Class<?> callbackClass = loader.loadClass("com.openos.virtualcar.VirtualServiceReadyListener");
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "CoverColorProbeSdkListener";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
            }
            worker.post(this::readManager);
            return null;
        });
        car = carClass.getMethod("createVirtualCar", Context.class, callbackClass, Handler.class)
            .invoke(null, context.getApplicationContext(), callback, worker);
        listener.log("已按原厂SDK连接；调用权限仍属于本测试App");
    }
    private void readManager() {
        if (!active || car == null) return;
        try {
            manager = car.getClass().getMethod("getVirtualCarManager", String.class)
                .invoke(car, "virtualcar_property_service");
            if (manager == null) { listener.log("原厂服务尚未就绪"); return; }
            if (!Boolean.TRUE.equals(manager.getClass().getMethod("isSupport", int.class, int.class)
                    .invoke(manager, ColorWritePolicy.COLOR_PROPERTY, ColorWritePolicy.AREA))) {
                throw new IllegalStateException("原车未报告支持本颜色属性");
            }
            int value = read();
            if (value < 0 || value > 255) throw new IllegalStateException("颜色回值超出已核验的字节范围");
            if (original == null) original = value;
            listener.log("读取原色=" + original + "，当前值=" + value + "；尚未写入"); listener.ready(true);
        } catch (Exception | LinkageError error) { listener.log("读取失败：" + describe(error)); listener.ready(false); }
    }
    int read() throws Exception {
        checkConnected();
        Object value = manager.getClass().getMethod("getValue", int.class, int.class)
            .invoke(manager, ColorWritePolicy.COLOR_PROPERTY, ColorWritePolicy.AREA);
        if (!(value instanceof Integer)) throw new IllegalStateException("不是整数颜色回值");
        return (Integer)value;
    }
    void write(int value) throws Exception {
        checkConnected(); int target = ColorWritePolicy.validate(value, original);
        Object result = manager.getClass().getMethod("setValue", int.class, int.class, Object.class)
            .invoke(manager, ColorWritePolicy.COLOR_PROPERTY, ColorWritePolicy.AREA, Integer.valueOf(target));
        listener.log("仅写当前颜色：0x314003a6 area0 value=" + target + "，原厂返回=" + result + "；不代表实体灯光成功");
    }
    Integer original() { return original; }
    void close() { active = false; manager = null; car = null; listener.ready(false); }
    private void checkConnected() throws Exception {
        if (!active || manager == null) throw new IllegalStateException("尚未连接或已退出");
        // The property manager exposes no public isConnected(). Readiness comes from the SDK callback;
        // getValue/setValue themselves report a failed or disconnected transport.
    }
    static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(path)) { int size; while ((size = input.read(buffer)) > 0) digest.update(buffer, 0, size); }
        StringBuilder text = new StringBuilder(); for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
