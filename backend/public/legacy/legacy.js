// ES5 only (Chrome 38). No fetch, URLSearchParams, let/const, arrows or template strings.
(function () {
  'use strict';
  var tvId = (/[?&]tvId=([^&]+)/.exec(location.search) || [])[1] || '';
  var config = {}, revision, tiles = [], focused = null, weatherAt = 0;
  var widgetData = null, widgetsWanted = [], widgetsAt = 0, commandCount = 0, appCount = 0;
  var TITLES = { today: 'Today', upcoming: 'Up next', birthdays: 'Birthdays', f1: 'Formula 1', nfl: 'NFL', countdowns: 'Countdowns', flights: 'Flights', transport: 'Supertram', advent: 'Advent' };
  function $(id) { return document.getElementById(id); }
  function setText(el, value) { if (el.textContent !== String(value)) el.textContent = String(value); }
  function status(message, state) { setText($('status'), message); $('dot').className = 'dot ' + (state || ''); }

  function ajax(method, url, body, done) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    if (body) xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.timeout = 30000;
    xhr.onload = function () {
      var data = null;
      try { data = JSON.parse(xhr.responseText); } catch (e) {}
      if (xhr.status >= 200 && xhr.status < 300 && !(data && data.ok === false)) done(null, data);
      else done((data && data.error) || ('HTTP ' + xhr.status));
    };
    xhr.onerror = xhr.ontimeout = function () { done('Server unavailable'); };
    xhr.send(body ? JSON.stringify(body) : null);
  }

  function sorted(items) {
    var out = [];
    for (var i = 0; i < items.length; i++) if (items[i] && !items[i].hidden) out.push(items[i]);
    return out.sort(function (a, b) { return (a.order === undefined ? 99999 : a.order) - (b.order === undefined ? 99999 : b.order); });
  }
  function icon(item) {
    var wrap = document.createElement('span');
    wrap.className = 'icon';
    var img = document.createElement('img');
    var label = String(item.label || '').toLowerCase();
    if (item.iconImageUrl) img.src = item.iconImageUrl;
    else if (label.indexOf('sky') === 0) img.src = '/tvlauncher/v2/symbols/sky-q.png';
    else { img.src = './symbols/' + (item.id === 'off' ? 'power' : 'tv') + '.svg'; img.className = 'sym'; }
    wrap.appendChild(img);
    return wrap;
  }
  function tile(item, kind, small) {
    var a = document.createElement('a');
    a.className = 'tile' + (small ? ' small' : '');
    a.tabIndex = -1;
    a.appendChild(icon(item));
    var copy = document.createElement('span');
    copy.className = 'copy';
    var label = document.createElement('div');
    label.className = 'label';
    label.textContent = item.label || 'Device';
    var detail = document.createElement('div');
    detail.className = 'detail';
    detail.textContent = item.subtitle || (kind === 'app' ? 'Web app' : '');
    copy.appendChild(label);
    copy.appendChild(detail);
    a.appendChild(copy);
    a._item = item;
    a._kind = kind;
    a.onclick = function () { activate(a); };
    return a;
  }
  function fill(id, items, kind, small) {
    var box = $(id), key = JSON.stringify(items);
    if (box._key === key) return;
    box._key = key;
    while (box.firstChild) box.removeChild(box.firstChild);
    for (var i = 0; i < items.length; i++) box.appendChild(tile(items[i], kind, small));
  }

  function render() {
    setText($('name'), config.name || 'TV');
    setText($('subtitle'), config.subtitle || '');
    var inputs = [], keys = Object.keys(config.inputs || {});
    for (var i = 0; i < keys.length; i++) { var entry = config.inputs[keys[i]]; entry.key = keys[i]; inputs.push(entry); }
    var apps = sorted(config.webApps || []), commands = sorted(config.deviceCommands || []);
    fill('sources', sorted(inputs), 'input');
    fill('apps', apps, 'app', true);
    fill('controls', commands, 'command', true);
    $('apps-section').className = 'section' + (apps.length ? '' : ' off');
    $('controls-section').className = 'section' + (commands.length ? '' : ' off');
    commandCount = commands.length;
    appCount = apps.length;
    widgetsWanted = config.widgets instanceof Array ? config.widgets : ['today', 'f1', 'birthdays', 'upcoming', 'nfl'];
    if (widgetsWanted.length && new Date().getTime() - widgetsAt > 300000) loadWidgets();
    sidePanel();
    tiles = [].slice.call(document.querySelectorAll('.tile'));
    if (!focused || tiles.indexOf(focused) < 0) focusTile(tiles[0]);
  }
  function focusTile(el) {
    if (!el) return;
    if (focused) focused.className = focused.className.replace(' focus', '');
    focused = el;
    el.className += ' focus';
  }

  function activate(el) {
    var item = el._item, label = item.label || 'item';
    if (el._kind === 'app') { location.href = item.url; return; }
    status('Opening ' + label + '...', '');
    function inputStep() {
      if (!item.inputId) return status('Connected', 'online');
      ajax('POST', '/api/tvlauncher/' + encodeURIComponent(tvId) + '/lg/input', { inputId: item.inputId }, function (err) {
        status(err || 'Connected', err ? 'offline' : 'online');
      });
    }
    if (item.url) ajax(item.method || 'GET', item.url, item.body, function (err) { if (err) status(err, 'offline'); else inputStep(); });
    else inputStep();
  }

  // Move focus to the nearest tile in the pressed direction.
  function move(dx, dy) {
    if (!focused) return focusTile(tiles[0]);
    var from = focused.getBoundingClientRect(), best = null, bestScore = 1e9;
    for (var i = 0; i < tiles.length; i++) {
      var el = tiles[i];
      if (el === focused || !el.offsetParent) continue;
      var r = el.getBoundingClientRect();
      var ahead = dx ? (r.left - from.left) * dx : (r.top - from.top) * dy;
      var across = dx ? Math.abs(r.top - from.top) : Math.abs(r.left - from.left);
      if (ahead <= 4) continue;
      var score = ahead + across * 3;
      if (score < bestScore) { best = el; bestScore = score; }
    }
    focusTile(best);
  }
  document.onkeydown = function (event) {
    var code = event.keyCode;
    var map = { 37: [-1, 0], 38: [0, -1], 39: [1, 0], 40: [0, 1] };
    if (map[code]) { move(map[code][0], map[code][1]); event.preventDefault(); }
    else if (code === 13 && focused) { activate(focused); event.preventDefault(); }
  };

  function layoutClass() {
    document.body.className = ($('widgets').firstChild ? 'has-widgets' : '') + (appCount > 0 ? ' has-apps' : '');
  }
  function sidePanel() {
    $('side').className = 'side' + (commandCount > 0 ? '' : ' off');
    layoutClass();
  }
  function renderWidgets() {
    var box = $('widgets');
    while (box.firstChild) box.removeChild(box.firstChild);
    if (!widgetData) return sidePanel();
    for (var w = 0; w < widgetsWanted.length; w++) {
      var id = widgetsWanted[w], items = widgetData[id] || [];
      if (!items.length && id !== 'today') continue;
      var card = document.createElement('div'), h = document.createElement('h3');
      card.className = 'widget';
      h.textContent = TITLES[id] || id;
      card.appendChild(h);
      if (!items.length) {
        var p = document.createElement('p');
        p.textContent = 'Nothing on today';
        card.appendChild(p);
      } else {
        var ul = document.createElement('ul');
        for (var i = 0; i < items.length; i++) {
          var li = document.createElement('li'), t = document.createElement('span'), when = document.createElement('span');
          t.className = 't'; t.textContent = items[i].title;
          when.className = 'when'; when.textContent = items[i].when;
          li.appendChild(t); li.appendChild(when); ul.appendChild(li);
        }
        card.appendChild(ul);
      }
      box.appendChild(card);
    }
    var cards = box.childNodes, n = cards.length;
    for (var c = 0; c < n; c++) {
      cards[c].style.width = 'calc((100% - ' + (n - 1) * 1.5 + 'vw) / ' + n + ')';
      cards[c].style.marginRight = c < n - 1 ? '1.5vw' : '0';
    }
    sidePanel();
  }
  function loadWidgets() {
    widgetsAt = new Date().getTime();
    ajax('GET', '/api/widgets', null, function (err, data) {
      if (!err && data) widgetData = data;
      renderWidgets();
    });
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  var days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  function tick() {
    var d = new Date();
    setText($('clock'), pad(d.getHours()) + ':' + pad(d.getMinutes()));
    setText($('date'), days[d.getDay()] + ' ' + d.getDate() + ' ' + months[d.getMonth()]);
  }
  tick();
  setInterval(tick, 1000);

  function words(code) {
    return code === 0 ? 'Clear' : code <= 2 ? 'Partly cloudy' : code === 3 ? 'Cloudy' : code <= 48 ? 'Fog' : code <= 57 ? 'Drizzle' :
      code <= 67 ? 'Rain' : code <= 77 ? 'Snow' : code <= 82 ? 'Showers' : code <= 86 ? 'Snow showers' : 'Thunderstorm';
  }
  function weather() {
    var w = config.weather;
    if (!w || isNaN(w.latitude) || isNaN(w.longitude)) return;
    ajax('GET', '/api/weather?latitude=' + Number(w.latitude) + '&longitude=' + Number(w.longitude), null, function (err, data) {
      if (!err && data && data.current) setText($('weather'), Math.round(data.current.temperature_2m) + '° · ' + words(data.current.weather_code));
    });
  }

  function load() {
    if (!tvId) { status('Open with ?tvId=...', ''); return; }
    ajax('GET', '/api/tvlauncher/' + encodeURIComponent(tvId), null, function (err, data) {
      if (err) status(err, 'offline');
      else {
        config = data || {};
        var next = String(config.webAppRevision || '');
        if (revision !== undefined && next && next !== revision) { location.reload(); return; }
        revision = next;
        render();
        status('Connected', 'online');
        if (new Date().getTime() - weatherAt > 900000) { weatherAt = new Date().getTime(); weather(); }
      }
      setTimeout(load, Math.max(10, Number(config.settings && config.settings.configPollSeconds) || 20) * 1000);
    });
  }
  load();
})();
