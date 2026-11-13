'use strict';

const state = {
  job: null,
  signature: '',
  tab: 'resumen',
  selectedRule: null,
  compareRule: null,
  compareFile: null,
  openCase: null,
  editing: false,
  autoSwitched: new Set(),
  pollTimer: null,
  sample: null,
};

const TABS = [
  { id: 'resumen', label: 'Tablero', ready: j => true },
  { id: 'legacy', label: 'Código legacy', ready: j => !!j.maskedCode },
  { id: 'analisis', label: 'Análisis', ready: j => !!j.analysis },
  { id: 'reglas', label: 'Reglas', ready: j => j.rules.length > 0 },
  { id: 'arquitectura', label: 'Arquitectura', ready: j => !!j.architecture },
  { id: 'comparativa', label: 'Legacy vs. moderno', ready: j => Object.keys(j.generatedFiles).length > 0 },
  { id: 'verificacion', label: 'Verificación', ready: j => !!j.equivalence },
  { id: 'entrega', label: 'Entrega', ready: j => !!j.executiveSummary },
  { id: 'gobierno', label: 'Seguridad y auditoría', ready: j => !!j.maskedCode },
];

const $ = sel => document.querySelector(sel);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const md = text => DOMPurify.sanitize(marked.parse(text || ''));

mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });

// ------------------------------------------------------------------ API
async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    let message = `Error ${response.status}`;
    try { message = (await response.json()).message || message; } catch (_) { /* sin cuerpo */ }
    throw new Error(message);
  }
  const type = response.headers.get('content-type') || '';
  return type.includes('application/json') ? response.json() : response.text();
}

function toast(message, isError = false) {
  const el = $('#toast');
  el.textContent = message;
  el.className = 'toast' + (isError ? ' err' : '');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.add('hidden'), isError ? 6000 : 3000);
}

// --------------------------------------------------------------- inicio
async function init() {
  checkSandbox();
  const samples = await api('/api/samples');
  $('#samples').innerHTML = samples.map(s => `
    <button class="sample" data-id="${esc(s.id)}" data-file="${esc(s.fileName)}" data-domain="${esc(s.domain)}">
      <b>${esc(s.fileName)} <span class="chip">${esc(s.language)}</span></b>
      <small>${esc(s.description)}</small>
    </button>`).join('');
  document.querySelectorAll('.sample').forEach(btn => btn.addEventListener('click', () => loadSample(btn)));
  $('#file-input').addEventListener('change', loadFile);
  $('#start-btn').addEventListener('click', startJob);
  $('#new-job').addEventListener('click', () => { location.hash = ''; location.reload(); });

  const fromHash = location.hash.replace('#', '');
  if (fromHash) {
    openJob(fromHash);
  } else {
    const first = document.querySelector('.sample');
    if (first) loadSample(first);
    loadHistory();
  }
}

async function loadHistory() {
  const jobs = await api('/api/jobs');
  if (!jobs.length) return;
  const statusChip = s => ({
    EN_CURSO: '<span class="chip accent">en curso</span>',
    ESPERANDO_APROBACION: '<span class="chip info">esperando aprobación</span>',
    COMPLETADO: '<span class="chip ok">completada</span>',
    FALLIDO: '<span class="chip err">detenida</span>',
  }[s]);
  $('#history-table').innerHTML = `<tr><th>Fecha</th><th>Artefacto</th><th>Dominio</th><th>Estado</th><th>Reglas</th><th>Equivalencia</th><th></th></tr>`
    + jobs.map(j => `<tr class="clickable" data-open="${esc(j.id)}">
      <td>${new Date(j.createdAt).toLocaleString()}</td><td class="mono">${esc(j.sourceName)}</td><td>${esc(j.domainName)}</td>
      <td>${statusChip(j.status)}</td><td>${j.rules || '—'}</td><td>${esc(j.equivalence || '—')}</td>
      <td>${j.packageReady ? `<a class="btn small" href="/api/jobs/${esc(j.id)}/package" onclick="event.stopPropagation()">Paquete</a>` : ''}</td></tr>`).join('');
  document.querySelectorAll('[data-open]').forEach(row => row.addEventListener('click', () => {
    location.hash = row.dataset.open;
    openJob(row.dataset.open);
  }));
  $('#history').classList.remove('hidden');
}

async function checkSandbox() {
  const chip = $('#sandbox-chip');
  try {
    const status = await api('/api/status');
    const oracle = { LISTO: 'Oracle listo', INICIANDO: 'Oracle iniciando…', DETENIDO: 'Oracle se inicia al usarlo', SIN_IMAGEN: 'sin Oracle' }[status.oracle];
    chip.className = 'chip ' + (!status.sandboxAvailable ? 'err' : status.oracle === 'LISTO' ? 'ok' : 'warn');
    chip.textContent = status.sandboxAvailable ? `● Sandbox GnuCOBOL + JDK listo · ${oracle}` : '● Sandbox no disponible (inicia Docker)';
    if (status.oracle === 'INICIANDO') setTimeout(checkSandbox, 5000);
  } catch (_) {
    chip.className = 'chip err';
    chip.textContent = '● Servidor no disponible';
  }
}

async function loadSample(btn) {
  document.querySelectorAll('.sample').forEach(b => b.classList.toggle('selected', b === btn));
  $('#code-input').value = await api('/api/samples/' + btn.dataset.id);
  $('#source-name').value = btn.dataset.file;
  $('#domain-input').value = btn.dataset.domain;
}

function loadFile(event) {
  const file = event.target.files[0];
  if (!file) return;
  if (file.size > 200000) { toast('El archivo supera los 200 KB admitidos en la demo.', true); return; }
  const reader = new FileReader();
  reader.onload = () => {
    $('#code-input').value = reader.result;
    $('#source-name').value = file.name;
    document.querySelectorAll('.sample').forEach(b => b.classList.remove('selected'));
  };
  reader.readAsText(file);
}

async function startJob() {
  const button = $('#start-btn');
  const error = $('#start-error');
  error.classList.add('hidden');
  button.disabled = true;
  try {
    const job = await api('/api/jobs', {
      method: 'POST',
      body: JSON.stringify({
        sourceName: $('#source-name').value,
        code: $('#code-input').value,
        domainName: $('#domain-input').value,
        targetStack: $('#stack-input').value,
        caseInputs: $('#cases-input').value.trim() ? [$('#cases-input').value] : [],
      }),
    });
    location.hash = job.id;
    openJob(job.id);
  } catch (e) {
    error.textContent = e.message;
    error.classList.remove('hidden');
  } finally {
    button.disabled = false;
  }
}

