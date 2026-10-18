const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../static/index.html'), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];

for (const succeeds of [true, false]) {
  test(`local revocation stops sending before network completes (${succeeds ? 'online' : 'offline'})`, async () => {
    const elements = new Map();
    const get = (id) => {
      if (!elements.has(id)) elements.set(id, {value: '', textContent: '', disabled: false, checked: true, classList: {add() {}, remove() {}, toggle() {}}});
      return elements.get(id);
    };
    let complete;
    const pending = new Promise((resolve, reject) => { complete = succeeds ? resolve : reject; });
    const context = vm.createContext({document: {getElementById: get, querySelectorAll: () => []}, fetch: () => pending});
    vm.runInContext(source, context);
    vm.runInContext("deviceToken='temporary-test-credential'; deviceId='test-device'; consentContext={};", context);
    const request = get('revoke-local').onclick();
    assert.equal(vm.runInContext('deviceToken', context), null);
    assert.equal(get('send-event').disabled, true);
    assert.equal(get('enroll').disabled, true);
    if (succeeds) complete({ok: true, status: 204}); else complete(new Error('Network unavailable'));
    await request;
    assert.equal(get('enroll').disabled, false);
    assert.equal(vm.runInContext('deviceToken', context), null);
    assert.match(get('agent-status').textContent, succeeds ? /Consentimiento revocado/ : /no se confirmó/);
  });
}
