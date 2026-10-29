// OrcaMobile local server UI. No framework, no build step: it ships inside the APK.
const $ = (id) => document.getElementById(id);

let token = new URLSearchParams(location.search).get('t') || localStorage.getItem('orcam.token') || '';
if (token) localStorage.setItem('orcam.token', token);

let polling = null;
let lastState = 'idle';

function toast(message, kind) {
    const el = $('toast');
    el.textContent = message;
    el.className = 'toast ' + (kind || '');
    el.classList.remove('hidden');
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.add('hidden'), 4000);
}

function authHeaders(extra) {
    return Object.assign({'X-Orca-Token': token}, extra || {});
}

async function api(path, options) {
    const opts = Object.assign({headers: authHeaders()}, options || {});
    const response = await fetch(path, opts);
    if (response.status === 401) {
        token = prompt('Token del servidor (está en Ajustes → Servidor local)') || '';
        localStorage.setItem('orcam.token', token);
        throw new Error('token');
    }
    const text = await response.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (e) { data = {raw: text}; }
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    return data;
}

function setConn(ok) {
    const el = $('conn');
    el.textContent = ok ? 'conectado' : 'sin conexión';
    el.className = 'pill ' + (ok ? 'on' : 'off');
}

async function refresh() {
    try {
        const status = await api('/api/status');
        setConn(true);
        const plate = status.plate || {};
        renderParts(plate.parts || []);
        fillSelect('printer', plate.printers || [], plate.printer);
        fillSelect('filament', plate.filaments || [], plate.filament);
        if (plate.options) applyOptionsToUi(plate.options);
        $('plate-ip').textContent = plate.bed ? 'Cama ' + plate.bed : '';
        renderJob(status.job || {});
        refreshPreview(plate.parts && plate.parts.length);
        $('slice').disabled = !plate.parts || plate.parts.length === 0;
    } catch (e) {
        setConn(false);
    }
}

function fillSelect(id, values, current) {
    const select = $(id);
    if (!values.length) return;
    const signature = values.join('|');
    if (select.dataset.signature !== signature) {
        select.innerHTML = '';
        values.forEach((value) => {
            const option = document.createElement('option');
            option.value = value;
            option.textContent = value;
            select.appendChild(option);
        });
        select.dataset.signature = signature;
    }
    if (current && values.includes(current)) select.value = current;
}

function renderParts(parts) {
    const list = $('parts');
    list.innerHTML = '';
    parts.forEach((part) => {
        const li = document.createElement('li');
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = part.name;
        const size = document.createElement('span');
        size.className = 'muted';
        size.textContent = part.sizeMm || '';
        const del = document.createElement('button');
        del.className = 'del';
        del.textContent = '✕';
        del.title = 'Quitar de la cama';
        del.onclick = async () => {
            try {
                await api('/api/files/' + part.index, {method: 'DELETE'});
                await refresh();
            } catch (e) { toast('No se pudo quitar: ' + e.message, 'bad'); }
        };
        li.append(name, size, del);
        list.appendChild(li);
    });
    $('parts-count').textContent = parts.length ? parts.length + ' pieza(s)' : 'Sin piezas';
}

function refreshPreview(hasParts) {
    const img = $('preview');
    if (!hasParts) {
        img.classList.remove('ready');
        $('preview-empty').classList.remove('hidden');
        return;
    }
    img.onload = () => {
        img.classList.add('ready');
        $('preview-empty').classList.add('hidden');
    };
    img.onerror = () => {
        img.classList.remove('ready');
        $('preview-empty').classList.remove('hidden');
    };
    img.src = '/api/preview.png?w=720&t=' + encodeURIComponent(token) + '&_=' + Date.now();
}

function uploadFiles(files) {
    if (!files || !files.length) return;
    let index = 0;
    const bar = $('upload-bar');
    const fill = $('upload-fill');
    const text = $('upload-text');
    bar.classList.remove('hidden');

    const next = () => {
        if (index >= files.length) {
            bar.classList.add('hidden');
            fill.style.width = '0%';
            refresh();
            return;
        }
        const file = files[index++];
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', '/api/files/' + encodeURIComponent(file.name));
        xhr.setRequestHeader('X-Orca-Token', token);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.upload.onprogress = (event) => {
            if (!event.lengthComputable) return;
            const percent = Math.round(event.loaded / event.total * 100);
            fill.style.width = percent + '%';
            text.textContent = file.name + ' · ' + percent + '%';
        };
        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                text.textContent = file.name + ' · subido';
                next();
            } else {
                bar.classList.add('hidden');
                toast('Error subiendo ' + file.name + ' (' + xhr.status + ')', 'bad');
            }
        };
        xhr.onerror = () => {
            bar.classList.add('hidden');
            toast('Fallo de red subiendo ' + file.name, 'bad');
        };
        xhr.send(file);
    };
    next();
}