// ------------------------------------------------------------ job view
function openJob(id) {
  $('#start-view').classList.add('hidden');
  $('#job-view').classList.remove('hidden');
  $('#new-job').classList.remove('hidden');
  state.jobId = id;
  state.layout = undefined;
  poll();
}

async function poll() {
  clearTimeout(state.pollTimer);
  try {
    const job = await api('/api/jobs/' + state.jobId);
    const signature = JSON.stringify([job.status, job.phases, job.events.length, job.rules, job.generatedFiles && Object.keys(job.generatedFiles).length, job.equivalence && job.equivalence.passed, job.testRun, job.packageReady, job.generationAttempts]);
    state.job = job;
    autoSwitch(job);
    if (signature !== state.signature) {
      state.signature = signature;
      render();
    } else {
      renderStepper();
    }
    const active = job.status === 'EN_CURSO';
    state.pollTimer = setTimeout(poll, active ? 1500 : 5000);
  } catch (e) {
    toast(e.message, true);
    if (e.message.includes('No existe')) { location.hash = ''; setTimeout(() => location.reload(), 1500); return; }
    state.pollTimer = setTimeout(poll, 4000);
  }
}

function autoSwitch(job) {
  const once = (key, tab) => {
    if (!state.autoSwitched.has(key)) { state.autoSwitched.add(key); state.tab = tab; }
  };
  if (job.status === 'ESPERANDO_APROBACION') once('approval', 'reglas');
  if (job.status === 'COMPLETADO') once('done', 'resumen');
  if (job.status === 'FALLIDO') once('failed', 'resumen');
}

function render() {
  renderStepper();
  renderHeader();
  renderTabs();
  renderEvents();
  if (!state.editing) renderTab();
}

function renderStepper() {
  const job = state.job;
  $('#stepper').innerHTML = job.phases.map(p => `
    <div class="step ${p.status}">
      <div class="dot">${p.status === 'COMPLETADA' ? '✓' : p.status === 'FALLIDA' ? '✕' : p.number}</div>
      <div>
        <div class="title">${esc(p.title)}</div>
        <div class="meta">${phaseMeta(p)}</div>
        ${p.status === 'EN_CURSO' || p.status === 'ESPERANDO_APROBACION' ? `<div class="meta">${esc(p.detail || '')}</div>` : ''}
        ${p.gateResult ? `<div class="gate">${p.gatePassed ? 'Gate ✓ ' : 'Gate ✕ '}${esc(p.gateResult)}</div>` : `<div class="meta" title="Quality gate">Gate: ${esc(p.qualityGate)}</div>`}
      </div>
    </div>`).join('');
}

function phaseMeta(p) {
  const labels = { PENDIENTE: 'Pendiente', EN_CURSO: 'En curso', ESPERANDO_APROBACION: 'Esperando tu aprobación', COMPLETADA: 'Completada', FALLIDA: 'Detenida' };
  const duration = p.durationMs != null && p.status !== 'ESPERANDO_APROBACION' ? ' · ' + formatDuration(p.durationMs) : '';
  return labels[p.status] + duration;
}

function renderHeader() {
  const job = state.job;
  const statusChip = {
    EN_CURSO: '<span class="chip accent"><span class="spinner"></span> Agentes trabajando</span>',
    ESPERANDO_APROBACION: '<span class="chip info">Esperando aprobación humana</span>',
    COMPLETADO: '<span class="chip ok">Migración completada y verificada</span>',
    FALLIDO: '<span class="chip err">Detenida por un quality gate</span>',
  }[job.status];
  $('#job-header').innerHTML = `
    <div>
      <h2>${esc(job.sourceName)} <span class="muted">→ ${esc(job.targetStack)}</span></h2>
      <div class="sub">${esc(job.domainName)} · migración ${esc(job.id)} · ${formatDuration(job.elapsedMs)} transcurridos</div>
    </div>
    <div>${statusChip} ${job.packageReady ? `<a class="btn primary small" href="/api/jobs/${esc(job.id)}/package">Descargar paquete</a>` : ''}</div>`;
}

function renderTabs() {
  const job = state.job;
  if (!TABS.find(t => t.id === state.tab).ready(job)) state.tab = 'resumen';
  $('#tabs').innerHTML = TABS.map(t => {
    let badge = '';
    if (t.id === 'reglas' && job.status === 'ESPERANDO_APROBACION') {
      const pending = job.rules.filter(r => r.status === 'PROPUESTA').length;
      badge = pending ? `<span class="chip info badge">${pending}</span>` : '<span class="chip ok badge">✓</span>';
    }
    if (t.id === 'verificacion' && job.equivalence) {
      badge = `<span class="chip ${job.equivalence.failed ? 'err' : 'ok'} badge">${job.equivalence.passed}/${job.equivalence.total}</span>`;
    }
    return `<button class="tab ${state.tab === t.id ? 'active' : ''}" data-tab="${t.id}" ${t.ready(job) ? '' : 'disabled'}>${t.label}${badge}</button>`;
  }).join('');
  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => {
    state.tab = b.dataset.tab;
    state.editing = false;
    renderTabs();
    renderTab();
  }));
}

function renderEvents() {
  const events = [...state.job.events].reverse();
  $('#events').innerHTML = events.map(e => `
    <li class="${esc(e.level)}"><time>${new Date(e.at).toLocaleTimeString()}</time>${esc(e.message)}</li>`).join('');
}

function renderTab() {
  const body = $('#tab-body');
  const renderers = { resumen: tabSummary, legacy: tabLegacy, analisis: tabAnalysis, reglas: tabRules, arquitectura: tabArchitecture, comparativa: tabCompare, verificacion: tabVerification, entrega: tabDelivery, gobierno: tabGovernance };
  const scroll = body.scrollTop;
  body.innerHTML = renderers[state.tab]();
  body.scrollTop = scroll;
  afterRender();
}

