import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = fs.readFileSync(new URL('./agent.js', import.meta.url), 'utf8')
    .replace("import Java from 'frida-java-bridge';", '');
function fixture() {
    const state = { now: 1000, allowed: true, ready: true, requests: 0, delegated: 0, throws: false };
    const hook = { implementation: null, call() { state.delegated++; return false; } };
    const classes = {
        'android.util.Log': { i() {} },
        'android.os.SystemClock': { elapsedRealtime: () => state.now },
        'android.os.Binder': { getCallingUid: () => 12345 },
        'android.app.ActivityThread': { currentApplication: () => ({ getPackageManager: () => ({
            getPackagesForUid: () => [state.allowed ? 'com.shihab.diplay.hudtest' : 'other.package']
        }) }) },
        'com.incall.interactive.socket.iowork.datahandle.IPDataCallback': { mInstance: {
            get value() { return state.ready ? { dealLVDSCode(code) {
                assert.equal(code, 3); state.requests++;
                if (state.throws) throw new Error('failure after side effect');
            } } : null; }
        } },
        'com.incall.serversdk.interactive.IDouInteractiveManager$Stub': { onTransact: { overload: () => hook } }
    };
    const sandbox = { Java: { perform: fn => fn(), use: name => {
        assert.ok(classes[name], name); return classes[name];
    } }, rpc: {}, send() {}, setTimeout(fn) { state.expire = fn; }, Promise, Set, Array };
    vm.runInNewContext(source, sandbox);
    assert.equal(sandbox.rpc.exports.status().installed, true);
    const request = (ticket, value = 3) => {
        const values = [0x4c564453, 1, 0x1b, 5, 1, value]; let position = 0;
        const data = { enforceInterface(token) { assert.equal(token, 'com.incall.serversdk.interactive.IDouInteractiveManager'); },
            readInt: () => values[position++], readString: () => ticket, dataAvail: () => 0 };
        const result = [];
        hook.implementation(0x00660001, data, { writeNoException() {}, writeInt: n => result.push(n) }, 0);
        return result.at(-1);
    };
    return { state, hook, request, rpc: sandbox.rpc.exports };
}
const ticket = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

test('original Binder transactions remain delegated; other apps cannot call the receiver', () => {
    const f = fixture();
    for (const code of [1, 2, 8, 21, 29, 30]) f.hook.implementation(code, null, null, 0);
    assert.equal(f.state.delegated, 6);
    f.state.allowed = false; f.request(ticket(1));
    assert.equal(f.state.requests, 0);
});
test('only the observed full request is accepted and a missing original singleton is left untouched', () => {
    const f = fixture();
    assert.equal(f.request(ticket(1), 1), -1);
    f.state.ready = false; assert.equal(f.request(ticket(1)), 0);
    assert.equal(f.state.requests, 0);
});
test('duplicates and ambiguous failures never repeat the original handler', () => {
    const f = fixture();
    f.state.throws = true; assert.equal(f.request(ticket(1)), -1);
    f.state.throws = false; assert.equal(f.request(ticket(1)), -2);
    assert.equal(f.state.requests, 1);
});
test('three requests maximum; elapsed-time deadline rejects requests before timer cleanup', () => {
    const f = fixture();
    for (let i = 1; i <= 3; i++) assert.equal(f.request(ticket(i)), 1);
    assert.equal(f.request(ticket(4)), -2);
    assert.equal(f.state.requests, 3);
    const expired = fixture(); expired.state.now += 600000;
    assert.equal(expired.request(ticket(1)), -1);
    expired.state.expire();
    assert.equal(expired.hook.implementation, null);
    assert.equal(expired.rpc.status().installed, false);
});
