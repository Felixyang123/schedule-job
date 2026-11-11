const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/admin.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const storage = (initial = {}) => {
  const values = new Map(Object.entries(initial));
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
};
function page(session = storage()) {
  const nodes = new Map();
  for (const match of html.matchAll(/id="([^"]+)"/g)) {
    const classes = new Set(['hidden']);
    nodes.set('#' + match[1], {value: '', innerText: '', innerHTML: '', style: {},
      classList: {toggle(c, on) { on ? classes.add(c) : classes.delete(c); }, add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c)},
      addEventListener() {}, appendChild() {}});
  }
  const requests = [];
  let responder = () => ({success: true, data: []});
  const local = storage();
  const context = {Headers, sessionStorage: session, localStorage: local, console: {error() {}}, alert() {},
    document: {querySelector(selector) {
      if (selector.endsWith(' tbody')) return {innerHTML: '', appendChild() {}};
      assert.ok(nodes.has(selector), 'missing DOM element ' + selector);
      return nodes.get(selector);
    }, querySelectorAll: () => []},
    fetch: async (url, options = {}) => {
      requests.push({url, options});
      const result = responder(url, options);
      return {ok: (result.status ?? 200) < 400, status: result.status ?? 200, json: async () => result};
    }};
  vm.runInNewContext(script, context);
  return {nodes, requests, session, local, respond(fn) {responder = fn;},
    async login(forced) {
      responder = url => url.endsWith('/auth/login') ? {success: true, data: {token: 'jwt', mustChangePassword: forced}} : {success: true, data: []};
      nodes.get('#login-username').value = 'admin';
      nodes.get('#login-password').value = 'initial-secret';
      await nodes.get('#login-form').onsubmit({preventDefault() {}});
    }};
}
test('anonymous page does not load management data', () => {
  const p = page();
  assert.equal(p.requests.length, 0);
  assert.equal(p.nodes.get('#login-view').classList.contains('hidden'), false);
});
test('login uses same origin and keeps bearer token only in session storage', async () => {
  const p = page(); await p.login(false);
  assert.equal(p.requests[0].url, '/admin/auth/login');
  assert.equal(p.session.getItem('job.admin.token'), 'jwt');
  assert.equal(p.local.getItem('job.admin.token'), null);
  assert.equal(p.requests[1].options.headers.get('Authorization'), 'Bearer jwt');
});
test('first login and reload remain at password change without management requests', async () => {
  const p = page(); await p.login(true);
  assert.equal(p.requests.length, 1);
  const reloaded = page(p.session);
  assert.equal(reloaded.requests.length, 0);
  assert.equal(reloaded.nodes.get('#password-view').classList.contains('hidden'), false);
});
test('successful password change clears token and requires a fresh login', async () => {
  const p = page(); await p.login(true);
  p.respond(() => ({success: true}));
  p.nodes.get('#old-password').value = 'initial-secret';
  p.nodes.get('#new-password').value = 'changed-secret';
  await p.nodes.get('#password-form').onsubmit({preventDefault() {}});
  assert.equal(p.requests.length, 2);
  assert.equal(p.session.getItem('job.admin.token'), null);
  assert.equal(p.local.getItem('job.admin.token'), null);
  assert.equal(p.nodes.get('#login-view').classList.contains('hidden'), false);
});
test('401 during password change stays on login view', async () => {
  const p = page(); await p.login(true);
  p.respond(() => ({status: 401, success: false}));
  await p.nodes.get('#password-form').onsubmit({preventDefault() {}});
  assert.equal(p.nodes.get('#login-view').classList.contains('hidden'), false);
  assert.equal(p.nodes.get('#password-view').classList.contains('hidden'), true);
});
test('signed in user can voluntarily open password form and logout clears storage', async () => {
  const p = page(); await p.login(false);
  assert.ok(p.nodes.has('#change-password'));
  p.nodes.get('#change-password').onclick();
  assert.equal(p.nodes.get('#password-view').classList.contains('hidden'), false);
  await p.nodes.get('#logout').onclick();
  assert.equal(p.session.getItem('job.admin.token'), null);
  assert.equal(p.nodes.get('#login-view').classList.contains('hidden'), false);
});