// --------------------------------------------------------------- tabs
function tabSummary() {
  const j = state.job;
  const accepted = j.rules.filter(r => r.status === 'APROBADA' || r.status === 'AJUSTADA').length;
  const eq = j.equivalence;
  const tr = j.testRun;
  const q = j.quality;
  const automatedMs = j.phases.filter(p => p.phase !== 'VALIDACION' && p.durationMs != null).reduce((a, p) => a + p.durationMs, 0);
  const failedPhase = j.phases.find(p => p.status === 'FALLIDA');

  let html = '';
  if (j.status === 'FALLIDO') {
    html += `<div class="panel" style="border-color: var(--err)"><h3 style="color: var(--err)">El flujo se detuvo en la fase ${failedPhase ? failedPhase.number + ' · ' + esc(failedPhase.title) : ''}</h3>
      <pre class="out">${esc(j.error)}</pre>
      <p class="muted">Acción sugerida: revisa el mensaje, corrige el artefacto o el entorno y lanza una nueva migración. Ningún dato se ha perdido: el registro de actividad y la auditoría siguen disponibles.</p></div>`;
  }
  if (j.status === 'ESPERANDO_APROBACION') {
    html += `<div class="approve-bar"><div><b>Tu turno:</b> revisa las ${j.rules.length} reglas descubiertas antes de generar código.</div><button class="btn primary" data-goto="reglas">Revisar reglas</button></div>`;
  }
  html += `<div class="kpis">
    ${kpi('Reglas de negocio', j.rules.length ? `${accepted || j.rules.length}` : '—', j.rules.length ? `${j.rules.length} descubiertas · ${accepted} aprobadas` : 'pendiente')}
    ${kpi('Equivalencia funcional', eq ? `${eq.passed}/${eq.total}` : '—', eq ? 'casos idénticos al programa original' : 'pendiente', eq ? (eq.failed ? 'err' : 'ok') : '')}
    ${kpi('Pruebas unitarias', tr ? `${tr.succeeded}/${tr.testsFound}` : '—', tr ? 'exitosas (JUnit 5)' : 'pendiente', tr ? (tr.failed ? 'err' : 'ok') : '')}
    ${kpi('Cobertura', tr ? `${tr.lineCoverage}%` : '—', tr ? `líneas · ${tr.branchCoverage}% ramas` : 'umbral 80% / 70%', tr ? (tr.lineCoverage >= 80 && tr.branchCoverage >= 70 ? 'ok' : 'err') : '')}
    ${kpi('Hallazgos críticos', q ? `${q.blockers + q.criticals}` : '—', q ? `complejidad máx. ${q.maxMethodComplexity}` : 'pendiente', q ? (q.blockers + q.criticals ? 'err' : 'ok') : '')}
    ${kpi('Escenarios BDD', j.bdd ? `${j.bdd.passed}/${j.bdd.scenarios}` : '—', j.bdd ? `Gherkin ejecutable · ${j.bdd.coveredRules.length} reglas cubiertas` : 'pendiente', j.bdd ? (j.bdd.failed || !j.bdd.scenarios ? 'err' : 'ok') : '')}
    ${kpi('Vulnerabilidades (SAST)', j.security && j.security.executed ? `${j.security.critical}` : '—', j.security && j.security.executed ? `críticas · ${j.security.major} mayores` : 'pendiente', j.security && j.security.executed ? (j.security.critical ? 'err' : 'ok') : '')}
    ${kpi('Tiempo automatizado', formatDuration(automatedMs), 'sin contar la revisión humana')}
  </div>`;

  if (j.analysis) {
    const manualHours = Math.ceil(j.analysis.codeLines / 12);
    const copilotMinutes = Math.max(1, Math.round(automatedMs / 60000));
    const ratio = Math.min(100, (copilotMinutes / 60) / manualHours * 100);
    html += `<div class="grid-2">
      <div class="panel"><h3>Copiloto vs. proceso manual</h3>
        <div class="compare-bars">
          <div class="row"><span>Análisis manual estimado</span><div class="bar"><span style="width:100%; background: var(--muted)"></span></div><b>${manualHours} h</b></div>
          <div class="row"><span>CodePhoenix</span><div class="bar"><span style="width:${Math.max(1, ratio).toFixed(1)}%"></span></div><b>${copilotMinutes} min</b></div>
        </div>
        <p class="muted" style="margin-top:10px">Supuesto configurable: 12 líneas de código legacy analizadas, migradas y probadas por hora sobre ${j.analysis.codeLines} líneas de código.</p>
      </div>
      <div class="panel"><h3>Programa analizado</h3>
        <table>
          <tr><td>Programa</td><td><b>${esc(j.analysis.programId)}</b> · ${esc(j.language)}</td></tr>
          <tr><td>Líneas</td><td>${j.analysis.totalLines} totales · ${j.analysis.codeLines} de código</td></tr>
          <tr><td>Unidades</td><td>${j.analysis.paragraphs.length} · ${j.analysis.callGraph.length} dependencias · ${j.analysis.goToCount} saltos GO TO</td></tr>
          <tr><td>Código muerto</td><td>${j.analysis.deadParagraphs.map(esc).join(', ') || 'ninguno'}</td></tr>
          <tr><td>Datos sensibles</td><td>${j.maskingFindings.length} enmascarados antes de usar IA</td></tr>
          <tr><td>Uso de IA</td><td>${j.audit.length} llamadas · ${j.totalInputTokens.toLocaleString()} / ${j.totalOutputTokens.toLocaleString()} tokens</td></tr>
        </table>
      </div>
    </div>`;
  }
  if (j.explanation) {
    html += `<div class="panel markdown"><h3>¿Qué hace este programa? <span class="chip">Agente Analista</span></h3>${md(j.explanation)}</div>`;
  } else if (j.status === 'EN_CURSO') {
    html += `<div class="empty"><span class="spinner"></span> Los agentes están analizando el artefacto…</div>`;
  }
  return html;
}

function kpi(label, value, hint, cls = '') {
  return `<div class="kpi ${cls}"><div class="label">${esc(label)}</div><div class="value">${esc(value)}</div><div class="hint">${esc(hint)}</div></div>`;
}

function tabLegacy() {
  const j = state.job;
  return `<div class="panel"><h3>Artefacto normalizado</h3>
      <p class="muted">Esta es la versión que ven los agentes de IA: los datos sensibles aparecen resaltados como marcadores. El original solo se ejecuta dentro del sandbox local.</p></div>
    ${codeView(j.sourceName + ' (enmascarado)', j.maskedCode, { ...legacyOpts() })}`;
}

