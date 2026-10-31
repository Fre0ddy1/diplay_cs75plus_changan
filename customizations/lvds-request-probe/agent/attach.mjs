// Run only after explicit approval of injection into the parked vehicle's projection process.
import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [flag, fridaModule, bundlePath, pidText, logPath] = process.argv.slice(2);
if (flag !== '--approved-injection' || !fridaModule || !bundlePath || !/^\d+$/.test(pidText || '') || !logPath) {
    throw new Error('Usage: attach.mjs --approved-injection FRIDA_MODULE BUNDLE PID D_DRIVE_LOG');
}
if (!/^[dD]:[\\/]/.test(logPath)) throw new Error('Log must stay on D drive');
const frida = await import(pathToFileURL(path.resolve(fridaModule)).href);
const device = await frida.getDeviceManager().addRemoteDevice('127.0.0.1:27042');
const pid = Number(pidText);
const processInfo = (await device.enumerateProcesses()).find(item => item.pid === pid);
if (processInfo?.name !== 'com.incall.interactive') throw new Error('Exact current target process required');
const source = await fs.readFile(bundlePath, 'utf8');
const session = await device.attach(pid);
let script, eternalized = false;
const messages = [];
try {
    script = await session.createScript(source, { name: 'lvds-request-probe-66' });
    script.message.connect(message => { messages.push(message); console.log(JSON.stringify(message)); });
    await script.load();
    let state;
    for (let i = 0; i < 20; i++) {
        state = await script.exports.status();
        if (state.installed || state.failure) break;
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    if (!state?.installed || state.failure) throw new Error('Agent not ready: ' + JSON.stringify(state));
    // ADB must be closed for projection. Eternalize retains the bounded in-memory hook after detach.
    await script.eternalize();
    eternalized = true;
    await fs.writeFile(logPath, JSON.stringify({ pid, target: processInfo.name, ttlMs: 600000,
        maxRequests: 3, state, eternalized, messages }, null, 2));
    console.log('Ready: hook restores original dispatch after 10 minutes. Reboot removes injected runtime.');
} finally {
    if (script && !eternalized) await script.unload().catch(() => {});
    await session.detach();
}
