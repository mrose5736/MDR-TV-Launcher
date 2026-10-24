const $ = selector => document.querySelector(selector);
const message = $('#message');

function say(text, isError) { message.textContent = text; message.className = isError ? 'error' : ''; }
function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}
async function api(path, method = 'GET', body) {
  const response = await fetch(path, {
    method, headers: body ? {'Content-Type': 'application/json'} : undefined, body: body && JSON.stringify(body)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok || result.ok === false) throw new Error(result.error || response.statusText);
  return result;
}
async function run(button, label, task) {
  button.disabled = true; say(label);
  try { await task(); } catch (error) { say(error.message, true); } finally { button.disabled = false; }
}

function inputRow(key, input, ids) {
  const row = el('div', {className: 'row'});
  row.dataset.key = key;
  const label = el('input', {type: 'text', value: input.label || '', placeholder: 'Label'});
  const inputId = el('input', {type: 'text', value: input.inputId || '', placeholder: 'HDMI_2', size: 10});
  inputId.setAttribute('list', 'ids-' + ids.tvId);
  const order = el('input', {type: 'number', className: 'order', value: input.order ?? 99, title: 'Order'});
  const hidden = el('input', {type: 'checkbox', checked: !!input.hidden});
  const test = el('button', {type: 'button', className: 'quiet', textContent: 'Test'});
  test.onclick = () => run(test, 'Switching…', async () => {
    await api(`/api/tvlauncher/${ids.tvId}/lg/input`, 'POST', {inputId: inputId.value});
    say('Switched to ' + inputId.value);
  });
  const remove = el('button', {type: 'button', className: 'quiet', textContent: '✕', title: 'Remove'});
  remove.onclick = () => row.remove();
  row.append(label, inputId, order, el('label', {className: 'muted'}, hidden, ' hidden'), test, remove);
  row.read = () => ({inputId: inputId.value.trim(), label: label.value.trim(), order: Number(order.value), hidden: hidden.checked});
  return row;
}

function tvCard(tv) {
  const badge = el('span', {className: 'badge checking', textContent: tv.paired ? 'checking…' : 'not paired'});
  const current = el('span', {className: 'muted'});
  const launcherUrl = `${location.origin}/tvlauncher/v2/?tvId=${encodeURIComponent(tv.tvId)}`;
  const name = el('input', {type: 'text', value: tv.name, placeholder: 'Name'});
  const room = el('input', {type: 'text', value: tv.room, placeholder: 'Room'});
  const ids = {tvId: tv.tvId};
  const datalist = el('datalist', {id: 'ids-' + tv.tvId});
  const rows = el('div', {className: 'rows'});
  Object.entries(tv.inputs).sort((a, b) => (a[1].order ?? 99) - (b[1].order ?? 99))
    .forEach(([key, input]) => rows.append(inputRow(key, input, ids)));

  const add = el('button', {type: 'button', className: 'quiet', textContent: '+ Input'});
  add.onclick = () => rows.append(inputRow('new:' + Date.now().toString(36), {}, ids));
  const save = el('button', {type: 'button', textContent: 'Save'});
  save.onclick = () => run(save, 'Saving…', async () => {
    const inputs = {};
    for (const row of rows.children) {
      const value = row.read();
      inputs[row.dataset.key.startsWith('new:') ? value.inputId.toLowerCase() : row.dataset.key] = value;
    }
    await api('/api/admin/tvs/' + tv.tvId, 'PUT', {name: name.value, room: room.value, inputs});
    say('Saved ' + tv.tvId);
  });

  const ip = el('input', {type: 'text', value: tv.paired?.ip || '', placeholder: 'TV IP', size: 14});
  const type = el('select', {}, el('option', {value: 'webos', textContent: 'webOS (2015+)'}), el('option', {value: 'netcast', textContent: 'Netcast (2013-14)'}));
  type.value = tv.paired?.type || 'webos';
  const key = el('input', {type: 'text', placeholder: 'Key on TV', size: 10, hidden: true});
  const pair = el('button', {type: 'button', className: 'quiet', textContent: tv.paired ? 'Re-pair' : 'Pair'});
  pair.onclick = () => run(pair, type.value === 'webos' ? 'Accept the prompt on the TV…' : 'Contacting TV…', async () => {
    const result = await api(`/api/admin/tvs/${tv.tvId}/pair`, 'POST', {ip: ip.value.trim(), type: type.value, key: key.value.trim() || undefined});
    if (result.needsKey) { key.hidden = false; key.focus(); say('Type the key now shown on the TV, then press Pair again'); return; }
    say('Paired ' + tv.tvId); badge.textContent = 'paired'; badge.className = 'badge online';
    key.hidden = true; key.value = ''; check();
  });

  async function check() {
    if (!tv.paired && badge.textContent === 'not paired') return;
    try {
      const {status} = await api(`/api/admin/tvs/${tv.tvId}/status`);
      badge.textContent = status.online ? 'online' : 'offline'; badge.className = 'badge ' + (status.online ? 'online' : 'offline');
      current.textContent = status.online ? 'on ' + status.current : status.error || '';
      datalist.replaceChildren(...(status.inputs || []).map(i => el('option', {value: i.id, label: i.label})));
    } catch (error) { badge.textContent = 'unpaired'; badge.className = 'badge unpaired'; }
  }
  check();

  const copy = el('button', {type: 'button', className: 'quiet', textContent: 'Copy'});
  copy.onclick = () => navigator.clipboard?.writeText(launcherUrl).then(() => say('Launcher URL copied'));
  return el('section', {className: 'tv'},
    el('div', {className: 'tv-head'}, el('h2', {textContent: tv.name}), el('span', {className: 'muted', textContent: tv.tvId}), badge, current),
    el('div', {className: 'row'}, name, room, save),
    rows, datalist, el('div', {className: 'row'}, add),
    el('div', {className: 'row'}, ip, type, key, pair),
    el('div', {className: 'row'}, el('span', {className: 'url', textContent: launcherUrl}), copy));
}

async function load() {
  try {
    const {tvs} = await api('/api/admin/tvs');
    $('#tvs').replaceChildren(...tvs.map(tvCard));
    say(tvs.length ? '' : 'No TVs yet. Add one above.');
  } catch (error) { say(error.message, true); }
}
$('#add-tv').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.target);
  try {
    await api('/api/admin/tvs/' + form.get('tvId'), 'PUT', {name: form.get('name'), inputs: {}});
    event.target.reset(); await load();
  } catch (error) { say(error.message, true); }
};
load();