function plsqlPanel(m) {
  if (!m) return '';
  const j = state.job;
  const signature = `${m.kind} ${m.entryPoint}(${m.params.map(p => `${p.name} ${p.mode} ${p.type}`).join(', ')})${m.kind === 'FUNCTION' ? ' RETURN ' + m.returnType : ''}`;
  return `
    <div class="panel"><h3>Rutina y datos <span class="chip">PL/SQL · Oracle</span></h3>
      <p class="mono">${esc(signature)}</p>
      <p>Lee <b>${m.tablesRead.map(esc).join(', ') || '—'}</b> · modifica <b>${m.tablesWritten.map(esc).join(', ') || '—'}</b>
        ${j.inferredTables.length ? `<span class="chip warn">esquema inferido por IA: ${j.inferredTables.map(esc).join(', ')}</span>` : '<span class="chip ok">DDL incluido en el artefacto</span>'}</p>
      ${m.nonDeterministic.length ? `<p><span class="chip warn">No determinista</span> ${m.nonDeterministic.map(n => `${esc(n.construct)} (línea ${n.line})`).join(', ')}: el Golden Master puede variar entre ejecuciones.</p>` : ''}
      <table><tr><th>Tabla</th><th>Columnas</th></tr>
        ${m.schema.map(t => `<tr><td class="mono">${esc(t.name)}${j.inferredTables.includes(t.name) ? ' <span class="chip warn">inferida</span>' : ''}</td>
          <td class="mono" style="font-size:12px">${t.columns.map(c => `${esc(c.name)} ${esc(c.type)}${c.nullable ? '' : ' NOT NULL'}`).join(' · ')}</td></tr>`).join('')}
      </table>
    </div>`;
}

function tabAnalysis() {
  const a = state.job.analysis;
  const risk = Object.fromEntries(a.riskMap.map(r => [r.paragraph, r]));
  const riskChip = level => `<span class="chip ${level === 'ALTO' ? 'err' : level === 'MEDIO' ? 'warn' : 'ok'}">${esc(level)}</span>`;
  const graph = ['flowchart TD']
    .concat(a.paragraphs.map(p => `  ${nodeId(p.name)}["${p.name}"]${p.reachable ? '' : ':::dead'}`))
    .concat(a.callGraph.map(e => `  ${nodeId(e.from)} -->|${e.kind.startsWith('PERFORM THRU') ? 'PERFORM THRU' : e.kind}| ${nodeId(e.to)}`))
    .concat(['  classDef dead fill:#fdecec,stroke:#c92a2a,color:#c92a2a,stroke-dasharray: 4 3'])
    .join('\n');
  return `${plsqlPanel(a.plsql)}
    <div class="grid-2">
      <div class="panel"><h3>Grafo de llamadas <span class="chip">determinista</span></h3><div class="mermaid">${esc(graph)}</div>
        <p class="muted">En rojo: párrafos que nunca se invocan (código muerto, no se migran).</p></div>
      <div class="panel"><h3>Mapa de riesgo de migración</h3>
        <table><tr><th>Unidad</th><th>Líneas</th><th>Complejidad</th><th>Tipo</th><th>Riesgo</th></tr>
        ${a.paragraphs.map(p => `<tr><td class="mono">${esc(p.name)}</td><td>${p.startLine}-${p.endLine}</td><td>${p.complexity}</td>
          <td>${p.business ? '<span class="chip accent">negocio</span>' : ''} ${p.infrastructure ? '<span class="chip">infraestructura</span>' : ''}</td>
          <td>${riskChip(risk[p.name].level)} <div class="muted" style="font-size:12px">${esc(risk[p.name].reason)}</div></td></tr>`).join('')}
        </table></div>
    </div>
    <div class="panel"><h3>Mapeo de tipos legacy → Java</h3>
      <table><tr><th>Dato</th><th>Línea</th><th>${state.job.language === 'COBOL' ? 'PIC' : 'Tipo'}</th><th>${state.job.language === 'COBOL' ? 'USAGE' : 'Modo'}</th><th>Valor inicial</th><th>Tipo Java exacto</th></tr>
      ${a.dataItems.filter(d => d.picture).map(d => `<tr><td class="mono">${esc(d.name)}</td><td>${d.line}</td><td class="mono">${esc(d.picture)}</td>
        <td>${esc(d.usage || 'DISPLAY')}</td><td class="mono">${esc(d.value || '')}</td><td class="mono">${esc(d.javaType)}</td></tr>`).join('')}
      </table></div>
    <div class="panel"><h3>Operaciones de infraestructura detectadas</h3>
      <p class="muted">Se separan de la lógica de negocio y pasan a los adaptadores de la arquitectura hexagonal.</p>
      <table><tr><th>Operación</th><th>Línea</th><th>Unidad</th></tr>
      ${a.infraOperations.map(o => `<tr><td class="mono">${esc(o.verb)}</td><td>${o.line}</td><td class="mono">${esc(o.paragraph)}</td></tr>`).join('')}</table></div>`;
}

function nodeId(name) { return 'p_' + name.replace(/[^A-Za-z0-9]/g, '_'); }

function tabRules() {
  const j = state.job;
  if (!state.selectedRule || !j.rules.find(r => r.id === state.selectedRule)) state.selectedRule = j.rules[0].id;
  const rule = j.rules.find(r => r.id === state.selectedRule);
  const reviewing = j.status === 'ESPERANDO_APROBACION';
  const pending = j.rules.filter(r => r.status === 'PROPUESTA').length;
  const statusChip = s => ({ PROPUESTA: '<span class="chip">propuesta</span>', APROBADA: '<span class="chip ok">aprobada</span>', AJUSTADA: '<span class="chip info">ajustada</span>', RECHAZADA: '<span class="chip err">rechazada</span>' }[s]);
  const confClass = c => c >= 0.8 ? '' : c >= 0.6 ? 'mid' : 'low';

  let html = '';
  if (reviewing) {
    html += `<div class="approve-bar"><div><b>Validación humana:</b> ${pending ? `${pending} reglas pendientes de revisión.` : 'todas las reglas revisadas.'}
        <div class="muted">${esc(j.rulesSummary || '')}</div></div>
      <div class="actions" style="margin:0">
        ${pending ? '<button class="btn small" id="approve-pending">Aprobar las pendientes con confianza ≥ 80%</button>' : ''}
        <button class="btn primary" id="approve-spec" ${pending ? 'disabled' : ''}>Aprobar especificación y generar código</button>
      </div></div>`;
  } else {
    html += `<div class="panel"><b>Especificación v${j.specVersion || 1}</b> — ${esc(j.rulesSummary || '')}</div>`;
  }
  html += `<div class="rules"><div class="rule-list">
    ${j.rules.map(r => `<button class="rule-item ${r.id === rule.id ? 'selected' : ''}" data-rule="${esc(r.id)}">
      <div class="top"><span class="name">${esc(r.id)} · ${esc(r.name)}</span>${statusChip(r.status)}</div>
      <div class="meta">${esc(r.category)} · líneas ${r.sourceStartLine}-${r.sourceEndLine} · confianza ${Math.round(r.confidence * 100)}%</div>
      <div class="confidence"><span class="${confClass(r.confidence)}" style="width:${Math.round(r.confidence * 100)}%"></span></div>
    </button>`).join('')}
    ${j.decisionTables.length ? `<div class="panel" style="margin-top:8px"><h3>Tablas de decisión</h3>${j.decisionTables.map(decisionTable).join('')}</div>` : ''}
    ${j.infrastructureLogic.length ? `<div class="panel" style="margin-top:8px"><h3>Lógica de infraestructura (no es negocio)</h3><ul>${j.infrastructureLogic.map(i => `<li>${esc(i)}</li>`).join('')}</ul></div>` : ''}
    </div>
    <div class="rule-detail">
      <div class="panel">
        <div class="top" style="display:flex;justify-content:space-between;gap:8px"><h3>${esc(rule.id)} · ${esc(rule.name)}</h3>${statusChip(rule.status)}</div>
        <p>${esc(rule.description)}</p>
        <dl>
          <dt>Condición</dt><dd>${esc(rule.condition)}</dd>
          <dt>Acción</dt><dd>${esc(rule.action)}</dd>
          <dt>Origen</dt><dd class="mono">${esc(j.sourceName)}:${rule.sourceStartLine}-${rule.sourceEndLine} (${esc(rule.sourceParagraph)})</dd>
          <dt>Confianza</dt><dd>${Math.round(rule.confidence * 100)}% ${rule.confidence < 0.8 ? '<span class="chip warn">requiere atención</span>' : ''}</dd>
          ${rule.assumptions.length ? `<dt>Supuestos</dt><dd><ul style="margin:0;padding-left:18px">${rule.assumptions.map(a => `<li>${esc(a)}</li>`).join('')}</ul></dd>` : ''}
          ${rule.reviewerComment ? `<dt>Revisor</dt><dd>${esc(rule.reviewerComment)}</dd>` : ''}
        </dl>
        <div class="gherkin">${esc(rule.gherkin)}</div>
        ${reviewing ? `<div class="actions">
          <button class="btn ok small" data-decision="APROBAR">Aprobar</button>
          <button class="btn small" id="adjust-btn">Ajustar…</button>
          <button class="btn danger small" id="reject-btn">Rechazar…</button>
        </div><div id="edit-area"></div>` : ''}
      </div>
      ${codeView(`${j.sourceName} · líneas ${rule.sourceStartLine}-${rule.sourceEndLine}`, j.maskedCode, { ...legacyOpts(), highlight: [rule.sourceStartLine, rule.sourceEndLine], scroll: true })}
    </div></div>`;
  return html;
}

