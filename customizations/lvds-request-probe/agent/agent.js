import Java from 'frida-java-bridge';

// This private transaction exists only while this diagnostic script is attached/eternalized.
const TOKEN = 'com.incall.serversdk.interactive.IDouInteractiveManager';
const TRANSACTION = 0x00660001;
const MAGIC = 0x4c564453;
const VERSION = 1;
const PACKAGE = 'com.shihab.diplay.hudtest';
const TTL_MS = 10 * 60 * 1000;
const MAX_REQUESTS = 3;
let installed = false;
let failure = null;
let hook = null;
let consumed = new Set();

Java.perform(() => {
    try {
        const Log = Java.use('android.util.Log');
        const Clock = Java.use('android.os.SystemClock');
        const Binder = Java.use('android.os.Binder');
        const ActivityThread = Java.use('android.app.ActivityThread');
        const Receiver = Java.use('com.incall.interactive.socket.iowork.datahandle.IPDataCallback');
        const Stub = Java.use(TOKEN + '$Stub');
        const started = Number(Clock.elapsedRealtime());
        const report = text => { Log.i('DiPlay-LvdsProbe66', text); send({ kind: 'lvds-probe', text }); };
        hook = Stub.onTransact.overload('int', 'android.os.Parcel', 'android.os.Parcel', 'int');
        hook.implementation = function (code, data, reply, flags) {
            if (code !== TRANSACTION) return hook.call(this, code, data, reply, flags);
            let status = -1;
            let ticket = '';
            try {
                const pm = ActivityThread.currentApplication().getPackageManager();
                const packages = pm.getPackagesForUid(Binder.getCallingUid());
                const allowed = packages !== null && Array.from(packages).some(name => String(name) === PACKAGE);
                if (!allowed || flags !== 0 || reply === null) return true;
                data.enforceInterface(TOKEN);
                const magic = data.readInt(), version = data.readInt();
                const cmd = data.readInt(), sub = data.readInt(), length = data.readInt(), value = data.readInt();
                ticket = String(data.readString());
                const valid = magic === MAGIC && version === VERSION && cmd === 0x1b && sub === 5 &&
                    length === 1 && value === 3 && /^[0-9a-f-]{36}$/.test(ticket) && data.dataAvail() === 0;
                if (valid && Number(Clock.elapsedRealtime()) - started < TTL_MS) {
                    if (consumed.has(ticket) || consumed.size >= MAX_REQUESTS) status = -2;
                    else {
                        // Never construct a new receiver or replace its real data transport.
                        const receiver = Receiver.mInstance.value;
                        if (receiver === null) status = 0;
                        else {
                            consumed.add(ticket); // An exception may follow side effects: never replay.
                            report('invoke ticket=' + ticket + ' cmd=1b/05 len=1 value=3 uid=' + Binder.getCallingUid());
                            receiver.dealLVDSCode(3);
                            status = 1; // Handler returned; no claim of visible fullscreen/source.
                        }
                    }
                }
            } catch (error) { report('request failed ticket=' + ticket + ' error=' + error); }
            if (reply !== null) {
                reply.writeNoException(); reply.writeInt(MAGIC); reply.writeInt(VERSION); reply.writeInt(status);
            }
            return true;
        };
        installed = true;
        setTimeout(() => {
            Java.perform(() => {
                hook.implementation = null;
                installed = false;
                report('expired; original Binder dispatch restored');
            });
        }, TTL_MS);
        report('ready ttlMs=' + TTL_MS + ' maxRequests=' + MAX_REQUESTS);
    } catch (error) { failure = String(error); send({ kind: 'lvds-probe-error', error: failure }); }
});

rpc.exports = {
    status() { return { installed, failure, consumed: consumed.size }; },
    stop() {
        return new Promise(resolve => Java.perform(() => {
            if (hook !== null) hook.implementation = null;
            installed = false;
            resolve(true);
        }));
    }
};
