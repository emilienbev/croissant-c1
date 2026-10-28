/*
 * The page the C1 serves to a phone, on the JSON of served/ConfigApi.kt: providers and
 * their keys, Ask's long prompts, export and import.
 *
 * Every word from the device or a file reaches the page as text (text nodes, textContent),
 * never as markup. A key typed here goes to the R1 once and is never shown back: the API
 * answers with its last four only.
 */
'use strict';

(function () {

  /** ModelSlot's keys, as the page names them. */
  const SLOTS = [['chat', 'chat'], ['stt', 'speech'], ['text_to_image', 'pictures'], ['image_to_image', 'transform']];
  /** ConfigServer.BODY_MAX: a body past this is refused unread. */
  const BODY_MAX = 256 * 1024;
  /** ConfigFile.TEXT_MAX: a prompt's text. */
  const TEXT_MAX = 32000;
  /** PromptsStore.NAME_MAX: a prompt's name. */
  const NAME_MAX = 24;
  const FILE_NAME = 'croissant-settings.json';
  /** ConfigApi.KEYS_WIFI, for where the page says it before asking. */
  const KEYS_WIFI = 'keys travel over the tailnet only: turn tailscale on and scan again';
  const TABS = ['providers', 'prompts', 'export'];

  const view = document.getElementById('view');
  const tabButtons = Array.from(document.querySelectorAll('[data-tab]'));

  /** What the page holds: the part shown, the API's last answers, the prompt open. */
  const S = {
    tab: 'providers',
    /** Within providers: null for the list, else {kind: 'presets' | 'add' | 'own' | 'provider', id}. */
    sub: null,
    /** GET providers: {keys, providers, presets} (and way, once the API says it). */
    data: null,
    /** GET prompts: {prompts, picks}. */
    prompts: null,
    /** The prompt open: {name, head (with its text), saved (the R1's text), draft (the field's)}. */
    edit: null,
    /** Shows the bar asking what to do with unsaved words, then runs what was asked. */
    guard: null,
    closed: false,
    drawn: 0,
  };

  // ---- the DOM, text only ------------------------------------------------------------

  /** An element: [props] as attributes (on* as listeners, value as the property), [kids] as nodes or text. */
  function h(tag, props, ...kids) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'value') e.value = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : String(v));
    }
    for (const c of kids.flat(Infinity)) if (c !== null && c !== undefined && c !== false) e.append(c);
    return e;
  }

  const SVG = 'http://www.w3.org/2000/svg';

  /** A line glyph on the 24 grid, 2 wide, round, in the text's colour. */
  function glyph(d, size, cls) {
    const s = document.createElementNS(SVG, 'svg');
    for (const [k, v] of [['width', size], ['height', size], ['viewBox', '0 0 24 24'], ['aria-hidden', 'true']]) s.setAttribute(k, String(v));
    if (cls) s.setAttribute('class', cls);
    const p = document.createElementNS(SVG, 'path');
    for (const [k, v] of [['d', d], ['fill', 'none'], ['stroke', 'currentColor'], ['stroke-width', '2'],
      ['stroke-linecap', 'round'], ['stroke-linejoin', 'round']]) p.setAttribute(k, v);
    s.append(p);
    return s;
  }

  const chevron = () => glyph('M9 6l6 6-6 6', 22, 'chev');
  const tick = () => glyph('M4 12.5l5 5L20 6.5', 22);

  function say(el, words, bad) {
    el.textContent = words || '';
    el.classList.toggle('bad', Boolean(bad));
  }

  /** A test's or an import's outcome: the tick and [words], [then] under them. */
  function worked(box, words, then) {
    box.replaceChildren(tick(), h('div', null, h('p', null, words), then ? h('p', { class: 'sub' }, then) : null));
  }

  /** What went wrong, as the R1 or the provider said it, [then] under it. */
  function failed(box, words, then) {
    box.replaceChildren(h('div', null, h('p', { class: 'bad' }, words), then ? h('p', { class: 'sub' }, then) : null));
  }

  const count = n => n.toLocaleString('en');
  const joined = l => l.length < 3 ? l.join(' and ') : l.slice(0, -1).join(', ') + ' and ' + l[l.length - 1];

  // ---- the API -----------------------------------------------------------------------

  /** The session is gone: the page says so and asks nothing more. */
  class Gone extends Error {}

  /** A refusal with a line to say after it. */
  class Said extends Error {
    constructor(words, then) { super(words); this.then = then; }
  }

  /**
   * [method] on /api/[path], same origin with the session's cookie, [body] as JSON (a string
   * goes as it is). Throws [Gone] when the session is (the server answers an empty 404, as
   * to anyone without it), else an Error with the API's own words.
   */
  async function call(method, path, body) {
    const init = { method, credentials: 'same-origin', cache: 'no-store', headers: {} };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = typeof body === 'string' ? body : JSON.stringify(body);
    }
    let r;
    try {
      r = await fetch('/api/' + path, init);
    } catch (e) {
      throw new Error('the r1 did not answer. if it left its on a phone page, open it again and scan the new code');
    }
    if (r.ok) return r;
    const json = (r.headers.get('content-type') || '').startsWith('application/json');
    const said = json ? await r.json().then(o => (o && typeof o.error === 'string' ? o.error : null), () => null) : null;
    if (r.status === 401 || (!said && (r.status === 403 || r.status === 404))) {
      close();
      throw new Gone();
    }
    if (r.status === 413) throw new Error('that is more than the r1 takes at once (256 kb)');
    throw new Error(said || 'the r1 answered ' + r.status);
  }

  async function api(method, path, body) {
    const r = await call(method, path, body);
    try {
      return await r.json();
    } catch (e) {
      throw new Error('the r1 answered with something that is not json');
    }
  }

  /** A name as one path segment. */
  const seg = encodeURIComponent;

  /** The session gone: the page closes, nothing unsaved holds it open. */
  function close() {
    if (S.closed) return;
    S.closed = true;
    S.edit = null;
    document.querySelector('.tabs').hidden = true;
    document.getElementById('wifi').hidden = true;
    view.hidden = true;
    document.getElementById('gone').hidden = false;
  }

  /**
   * [work] once at a time from [button], which says [busy] meanwhile; what went wrong goes
   * to [fail] (never a gone session: the page has said that already).
   */
  async function act(button, busy, fail, work) {
    if (button.disabled) return;
    const was = button.textContent;
    button.disabled = true;
    if (busy) button.textContent = busy;
    try {
      await work();
    } catch (e) {
      if (!(e instanceof Gone)) fail(e.message, e.then);
    } finally {
      button.disabled = false;
      if (busy) button.textContent = was;
    }
  }

  /** A button that asks first: [sure] on the first tap, [work] on the second; it forgets after a while. */
  function twoStep(button, sure, busy, fail, work) {
    const was = button.textContent;
    let timer = 0;
    const calm = () => { clearTimeout(timer); button.textContent = was; button.classList.remove('sure'); };
    button.addEventListener('click', () => {
      if (!button.classList.contains('sure')) {
        button.textContent = sure;
        button.classList.add('sure');
        timer = setTimeout(calm, 4000);
        return;
      }
      calm();
      act(button, busy, fail, work);
    });
    return button;
  }

  // ---- the way it is served ----------------------------------------------------------

  /** Whether [host] is a tailnet address: Tailscale's IPv4 100.64.0.0/10, its IPv6 fd7a:115c:a1e0::/48. */
  function onTailnet(host) {
    const m = /^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$/.exec(host);
    if (m) return Number(m[1]) === 100 && (Number(m[2]) & 0xC0) === 64;
    return /^\[?fd7a:115c:a1e0:/i.test(host);
  }

  /**
   * The warning while on plain wi-fi. The server serves on one address only, the tailnet's
   * when Tailscale is up, and refuses any other Host, so the address this page came from
   * says the way (unless the API says it itself).
   */
  function showWay() {
    const way = S.data.way || (onTailnet(location.hostname) ? 'tailnet' : 'wifi');
    document.getElementById('wifi').hidden = way !== 'wifi';
    document.getElementById('wifi-words').textContent = S.data.keys
      ? 'what you type here, keys too, crosses the network unencrypted: use this page on a private network only'
      : 'what you type here crosses the network unencrypted: use this page on a private network only';
  }

  // ---- parts -------------------------------------------------------------------------

  function paintTabs() {
    for (const b of tabButtons) b.setAttribute('aria-selected', String(b.dataset.tab === S.tab));
  }

  /** Words not saved hold the page: [then] runs once they are saved or let go. */
  function leave(then) {
    const e = S.edit;
    if (S.tab !== 'prompts' || !e || !e.head || e.draft === e.saved || !S.guard) return then();
    S.guard(then);
  }

  /** [tab] shown, fresh from the R1 (its own pages may have changed something). */
  function go(tab) {
    if (tab === S.tab && !S.sub) return;
    leave(() => {
      S.tab = tab;
      S.sub = null;
      S.data = null;
      S.prompts = null;
      if (S.edit) S.edit = { name: S.edit.name };
      history.replaceState(null, '', '#' + tab);
      paintTabs();
      draw();
      window.scrollTo(0, 0);
    });
  }

  /** The part shown, drawn again; a late answer never draws over a newer part. */
  async function draw() {
    const n = ++S.drawn;
    let nodes;
    try {
      nodes = await build();
    } catch (e) {
      if (e instanceof Gone) return;
      const again = h('button', { class: 'btn', type: 'button', onclick: () => draw() }, 'try again');
      nodes = [h('p', { class: 'lede bad' }, e.message), h('div', { class: 'buttons' }, again)];
    }
    if (n !== S.drawn || S.closed) return;
    view.replaceChildren(...[nodes].flat(Infinity).filter(Boolean));
  }

  async function build() {
    if (!S.data) {
      S.data = await api('GET', 'providers');
      showWay();
    }
    if (S.tab === 'prompts') return promptsPart();
    if (S.tab === 'export') return exportPart();
    if (!S.sub) return providersPart();
    if (S.sub.kind === 'presets') return presetsPart();
    return providerPart(S.sub);
  }

  function open(sub) {
    S.sub = sub;
    draw();
    window.scrollTo(0, 0);
  }

  // ---- providers ---------------------------------------------------------------------

  /** A preset by its own name, in the page's lower case; one's own server as it was named. */
  const nameOf = p => (p.preset === false ? p.title : p.title.toLowerCase());

  function hostOf(url) {
    try {
      return new URL(url).hostname.replace(/^\[|\]$/g, '');
    } catch (e) {
      return url;
    }
  }

  /** What it stands on: its key by the last four (and a free tier), or where it is; [1] whether it asks for something. */
  function stands(p) {
    if (!p.url) return ['no server set on the r1 yet', true];
    if (p.hasKey) return [(p.key ? 'key …' + p.key : 'key kept') + (p.free ? ', free tier' : ''), false];
    if (p.needsKey) return ['no key yet', true];
    return ['on ' + (p.on || hostOf(p.url)), false];
  }

  /** What its last test found, by slot. */
  function offers(p) {
    if (!p.listed) return 'not tested yet';
    const parts = SLOTS.filter(([k]) => p.listed[k] > 0).map(([k, w]) => p.listed[k] + ' ' + w);
    return parts.length ? parts.join(', ') : 'no models it can use';
  }

  function providersPart() {
    const cards = S.data.providers.map(p => {
      const [line, asks] = stands(p);
      return h('button', { class: 'card', type: 'button', onclick: () => open({ kind: 'provider', id: p.id }) },
        h('span', { class: 'card-text' },
          h('span', { class: 'card-name' }, nameOf(p)),
          h('span', { class: asks ? 'card-line hue' : 'card-line' }, line),
          h('span', { class: 'card-line' }, offers(p))),
        chevron());
    });
    return [
      h('p', { class: 'lede' }, 'keys stay on the r1: this page sees only their last four'),
      h('div', { class: 'cards' }, cards),
      h('button', { class: 'btn wide', type: 'button', onclick: () => open({ kind: 'presets' }) }, 'add a provider'),
    ];
  }

  const backToList = () => h('button', { class: 'back', type: 'button', onclick: () => open(null) }, '‹ providers');

  function row(words, then) {
    return h('button', { class: 'card row', type: 'button', onclick: then },
      h('span', { class: 'card-text' }, h('span', { class: 'card-name' }, words)), chevron());
  }

  /** The presets not added yet, then a server of one's own. */
  function presetsPart() {
    const rows = S.data.presets.map(p => row(p.title.toLowerCase(), () => open({ kind: 'add', id: p.id })));
    rows.push(row('your own server', () => open({ kind: 'own' })));
    return [backToList(), h('h1', null, 'add a provider'), h('div', { class: 'cards rows' }, rows)];
  }

  /** A name typed for one's own server as an id the R1 takes (letters, digits and ._:-). */
  function idFrom(name) {
    const id = name.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9._:-]/g, '').replace(/^-+|-+$/g, '').slice(0, 80);
    return /^\.*$/.test(id) ? '' : id;
  }

  /**
   * A provider being added ([sub] add: a preset; own: a server of one's own) or one added
   * ([sub] provider): its address where it has one to give, its key where it takes one, and
   * test, which keeps what was typed and asks it for its models. One being added is kept only
   * if that works; remove at the foot of one added.
   */
  function providerPart(sub) {
    const d = S.data;
    const own = sub.kind === 'own';
    const adding = sub.kind !== 'provider';
    const p = own ? null : (adding ? d.presets : d.providers).find(x => x.id === sub.id);
    if (!own && !p) {
      S.sub = null;
      return providersPart();
    }
    const custom = own || p.preset === false;
    const takesAddress = custom || p.kind === 'ollama' || p.mac || (!adding && p.address != null);
    const takesKey = custom || p.needsKey;
    const now = () => (adding ? null : S.data.providers.find(x => x.id === p.id) || p);

    const f = {};
    const fields = [];
    const box = h('div', { class: 'result', role: 'status' });
    const fail = (words, then) => failed(box, words, then);

    const line = adding ? null : h('p', { class: 'sub' });
    const kept = h('span');
    const moving = h('p', { class: 'hint hue', hidden: true }, 'a new address drops the kept key');

    if (own) {
      f.name = h('input', { class: 'field', id: 'f-name', type: 'text', maxlength: 80, autocomplete: 'off',
        autocapitalize: 'off', spellcheck: 'false', placeholder: 'my-server' });
      fields.push(h('label', { for: 'f-name' }, 'name'), f.name);
    }
    if (takesAddress) {
      f.address = h('input', { class: 'field', id: 'f-address', type: 'url', inputmode: 'url', autocomplete: 'off',
        autocapitalize: 'off', spellcheck: 'false', value: adding ? '' : p.address || '',
        placeholder: custom ? 'http://host:port/v1' : (!adding && p.url) || 'the server\'s machine' });
      f.address.addEventListener('input', () => {
        const q = now();
        moving.hidden = !q || !q.hasKey || f.address.value.trim() === (q.address || '');
      });
      fields.push(h('label', { for: 'f-address' }, 'address'), f.address,
        h('p', { class: 'hint' }, custom ? 'openai-compatible, as http://host:port/v1' : 'left empty, it follows the server\'s machine'),
        moving);
    }
    if (takesKey && !d.keys) {
      fields.push(h('p', { class: 'label' }, 'key'), h('p', { class: 'hint hue' }, KEYS_WIFI));
    } else if (takesKey) {
      f.key = h('input', { class: 'field', id: 'f-key', type: 'password', autocomplete: 'off', autocapitalize: 'off',
        autocorrect: 'off', spellcheck: 'false', placeholder: !adding && p.hasKey ? 'paste another to replace it' : 'paste it here' });
      const show = h('button', { class: 'quiet', type: 'button', 'aria-pressed': 'false', 'aria-controls': 'f-key' }, 'show');
      f.hide = () => {
        f.key.type = 'password';
        show.textContent = 'show';
        show.setAttribute('aria-pressed', 'false');
      };
      show.addEventListener('click', () => {
        const showing = f.key.type === 'password';
        f.key.type = showing ? 'text' : 'password';
        show.textContent = showing ? 'hide' : 'show';
        show.setAttribute('aria-pressed', String(showing));
      });
      fields.push(h('label', { for: 'f-key' }, h('span', null, custom ? 'key, if it needs one' : 'key'), kept),
        h('div', { class: 'keyfield' }, f.key, show));
    }

    /** What changed on the R1 shown again, the fields left as typed. */
    function paint() {
      const q = now();
      if (!q) return;
      if (line) line.textContent = offers(q) + (q.free ? ', free tier' : '');
      kept.textContent = q.hasKey ? (q.key ? 'kept …' + q.key : 'kept') : '';
      if (f.address) f.address.placeholder = q.url || 'the server\'s machine';
      moving.hidden = true;
    }
    paint();

    const canTest = !(adding && p && p.needsKey && !d.keys);
    const test = h('button', { class: 'btn accent', type: 'submit', hidden: !canTest }, 'test');
    const buttons = h('div', { class: 'buttons' }, test,
      adding ? h('button', { class: 'btn', type: 'button', onclick: () => open(null) }, 'cancel') : null);
    const form = h('form', { class: 'form', novalidate: true }, fields, buttons);
    form.addEventListener('submit', ev => {
      ev.preventDefault();
      if (sub.done || !canTest) return;
      box.replaceChildren();
      act(test, 'testing', fail, () => (adding ? testNew(sub, p, f, box) : testKept(p, f, box))).then(paint);
    });

    const nodes = [backToList(), h('h1', null, own ? 'your own server' : nameOf(p)), line, form, box];
    if (!adding && p.id !== 'ollama') {
      const remove = h('button', { class: 'btn wide', type: 'button' }, 'remove');
      twoStep(remove, 'sure? remove ' + nameOf(p), 'removing', fail, async () => {
        S.data = await api('DELETE', 'providers/' + seg(p.id));
        open(null);
      });
      nodes.push(h('div', { class: 'foot' }, remove));
    }
    return nodes;
  }

  /** Asked for its models with what was typed, and kept (its key too) only if that works: a refusal leaves nothing kept. */
  async function testNew(sub, p, f, box) {
    const key = f.key ? f.key.value.trim() : '';
    const url = f.address ? f.address.value.trim() : '';
    let id;
    let body;
    if (sub.kind === 'own') {
      const name = f.name.value.trim();
      id = idFrom(name);
      if (!id) throw new Error('give it a name of letters or digits');
      if (S.data.providers.some(x => x.id === id) || S.data.presets.some(x => x.id === id))
        throw new Error('there is a provider called ' + id + ' already');
      if (!url) throw new Error('give it its address');
      body = { kind: 'openai', url, title: name };
    } else {
      id = p.id;
      body = {};
      if (url) body.url = url;
    }
    if (p && p.needsKey && !key) throw new Error('paste its key first');
    if (key) body.key = key;

    try {
      S.data = await api('POST', 'providers/' + seg(id) + '/try', body);
    } catch (e) {
      if (e instanceof Gone) throw e;
      throw new Said(e.message, 'nothing kept');
    }
    if (f.key) {
      f.key.value = '';
      f.hide();
    }
    sub.done = true;
    const added = S.data.providers.find(x => x.id === id);
    worked(box, 'it works: ' + (added ? offers(added) : 'kept'), 'kept on the r1, the page goes back to providers');
    setTimeout(() => { if (S.sub === sub) open(null); }, 2500);
  }

  /** Its models asked for; a new address or a new key tried first, and kept only if that works. */
  async function testKept(p, f, box) {
    const path = 'providers/' + seg(p.id);
    const body = {};
    if (f.address) {
      const url = f.address.value.trim();
      const q = S.data.providers.find(x => x.id === p.id) || p;
      if (url !== (q.address || '')) body.url = url || null;
    }
    const key = f.key ? f.key.value.trim() : '';
    if (key) body.key = key;
    const trying = Object.keys(body).length > 0;
    try {
      S.data = await api('POST', path + (trying ? '/try' : '/list'), trying ? body : undefined);
    } catch (e) {
      if (e instanceof Gone) throw e;
      throw new Said(e.message, trying ? 'nothing changed' : null);
    }
    if (key) {
      f.key.value = '';
      f.hide();
    }
    const q = S.data.providers.find(x => x.id === p.id);
    worked(box, 'it works: ' + (q ? offers(q) : 'tested'));
  }

  // ---- prompts -----------------------------------------------------------------------

  async function promptsPart() {
    if (!S.prompts) S.prompts = await api('GET', 'prompts');
    const names = S.prompts.prompts.map(x => x.name);
    if (!S.edit || !S.edit.head || !names.includes(S.edit.name)) {
      const name = S.edit && names.includes(S.edit.name) ? S.edit.name : names[0];
      await load(name);
    }
    return promptNodes();
  }

  async function load(name) {
    const p = await api('GET', 'prompts/' + seg(name));
    S.edit = { name: p.name, head: p, saved: p.text, draft: p.text };
  }

  /** The prompt's head in the list, as its last answer has it. */
  function keepHead(p) {
    const i = S.prompts.prompts.findIndex(x => x.name === p.name);
    const head = { name: p.name, seed: p.seed, edited: p.edited, default: p.default, chars: p.chars };
    if (i >= 0) S.prompts.prompts[i] = head;
  }

  /** Which models get [name]: those that picked it; the default, any with no other; a seed, the models it was written for. */
  function whoGets(head) {
    const picked = Object.entries(S.prompts.picks || {}).filter(([, n]) => n === head.name)
      .map(([entry]) => entry.slice(entry.indexOf(':') + 1));
    const them = picked.length ? joined(picked) + (picked.length > 1 ? ' use it' : ' uses it') : '';
    if (head.default) return them ? them + ', and any model with no other' : 'any model with no other prompt gets it';
    if (them) return them;
    return head.seed ? 'the models it was written for get it, unless they pick another' : 'no model uses it yet: pick it on the r1';
  }

  async function savePrompt() {
    const e = S.edit;
    const text = e.draft;
    if (text.length > TEXT_MAX) throw new Error('a prompt is up to ' + count(TEXT_MAX) + ' characters');
    const p = await api('PUT', 'prompts/' + seg(e.name), { text });
    e.head = p;
    e.saved = p.text;
    if (e.draft === text) e.draft = p.text;
    keepHead(p);
  }

  /**
   * The prompts: the one open picked above (and a new one), who gets it, its text in a field
   * as tall as the page allows; save (only once something changed), reset on an edited seed;
   * rename and delete for any but the default.
   */
  function promptNodes() {
    const e = S.edit;
    const status = h('p', { class: 'status', role: 'status' });
    const fail = words => say(status, words, true);

    const area = h('textarea', { class: 'text', id: 'f-text', 'aria-label': 'the text of ' + e.name,
      spellcheck: 'false', autocapitalize: 'sentences', value: e.draft });
    const save = h('button', { class: 'btn accent', type: 'button' }, 'save');
    const reset = h('button', { class: 'btn', type: 'button' }, 'reset');
    const note = h('span', { class: 'note' });

    function sync() {
      const dirty = e.draft !== e.saved;
      const long = e.draft.length > TEXT_MAX;
      save.disabled = !dirty || long;
      reset.hidden = !(e.head.seed && e.head.edited);
      note.textContent = long ? 'too long: ' + count(TEXT_MAX) + ' characters at most' : dirty ? 'not saved' : '';
      note.className = long ? 'note bad' : 'note hue';
    }

    area.addEventListener('input', () => {
      e.draft = area.value;
      sync();
    });
    save.addEventListener('click', () => {
      say(status, '');
      act(save, 'saving', fail, savePrompt).then(sync);
    });
    twoStep(reset, 'sure?', 'resetting', fail, async () => {
      const p = await api('POST', 'prompts/' + seg(e.name) + '/reset');
      e.head = p;
      e.saved = e.draft = area.value = p.text;
      keepHead(p);
      sync();
    });

    // Unsaved words: save, let go or keep editing before anything else opens.
    const guard = h('div', { class: 'guard', hidden: true });
    S.guard = then => {
      const keep = h('button', { class: 'quiet', type: 'button' }, 'keep editing');
      const sv = h('button', { class: 'btn accent', type: 'button' }, 'save');
      const drop = h('button', { class: 'btn', type: 'button' }, 'discard');
      keep.addEventListener('click', () => { guard.hidden = true; area.focus(); });
      sv.addEventListener('click', () => act(sv, 'saving', fail, async () => {
        await savePrompt();
        guard.hidden = true;
        then();
      }).then(sync));
      drop.addEventListener('click', () => {
        e.draft = e.saved;
        guard.hidden = true;
        then();
      });
      guard.replaceChildren(h('p', null, e.name + ' is not saved'), h('div', { class: 'buttons' }, sv, drop, keep));
      guard.hidden = false;
      guard.scrollIntoView({ block: 'nearest' });
    };

    // A new one (a copy of the one open) or a new name, in the same place under the chips.
    const slot = h('div');
    function ask(words, value, button, busy, then) {
      const input = h('input', { class: 'field', id: 'f-name', type: 'text', maxlength: NAME_MAX, autocomplete: 'off',
        autocapitalize: 'off', spellcheck: 'false', value });
      const go = h('button', { class: 'btn accent', type: 'submit' }, button);
      const cancel = h('button', { class: 'btn', type: 'button', onclick: () => slot.replaceChildren() }, 'cancel');
      const form = h('form', { class: 'inline', novalidate: true }, h('label', { for: 'f-name' }, words), input, go, cancel);
      form.addEventListener('submit', ev => {
        ev.preventDefault();
        say(status, '');
        act(go, busy, fail, async () => {
          const name = input.value.trim();
          if (!name) throw new Error('give it a name');
          await then(name);
        });
      });
      slot.replaceChildren(form);
      input.focus();
    }

    const chips = S.prompts.prompts.map(x => {
      const b = h('button', { class: 'chip', type: 'button', 'aria-pressed': String(x.name === e.name) }, x.name);
      b.addEventListener('click', () => {
        if (x.name === e.name) return;
        leave(() => act(b, null, fail, async () => {
          await load(x.name);
          draw();
        }));
      });
      return b;
    });
    const fresh = h('button', { class: 'chip', type: 'button' }, '+ new');
    fresh.addEventListener('click', () => leave(() => ask('a copy of ' + e.name + ', named', '', 'make', 'making', async name => {
      S.prompts = await api('POST', 'prompts', { name, from: e.name });
      await load(name);
      draw();
    })));

    const more = [];
    if (!e.head.default) {
      const rename = h('button', { class: 'quiet', type: 'button' }, 'rename');
      rename.addEventListener('click', () => ask('a new name for ' + e.name, e.name, 'rename', 'renaming', async to => {
        S.prompts = await api('POST', 'prompts/' + seg(e.name) + '/rename', { to });
        e.name = to;
        e.head = Object.assign({}, e.head, S.prompts.prompts.find(x => x.name === to));
        draw();
      }));
      const del = h('button', { class: 'quiet', type: 'button' }, 'delete');
      twoStep(del, 'sure? delete ' + e.name, 'deleting', fail, async () => {
        S.prompts = await api('DELETE', 'prompts/' + seg(e.name));
        S.edit = null;
        draw();
      });
      more.push(h('div', { class: 'more' }, rename, del));
    }

    sync();
    return [
      h('div', { class: 'chips' }, chips, fresh),
      h('p', { class: 'sub who' }, whoGets(e.head)),
      guard,
      slot,
      area,
      h('div', { class: 'buttons' }, save, reset, note),
      more,
      status,
    ];
  }

  // ---- export and import -------------------------------------------------------------

  /** The settings file, saved by the browser as the R1 names it. */
  async function download() {
    const r = await call('GET', 'export');
    const blob = await r.blob();
    const named = /filename="([^"]+)"/.exec(r.headers.get('content-disposition') || '');
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: named ? named[1] : FILE_NAME, hidden: true });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  /** [file] merged into the R1, what it merged said. */
  async function importFile(file, box) {
    if (file.size > BODY_MAX) throw new Error('that file is larger than a settings file can be');
    const text = await file.text();
    const n = await api('POST', 'import', text);
    S.data = await api('GET', 'providers');
    showWay();
    S.prompts = null;
    S.edit = null;
    const parts = [[n.providers, 'provider'], [n.keys, 'key'], [n.prompts, 'prompt'], [n.picks, 'prompt pick'], [n.models, 'model pick']]
      .filter(([k]) => k > 0).map(([k, w]) => k + ' ' + w + (k === 1 ? '' : 's'));
    worked(box, parts.length ? 'merged: ' + parts.join(', ') : 'the file had nothing to merge');
  }

  function exportPart() {
    const keys = S.data.keys;
    const said = h('p', { class: 'status', role: 'status' });
    const box = h('div', { class: 'result', role: 'status' });

    const out = h('button', { class: 'btn accent', type: 'button' }, 'export');
    out.addEventListener('click', () => {
      say(said, '');
      act(out, 'exporting', words => say(said, words, true), download);
    });

    const file = h('input', { class: 'file', type: 'file', accept: '.json,application/json', tabindex: '-1', 'aria-hidden': 'true' });
    const choose = h('button', { class: 'btn choose', type: 'button' }, 'choose a file');
    choose.addEventListener('click', () => file.click());
    file.addEventListener('change', () => {
      const f = file.files && file.files[0];
      if (!f) return;
      box.replaceChildren();
      act(choose, 'merging', (words, then) => failed(box, words, then), () => importFile(f, box))
        .then(() => { file.value = ''; });
    });

    return [
      h('section', { class: 'part' },
        h('h2', null, 'export'),
        h('p', { class: 'big-sub' }, keys
          ? 'one file: the providers and their keys, the prompts and which model gets which'
          : 'one file: the providers, the prompts and which model gets which'),
        h('div', { class: 'beside' }, out,
          h('p', null, keys ? 'it carries the keys: keep the file to yourself' : 'on plain wi-fi it leaves the keys out')),
        said),
      h('section', { class: 'part' },
        h('h2', null, 'import'),
        h('p', { class: 'big-sub' }, 'a file from another r1, merged by name: the imported one wins, nothing is deleted'),
        h('div', { class: 'buttons' }, choose), file, box),
    ];
  }

  // ---- start -------------------------------------------------------------------------

  tabButtons.forEach(b => b.addEventListener('click', () => go(b.dataset.tab)));

  window.addEventListener('beforeunload', ev => {
    const e = S.edit;
    if (!S.closed && e && e.head && e.draft !== e.saved) {
      ev.preventDefault();
      ev.returnValue = '';
    }
  });

  // #prompts/<name>: the R1's edit page opens on that prompt.
  const [first, named] = location.hash.slice(1).split('/');
  S.tab = TABS.includes(first) ? first : 'providers';
  if (S.tab === 'prompts' && named) {
    try {
      S.edit = { name: decodeURIComponent(named) };
    } catch (e) {
      S.edit = null;
    }
  }
  paintTabs();
  draw();
}());