function tabArchitecture() {
  const a = state.job.architecture;
  return `
    <div class="panel"><h3>Arquitectura destino <span class="chip">Agente Arquitecto</span></h3><p>${esc(a.summary)}</p>
      <p class="muted">Paquete base: <code>${esc(a.basePackage)}</code></p></div>
    <div class="panel"><h3>Hexágono</h3><div class="mermaid">${esc(a.mermaidDiagram || '')}</div></div>
    <div class="panel"><h3>Modelo de dominio y adaptadores</h3>
      <table><tr><th>Elemento</th><th>Tipo</th><th>Capa</th><th>Responsabilidad</th></tr>
      ${a.elements.map(e => `<tr><td class="mono">${esc(e.name)}</td><td><span class="chip">${esc(e.kind)}</span></td><td>${esc(e.layer)}</td><td>${esc(e.responsibility)}</td></tr>`).join('')}
      </table></div>
    <div class="panel"><h3>Registros de decisión (ADR)</h3>
      ${a.adrs.map(d => `<details><summary><b>${esc(d.id)}</b> · ${esc(d.title)}</summary>
        <dl class="rule-detail"><p><b>Contexto.</b> ${esc(d.context)}</p><p><b>Decisión.</b> ${esc(d.decision)}</p><p><b>Consecuencias.</b> ${esc(d.consequences)}</p></dl></details>`).join('')}
    </div>
    <div class="panel"><h3>Contrato OpenAPI (patrón Strangler Fig)</h3><pre class="out">${esc(a.openApiYaml || '')}</pre></div>`;
}