function applyOptionsToUi(options) {
    if (options.printer) $('printer').value = options.printer;
    if (options.filament) $('filament').value = options.filament;
    if (options.supports) $('supports').value = options.supports;
    if (options.brim) $('brim').value = options.brim;
    if (options.skirt) $('skirt').value = options.skirt;
    if (options.quality) $('quality').value = options.quality;
    if (options.infill) $('infill').value = options.infill;
}

async function saveOptions() {
    const body = {
        printer: $('printer').value,
        filament: $('filament').value,
        supports: $('supports').value,
        brim: $('brim').value,
        skirt: $('skirt').value,
        quality: $('quality').value,
        infill: $('infill').value
    };
    try {
        await api('/api/options', {
            method: 'POST',
            headers: authHeaders({'Content-Type': 'application/json'}),
            body: JSON.stringify(body)
        });
        $('apply-state').textContent = 'guardado';
        setTimeout(() => $('apply-state').textContent = '', 2500);
    } catch (e) {
        toast('No se pudo guardar: ' + e.message, 'bad');
    }
}

function formatDuration(seconds) {
    if (!seconds) return '—';
    const h = Math.floor(seconds / 3600);
    const m = Math.round((seconds % 3600) / 60);
    return h ? h + ' h ' + m + ' min' : m + ' min';
}

function renderJob(job) {
    const state = job.state || 'idle';
    const bar = $('job-bar');
    const fill = $('job-fill');
    if (state === 'slicing') {
        bar.classList.remove('hidden');
        fill.style.width = (job.progress || 0) + '%';
        $('job-state').textContent = (job.progress || 0) + '% · ' + (job.message || 'laminando');
        $('slice').disabled = true;
    } else {
        bar.classList.add('hidden');
        $('slice').disabled = false;
        $('job-state').textContent = state === 'done' ? 'listo'
            : state === 'error' ? ('error: ' + (job.error || '')) : '';
    }
    const result = $('result');
    if (state === 'done' && job.stats) {
        result.classList.remove('hidden');
        $('stat-time').textContent = formatDuration(job.stats.seconds);
        $('stat-weight').textContent = (job.stats.grams || 0).toFixed(1) + ' g';
        $('stat-layers').textContent = job.stats.layers || '—';
        $('stat-speed').textContent = Math.round(job.stats.speed || 0) + ' mm/s';
        $('stat-hash').textContent = job.stats.sha256 ? 'sha256 ' + job.stats.sha256 : '';
        const q = '?t=' + encodeURIComponent(token);
        $('dl-gcode').href = '/api/gcode' + q;
        $('dl-package').href = '/api/package' + q;
    } else {
        result.classList.add('hidden');
    }
    lastState = state;
}

async function startSlice() {
    try {
        await saveOptions();
        const job = await api('/api/slice', {method: 'POST'});
        renderJob(job);
        if (polling) clearInterval(polling);
        polling = setInterval(async () => {
            try {
                const status = await api('/api/status');
                renderJob(status.job || {});
                if (status.job && (status.job.state === 'done' || status.job.state === 'error')) {
                    clearInterval(polling);
                    polling = null;
                    refreshPreview(true);
                    if (status.job.state === 'error') {
                        toast('Laminado fallido: ' + (status.job.error || ''), 'bad');
                    } else {
                        toast('Laminado listo', 'ok');
                    }
                }
            } catch (e) {
                clearInterval(polling);
                polling = null;
            }
        }, 700);
    } catch (e) {
        if (e.message !== 'token') toast('No se pudo laminar: ' + e.message, 'bad');
        if (e.message === 'busy') toast('Ya hay un laminado en curso', 'bad');
    }
}

async function send(print) {
    try {
        const format = $('dl-package').href.indexOf('http') === 0 || true ? '3mf' : 'gcode';
        $('job-state').textContent = 'enviando…';
        const result = await api('/api/send?print=' + (print ? 1 : 0) + '&format=' + format, {method: 'POST'});
        toast(result.message || 'Enviado', 'ok');
        $('job-state').textContent = '';
    } catch (e) {
        toast('Envío fallido: ' + e.message, 'bad');
    }
}

// wiring
const drop = $('drop');
['dragenter', 'dragover'].forEach((event) => drop.addEventListener(event, (e) => {
    e.preventDefault();
    drop.classList.add('over');
}));
['dragleave', 'drop'].forEach((event) => drop.addEventListener(event, (e) => {
    e.preventDefault();
    drop.classList.remove('over');
}));
drop.addEventListener('drop', (e) => uploadFiles(e.dataTransfer.files));
$('picker').addEventListener('change', (e) => uploadFiles(e.target.files));
$('arrange').addEventListener('click', async () => {
    try {
        await api('/api/arrange', {method: 'POST'});
        refresh();
    } catch (e) { toast('No se pudo organizar: ' + e.message, 'bad'); }
});
$('apply').addEventListener('click', saveOptions);
$('slice').addEventListener('click', startSlice);
$('send').addEventListener('click', () => send(false));
$('send-print').addEventListener('click', () => send(true));

refresh();
setInterval(refresh, 5000);