function traceLinks() {
  const links = [];
  Object.entries(state.job.generatedFiles).forEach(([path, content]) => {
    if (!path.startsWith('src/main/java/')) return;
    content.split('\n').forEach((line, i) => {
      const t = line.trim();
      if (!(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'))) return;
      (t.match(/\bR-\d{2,3}\b/g) || []).forEach(id => links.push({ id, path, line: i + 1 }));
    });
  });
  return links;
}

function tabCompare() {
  const j = state.job;
  const files = Object.keys(j.generatedFiles);
  const links = traceLinks();
  const accepted = j.rules.filter(r => r.status !== 'RECHAZADA');
  if (!state.compareRule || !accepted.find(r => r.id === state.compareRule)) state.compareRule = accepted[0] && accepted[0].id;
  const rule = accepted.find(r => r.id === state.compareRule);
  const ruleLinks = links.filter(l => l.id === state.compareRule);
  if (!state.compareFile || !files.includes(state.compareFile)) state.compareFile = ruleLinks[0] ? ruleLinks[0].path : files[0];
  const fileLinks = ruleLinks.filter(l => l.path === state.compareFile);
  const highlight = fileLinks.length ? [fileLinks[0].line, fileLinks[0].line + 8] : null;
  const traced = new Set(links.map(l => l.id));
  return `
    <div class="panel"><b>Trazabilidad:</b> ${accepted.filter(r => traced.has(r.id)).length} de ${accepted.length} reglas aprobadas enlazadas con el código moderno.
      Selecciona una regla para ver dónde vive en cada mundo. · ${j.generationAttempts} intento(s) de generación con autocorrección.
      <div class="file-list" style="margin-top:10px">${accepted.map(r => `<button class="btn small ${r.id === state.compareRule ? 'primary' : ''}" data-compare-rule="${esc(r.id)}">${esc(r.id)} ${traced.has(r.id) ? '' : '⚠'}</button>`).join('')}</div>
    </div>
    <div class="compare">
      <div>
        <p><b>Legacy</b> · ${rule ? esc(rule.name) : ''}</p>
        ${rule ? codeView(`${j.sourceName}:${rule.sourceStartLine}-${rule.sourceEndLine}`, j.maskedCode, { ...legacyOpts(), highlight: [rule.sourceStartLine, rule.sourceEndLine], scroll: true }) : ''}
      </div>
      <div>
        <p><b>Moderno</b> · <select id="file-select" style="width:auto;padding:3px 6px">${files.map(f => `<option value="${esc(f)}" ${f === state.compareFile ? 'selected' : ''}>${esc(f.replace('src/main/java/', '').replace('src/test/java/', 'test/'))}</option>`).join('')}</select>
          ${ruleLinks.length ? ruleLinks.map(l => `<button class="btn small" data-jump="${esc(l.path)}">${esc(l.path.split('/').pop())}:${l.line}</button>`).join(' ') : '<span class="chip warn">sin enlace en el código</span>'}</p>
        ${codeView(state.compareFile.split('/').pop(), j.generatedFiles[state.compareFile], { highlight, scroll: true })}
      </div>
    </div>`;
}

function tabVerification() {
  const j = state.job;
  const eq = j.equivalence;
  const tr = j.testRun;
  const q = j.quality;
  let html = `<div class="kpis">
    ${kpi('Equivalencia vs. programa original', `${eq.passed}/${eq.total}`, `${runtimeName()} · intento ${eq.attempt}`, eq.failed ? 'err' : 'ok')}
    ${tr ? kpi('Pruebas unitarias', `${tr.succeeded}/${tr.testsFound}`, tr.compiled ? 'JUnit 5 en sandbox' : 'no compilan', tr.failed || !tr.compiled ? 'err' : 'ok') : kpi('Pruebas unitarias', '…', 'generando')}
    ${tr ? kpi('Cobertura de líneas', `${tr.lineCoverage}%`, 'umbral ≥ 80%', tr.lineCoverage >= 80 ? 'ok' : 'err') : ''}
    ${tr ? kpi('Cobertura de ramas', `${tr.branchCoverage}%`, 'umbral ≥ 70%', tr.branchCoverage >= 70 ? 'ok' : 'err') : ''}
    ${q ? kpi('Bloqueantes / críticos', `${q.blockers} / ${q.criticals}`, `${q.filesAnalyzed} archivos · ${q.linesOfCode} LOC`, q.blockers + q.criticals ? 'err' : 'ok') : ''}
    ${q ? kpi('Complejidad máx. por método', `${q.maxMethodComplexity}`, 'umbral ≤ 10', q.maxMethodComplexity <= 10 ? 'ok' : 'err') : ''}
  </div>
  <div class="panel"><h3>Golden Master: legacy real vs. moderno</h3>
    <p class="muted">Cada entrada se ejecutó en el programa original (${runtimeName()}) y en el Java generado, ambos en un sandbox sin acceso a Internet. Se compara cada línea campo por campo${state.job.language === 'PL/SQL' ? ', incluido el estado final de las tablas modificadas' : ''}.</p>
    <table><tr><th>Caso</th><th>Tipo</th><th>Descripción</th><th>Entrada</th><th>Líneas</th><th>Resultado</th></tr>
    ${eq.cases.map(c => `<tr class="clickable case-row" data-case="${esc(c.id)}"><td>${esc(c.id)}</td><td><span class="chip">${esc(c.kind)}</span></td><td>${esc(c.description)}</td>
      <td class="mono">${c.input.includes('\n') ? `<div class="out" style="max-height:120px">${esc(c.input)}</div>` : esc(c.input)}<div class="muted" style="font-family:Inter,sans-serif;font-size:11.5px">${esc(decodeRecord(c.input))}</div></td><td>${c.legacyOutput.length}</td><td>${c.passed ? '<span class="chip ok">idéntico</span>' : '<span class="chip err">diferente</span>'}</td></tr>
      ${state.openCase === c.id ? `<tr><td colspan="6">
        ${c.differences.length ? `<div class="diff">${c.differences.map(esc).join('\n')}</div>` : ''}
        <div class="compare"><div><b>Salida del original (${runtimeName()})</b><div class="out">${esc(c.legacyOutput.join('\n'))}</div></div>
        <div><b>Salida Java</b><div class="out">${esc(c.modernOutput.join('\n'))}</div></div></div></td></tr>` : ''}`).join('')}
    </table></div>`;
  if (q) {
    html += `<div class="panel"><h3>Análisis estático del código generado</h3>
      ${q.findings.length ? `<table><tr><th>Severidad</th><th>Regla</th><th>Archivo</th><th>Línea</th><th>Mensaje</th></tr>
      ${q.findings.map(f => `<tr><td><span class="chip ${f.severity === 'MENOR' ? '' : f.severity === 'MAYOR' ? 'warn' : 'err'}">${esc(f.severity)}</span></td><td>${esc(f.rule)}</td><td class="mono">${esc(f.file.split('/').pop())}</td><td>${f.line}</td><td>${esc(f.message)}</td></tr>`).join('')}</table>`
        : '<p>Sin hallazgos: precisión decimal, dependencias hexagonales, secretos, excepciones y complejidad dentro de los umbrales.</p>'}</div>`;
  }
  if (j.bdd) {
    html += `<div class="panel"><h3>Especificación ejecutable (Gherkin + Cucumber)</h3>
      <p>${j.bdd.passed}/${j.bdd.scenarios} escenarios aprobados. Reglas cubiertas por escenarios etiquetados:
        ${j.bdd.coveredRules.map(r => `<span class="chip ok">${esc(r)}</span>`).join(' ')}
        ${j.bdd.uncoveredRules.map(r => `<span class="chip err">${esc(r)}</span>`).join(' ')}</p>
      ${Object.entries(j.generatedFiles).filter(([p]) => p.endsWith('.feature')).map(([p, c]) => `<details><summary class="mono">${esc(p.split('/').pop())}</summary><div class="gherkin">${esc(c)}</div></details>`).join('')}
    </div>`;
  }
  if (j.security) {
    const s = j.security;
    html += `<div class="panel"><h3>Seguridad: SAST <span class="chip">${esc(s.tool)}</span></h3>
      ${s.executed ? `<p>${s.critical} críticos · ${s.major} mayores · ${s.minor} menores. Dependencias de producción: <b>${s.productionDependencies.length ? s.productionDependencies.map(esc).join(', ') : 'ninguna (solo JDK)'}</b></p>
        ${s.findings.length ? `<table><tr><th>Severidad</th><th>Regla</th><th>Archivo</th><th>Línea</th><th>Descripción</th></tr>
        ${s.findings.map(f => `<tr><td><span class="chip ${f.severity === 'MENOR' ? '' : f.severity === 'MAYOR' ? 'warn' : 'err'}">${esc(f.severity)}</span></td><td class="mono" style="font-size:12px">${esc(f.rule)}</td><td class="mono">${esc(f.file.split('/').pop())}</td><td>${f.line}</td><td>${esc(f.message)}</td></tr>`).join('')}</table>` : '<p>Sin hallazgos.</p>'}`
        : `<pre class="out">${esc(s.output)}</pre>`}
    </div>`;
  }
  if (tr) html += `<div class="panel"><details><summary><b>Salida de JUnit</b></summary><pre class="out">${esc(tr.output)}</pre></details></div>`;
  return html;
}

function tabDelivery() {
  const j = state.job;
  return `
    <div class="approve-bar" style="background: var(--ok-soft); border-color: #b9e2c2">
      <div><b>Paquete de migración</b> — código, pruebas, catálogo de reglas, matriz de trazabilidad, ADRs, OpenAPI, reportes de equivalencia, calidad y cobertura, auditoría de IA, Dockerfile e informe ejecutivo.</div>
      ${j.packageReady ? `<a class="btn ok" href="/api/jobs/${esc(j.id)}/package">Descargar .zip</a>` : '<span class="chip"><span class="spinner"></span> armando…</span>'}
    </div>
    <div class="panel"><h3>Ejecutar con un solo comando</h3>
      <pre class="out">${esc(runCommand())}</pre>
      <p class="muted">La construcción de la imagen compila y ejecuta toda la suite de pruebas: si alguna prueba falla, la imagen no se construye.</p></div>
    <div class="panel markdown"><h3>Informe ejecutivo <span class="chip">Agente Documentador</span></h3>${md(j.executiveSummary)}</div>`;
}

function tabGovernance() {
  const j = state.job;
  return `
    <div class="kpis">
      ${kpi('Datos sensibles enmascarados', j.maskingFindings.length, 'antes de cualquier envío a la IA', 'ok')}
      ${kpi('Llamadas a agentes', j.audit.length, 'cada una registrada con hash')}
      ${kpi('Tokens de entrada', j.totalInputTokens.toLocaleString(), 'consumo del modelo')}
      ${kpi('Tokens de salida', j.totalOutputTokens.toLocaleString(), 'consumo del modelo')}
    </div>
    <div class="panel"><h3>Enmascaramiento</h3>
      ${j.maskingFindings.length ? `<table><tr><th>Línea</th><th>Tipo</th><th>Sustituido por</th></tr>
      ${j.maskingFindings.map(f => `<tr><td>${f.line}</td><td>${esc(f.type)}</td><td class="mono">${esc(f.replacement)}</td></tr>`).join('')}</table>` : '<p>No se encontraron datos sensibles.</p>'}
      <p class="muted">El valor original nunca sale del equipo: solo se usa para ejecutar el programa original dentro del sandbox local, sin acceso a Internet.</p></div>
    <div class="panel"><h3>Bitácora de IA</h3>
      <table><tr><th>Hora</th><th>Agente</th><th>Modelo</th><th>Hash del prompt</th><th>Entrada</th><th>Salida</th><th>Duración</th><th>Resultado</th></tr>
      ${j.audit.map(a => `<tr><td>${new Date(a.at).toLocaleTimeString()}</td><td>${esc(a.agent)}</td><td class="mono">${esc(a.model)}</td>
        <td class="mono" title="${esc(a.promptSha256)}">${esc(a.promptSha256.slice(0, 12))}…</td><td>${a.inputTokens.toLocaleString()}</td><td>${a.outputTokens.toLocaleString()}</td>
        <td>${formatDuration(a.durationMs)}</td><td>${a.outcome === 'OK' ? '<span class="chip ok">OK</span>' : `<span class="chip err">${esc(a.outcome)}</span>`}</td></tr>`).join('')}
      </table></div>`;
}

// ------------------------------------------------------------ helpers

function decisionTable(t) {
  const head = t.conditions.map(c => `<th>¿${esc(c)}?</th>`).join('') + t.actions.map(a => `<th>→ ${esc(a)}</th>`).join('');
  const rows = t.rows.map(r => `<tr>${(r.conditions || []).map(v => `<td>${esc(v)}</td>`).join('')}${(r.actions || []).map(v => `<td><b>${esc(v)}</b></td>`).join('')}</tr>`).join('');
  return `<p style="margin:10px 0 4px"><b>${esc(t.name)}</b> ${(t.ruleIds || []).map(r => `<span class="chip">${esc(r)}</span>`).join(' ')}</p>
    <div style="overflow-x:auto"><table><tr>${head}</tr>${rows}</table></div>`;
}

function legacyOpts() {
  return { cobol: state.job.language === 'COBOL', plsql: state.job.language === 'PL/SQL' };
}

function runCommand() {
  const nominal = state.job.testCases.find(c => c.kind === 'NOMINAL') || state.job.testCases[0];
  if (state.job.language === 'PL/SQL') {
    return `docker build -t migracion . && docker run -i --rm migracion < reports/golden-master/${nominal ? nominal.id : 'TC01'}.in`;
  }
  return `docker build -t migracion . && echo "${nominal ? nominal.input : '<registro>'}" | docker run -i --rm migracion`;
}

function runtimeName() {
  return state.job.language === 'PL/SQL' ? 'Oracle Database 23ai Free' : 'GnuCOBOL';
}

/** Campos del registro que lee el ACCEPT, según el análisis estático: [{name, length, decimals}]. */
function recordLayout() {
  const a = state.job.analysis;
  if (state.layout !== undefined) return state.layout;
  state.layout = null;
  const accept = a.infraOperations.find(o => o.verb === 'ACCEPT');
  if (!accept) return null;
  const line = state.job.maskedCode.split('\n')[accept.line - 1] || '';
  const match = line.match(/ACCEPT\s+([A-Z0-9-]+)/i);
  if (!match) return null;
  const start = a.dataItems.findIndex(d => d.name === match[1].toUpperCase());
  if (start < 0) return null;
  const fields = [];
  for (let i = start + 1; i < a.dataItems.length && a.dataItems[i].level > a.dataItems[start].level; i++) {
    const pic = a.dataItems[i].picture;
    if (!pic) continue;
    const expanded = pic.replace(/(.)\((\d+)\)/g, (_, ch, n) => ch.repeat(Number(n))).toUpperCase();
    const [intPart, decPart = ''] = expanded.replace('S', '').split('V');
    fields.push({ name: a.dataItems[i].name, length: intPart.length + decPart.length, decimals: decPart.length });
  }
  state.layout = fields;
  return fields;
}

function decodeRecord(input) {
  const layout = recordLayout();
  if (!layout || !layout.length) return '';
  let pos = 0;
  return layout.map(f => {
    const raw = input.substring(pos, pos + f.length);
    pos += f.length;
    const name = f.name.replace(/^(IN|WS)-/, '').toLowerCase();
    if (!/^\d+$/.test(raw) || raw.length < f.length) return `${name}=«${raw}» (no numérico)`;
    const value = f.decimals ? `${Number(raw.slice(0, raw.length - f.decimals))}.${raw.slice(raw.length - f.decimals)}` : String(Number(raw));
    return `${name}=${value}`;
  }).join(' · ');
}
function codeView(title, code, opts = {}) {
  const lines = (code || '').split('\n');
  const [from, to] = opts.highlight || [0, -1];
  const body = lines.map((text, i) => {
    const n = i + 1;
    let html = esc(text);
    if (opts.cobol && text.length > 6 && (text[6] === '*' || text[6] === '/')) html = `<span class="comment">${html}</span>`;
    if (opts.plsql) {
      const cut = text.indexOf('--');
      if (cut >= 0 && (text.slice(0, cut).split("'").length - 1) % 2 === 0) {
        html = esc(text.slice(0, cut)) + `<span class="comment">${esc(text.slice(cut))}</span>`;
      }
    }
    html = html.replace(/\[(CONTRASENA|USUARIO|EMAIL|CADENA_CONEXION|CLAVE_API|DIRECCION_IP|TARJETA)_\d+\]/g, m => `<span class="masked">${m}</span>`);
    return `<div class="line ${n >= from && n <= to ? 'hl' : ''}" ${n === from && opts.scroll ? 'data-scroll="1"' : ''}><span class="n">${n}</span><span>${html || ' '}</span></div>`;
  }).join('');
  return `<div class="code"><div class="head"><span>${esc(title)}</span><span>${lines.length} líneas</span></div>${body}</div>`;
}

function formatDuration(ms) {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  return `${Math.floor(s / 60)} min ${s % 60} s`;
}

function afterRender() {
  document.querySelectorAll('[data-scroll]').forEach(el => {
    const container = el.closest('.code');
    container.scrollTop = el.offsetTop - 60;
  });
  document.querySelectorAll('.mermaid').forEach(async (el, i) => {
    const source = el.textContent;
    try {
      const { svg } = await mermaid.render(`mmd-${Date.now()}-${i}`, source);
      el.innerHTML = svg;
    } catch (_) {
      el.innerHTML = `<pre class="out">${esc(source)}</pre>`;
    }
  });
  document.querySelectorAll('[data-goto]').forEach(b => b.addEventListener('click', () => { state.tab = b.dataset.goto; renderTabs(); renderTab(); }));
  document.querySelectorAll('[data-rule]').forEach(b => b.addEventListener('click', () => { state.selectedRule = b.dataset.rule; state.editing = false; renderTab(); }));
  document.querySelectorAll('[data-decision]').forEach(b => b.addEventListener('click', () => decide(b.dataset.decision)));
  document.querySelectorAll('[data-compare-rule]').forEach(b => b.addEventListener('click', () => { state.compareRule = b.dataset.compareRule; state.compareFile = null; renderTab(); }));
  document.querySelectorAll('[data-jump]').forEach(b => b.addEventListener('click', () => { state.compareFile = b.dataset.jump; renderTab(); }));
  document.querySelectorAll('[data-case]').forEach(r => r.addEventListener('click', () => { state.openCase = state.openCase === r.dataset.case ? null : r.dataset.case; renderTab(); }));
  const fileSelect = $('#file-select');
  if (fileSelect) fileSelect.addEventListener('change', () => { state.compareFile = fileSelect.value; renderTab(); });
  const adjust = $('#adjust-btn');
  if (adjust) adjust.addEventListener('click', () => openEditor('AJUSTAR'));
  const reject = $('#reject-btn');
  if (reject) reject.addEventListener('click', () => openEditor('RECHAZAR'));
  const approveSpec = $('#approve-spec');
  if (approveSpec) approveSpec.addEventListener('click', approveSpecification);
  const approvePending = $('#approve-pending');
  if (approvePending) approvePending.addEventListener('click', approveHighConfidence);
}

function openEditor(decision) {
  const rule = state.job.rules.find(r => r.id === state.selectedRule);
  state.editing = true;
  $('#edit-area').innerHTML = decision === 'AJUSTAR' ? `
    <div class="edit-form">
      <label class="muted">Descripción</label><textarea id="ed-description">${esc(rule.description)}</textarea>
      <label class="muted">Condición</label><textarea id="ed-condition">${esc(rule.condition)}</textarea>
      <label class="muted">Acción</label><textarea id="ed-action">${esc(rule.action)}</textarea>
      <label class="muted">Comentario para la auditoría</label><textarea id="ed-comment" placeholder="Motivo del ajuste"></textarea>
      <div class="actions"><button class="btn primary small" id="ed-save">Guardar ajuste</button><button class="btn small" id="ed-cancel">Cancelar</button></div>
    </div>` : `
    <div class="edit-form">
      <label class="muted">Motivo del rechazo (obligatorio)</label><textarea id="ed-comment"></textarea>
      <div class="actions"><button class="btn danger small" id="ed-save">Rechazar regla</button><button class="btn small" id="ed-cancel">Cancelar</button></div>
    </div>`;
  $('#ed-cancel').addEventListener('click', () => { state.editing = false; renderTab(); });
  $('#ed-save').addEventListener('click', () => decide(decision, {
    description: $('#ed-description') && $('#ed-description').value,
    condition: $('#ed-condition') && $('#ed-condition').value,
    action: $('#ed-action') && $('#ed-action').value,
    comment: $('#ed-comment').value,
  }));
}

async function decide(decision, fields = {}) {
  try {
    await api(`/api/jobs/${state.job.id}/rules/${state.selectedRule}`, { method: 'POST', body: JSON.stringify({ decision, ...fields }) });
    state.editing = false;
    const next = state.job.rules.find(r => r.status === 'PROPUESTA' && r.id !== state.selectedRule);
    if (next) state.selectedRule = next.id;
    poll();
  } catch (e) {
    toast(e.message, true);
  }
}

async function approveHighConfidence() {
  const pending = state.job.rules.filter(r => r.status === 'PROPUESTA' && r.confidence >= 0.8);
  if (!pending.length) { toast('No hay reglas pendientes con confianza ≥ 80%. Revísalas una a una.'); return; }
  for (const rule of pending) {
    await api(`/api/jobs/${state.job.id}/rules/${rule.id}`, { method: 'POST', body: JSON.stringify({ decision: 'APROBAR', comment: 'Aprobada en lote (confianza ≥ 80%)' }) });
  }
  const remaining = state.job.rules.filter(r => r.status === 'PROPUESTA' && r.confidence < 0.8);
  if (remaining.length) { state.selectedRule = remaining[0].id; toast(`${remaining.length} reglas con menor confianza requieren revisión individual.`); }
  poll();
}

async function approveSpecification() {
  try {
    await api(`/api/jobs/${state.job.id}/approve`, { method: 'POST' });
    toast('Especificación aprobada: los agentes generan el código.');
    state.tab = 'resumen';
    poll();
  } catch (e) {
    toast(e.message, true);
  }
}

init();
