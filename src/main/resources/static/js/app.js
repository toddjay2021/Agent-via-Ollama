'use strict';

/* ============================================================
   DOM refs
   ============================================================ */
const messagesEl = document.getElementById('messages');
const timelineEl = document.getElementById('timeline');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const toastEl = document.getElementById('toast');

let running = false;
let runCount = 0;

/* ============================================================
   Ollama health check
   ============================================================ */
async function checkHealth() {
  const badge = document.getElementById('statusBadge');
  const text = document.getElementById('statusText');
  try {
    const res = await fetch('/api/health');
    const data = await res.json();
    if (data.connected && data.modelAvailable) {
      badge.className = 'status-badge ok';
      text.textContent = `Ollama connected · ${data.model}`;
    } else if (data.connected) {
      badge.className = 'status-badge warn';
      text.textContent = `Ollama up · "${data.model}" not pulled yet`;
    } else {
      badge.className = 'status-badge down';
      text.textContent = 'Ollama unreachable';
    }
  } catch (e) {
    badge.className = 'status-badge down';
    text.textContent = 'Backend unreachable';
  }
}
checkHealth();
setInterval(checkHealth, 30000);

/* ============================================================
   Utilities
   ============================================================ */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

function el(html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstChild;
}

function scrollBottom(node) { node.scrollTop = node.scrollHeight; }

/* Types text into an element, char by char, auto-scrolling its container. */
function typewriter(target, text, ms = 10) {
  return new Promise(resolve => {
    let i = 0;
    const step = () => {
      if (i >= text.length) { resolve(); return; }
      target.textContent += text.slice(i, i + 3);
      i += 3;
      const container = target.closest('.messages, .timeline');
      if (container) scrollBottom(container);
      setTimeout(step, ms);
    };
    step();
  });
}

function toast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => toastEl.classList.add('hidden'), 3500);
}

/* ============================================================
   Chat panel
   ============================================================ */
function removeWelcome() {
  const w = document.getElementById('welcome');
  if (w) w.remove();
}

function addMessage(role, text) {
  removeWelcome();
  const node = el(`
    <div class="msg ${role}">
      <div class="avatar">${role === 'user' ? '🧑‍💻' : '🤖'}</div>
      <div class="bubble"></div>
    </div>`);
  const bubble = node.querySelector('.bubble');
  if (text) bubble.textContent = text;
  messagesEl.appendChild(node);
  scrollBottom(messagesEl);
  return bubble;
}

function addTyping() {
  removeWelcome();
  const node = el(`
    <div class="msg ai">
      <div class="avatar">🤖</div>
      <div class="bubble"><span class="typing-dots"><i></i><i></i><i></i></span></div>
    </div>`);
  messagesEl.appendChild(node);
  scrollBottom(messagesEl);
  return node;
}

/* ============================================================
   Agent reasoning timeline
   ============================================================ */
const STEP_META = {
  thought:     { icon: '🧠', label: 'Thought' },
  action:      { icon: '⚙️', label: 'Action' },
  observation: { icon: '👁️', label: 'Observation' },
  final:       { icon: '✅', label: 'Final Answer' },
  error:       { icon: '❌', label: 'Error' },
};

function removeLogEmpty() {
  const e = document.getElementById('logEmpty');
  if (e) e.remove();
}

function startRun(question) {
  runCount += 1;
  removeLogEmpty();
  const node = el(`
    <div class="run running">
      <div class="run-head">
        <span class="run-badge">RUN ${String(runCount).padStart(2, '0')}</span>
        <span class="run-q" title="${escapeHtml(question)}">${escapeHtml(question)}</span>
        <span class="run-meta">thinking…</span>
      </div>
      <div class="run-steps"></div>
    </div>`);
  timelineEl.appendChild(node);
  scrollBottom(timelineEl);
  return {
    root: node,
    stepsEl: node.querySelector('.run-steps'),
    metaEl: node.querySelector('.run-meta'),
  };
}

async function renderStep(run, type, text, withTypewriter) {
  const meta = STEP_META[type];
  const node = el(`
    <div class="step ${type}">
      <div class="step-icon">${meta.icon}</div>
      <div class="step-body">
        <div class="step-label">${meta.label}</div>
        <div class="step-text"></div>
      </div>
    </div>`);
  run.stepsEl.appendChild(node);
  scrollBottom(timelineEl);
  const textEl = node.querySelector('.step-text');
  if (withTypewriter && text) {
    textEl.classList.add('cursor');
    await typewriter(textEl, text, 9);
    textEl.classList.remove('cursor');
  } else {
    textEl.textContent = text;
  }
}

function finishRun(run, data) {
  run.root.classList.remove('running');
  if (data && typeof data.steps === 'number') {
    run.metaEl.textContent =
      `${data.steps} step${data.steps === 1 ? '' : 's'} · ${(data.durationMs / 1000).toFixed(1)}s`;
  } else {
    run.metaEl.textContent = 'done';
  }
}

/* ============================================================
   Ask & SSE streaming
   ============================================================ */
function ask() {
  const question = inputEl.value.trim();
  if (!question || running) return;
  running = true;
  sendBtn.disabled = true;
  inputEl.value = '';
  resizeInput();

  addMessage('user', question);
  const typing = addTyping();
  const run = startRun(question);

  const es = new EventSource(`/api/agent/stream?q=${encodeURIComponent(question)}`);
  let queue = Promise.resolve();
  const enqueue = fn => { queue = queue.then(fn).catch(err => console.error(err)); };

  let finished = false;
  const settle = data => {
    running = false;
    sendBtn.disabled = false;
    inputEl.focus();
    enqueue(() => {
      typing.remove();
      finishRun(run, data);
    });
  };

  es.addEventListener('thought', e => {
    enqueue(() => renderStep(run, 'thought', JSON.parse(e.data).text, true));
  });

  es.addEventListener('action', e => {
    enqueue(() => renderStep(run, 'action', JSON.parse(e.data).display, false));
  });

  es.addEventListener('observation', e => {
    enqueue(() => renderStep(run, 'observation', JSON.parse(e.data).text, false));
  });

  es.addEventListener('final_answer', e => {
    enqueue(async () => {
      typing.remove();
      const answer = JSON.parse(e.data).text || '';
      const bubble = addMessage('ai', '');
      const chatType = typewriter(bubble, answer, 9);   // chat and log type in parallel
      await renderStep(run, 'final', answer, true);
      await chatType;
    });
  });

  es.addEventListener('agent_error', e => {
    enqueue(async () => {
      const msg = JSON.parse(e.data).message || 'Unknown error';
      typing.remove();
      addMessage('ai', '⚠️ ' + msg);
      await renderStep(run, 'error', msg, false);
    });
  });

  es.addEventListener('done', e => {
    // Mark finished and close synchronously, otherwise the browser fires
    // onerror (stream closed) before the queued UI work runs.
    finished = true;
    es.close();
    settle(e.data ? JSON.parse(e.data) : null);
  });

  es.onerror = () => {
    if (finished) return;
    // Stream dropped without a done event (server down / network error)
    finished = true;
    es.close();
    typing.remove();
    addMessage('ai', '⚠️ Connection lost. Is the backend running?');
    enqueue(() => renderStep(run, 'error', 'SSE connection lost', false));
    finishRun(run, null);
    running = false;
    sendBtn.disabled = false;
  };
}

/* ============================================================
   Input handling
   ============================================================ */
sendBtn.addEventListener('click', ask);

inputEl.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    ask();
  }
});

function resizeInput() {
  inputEl.style.height = 'auto';
  inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
}
inputEl.addEventListener('input', resizeInput);

/* Suggestion chips */
document.querySelectorAll('.chip').forEach(chip => {
  chip.addEventListener('click', () => {
    if (running) return;
    inputEl.value = chip.dataset.q;
    ask();
  });
});

/* Clear buttons */
const WELCOME_HTML = `
  <div class="welcome" id="welcome">
    <div class="welcome-icon">🤖</div>
    <h3>Hi, I'm your local AI agent</h3>
    <p>Ask me anything — I can call <code>calculate</code> and <code>get_current_time</code> tools,
       and you'll watch my <b>Thought → Action → Observation</b> reasoning live in the panel on the right.</p>
  </div>`;

const LOG_EMPTY_HTML = `
  <div class="empty-state" id="logEmpty">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
      <circle cx="12" cy="12" r="3"/>
    </svg>
    <p>The agent's <b>Thought → Action → Observation</b> trace will appear here in real time.</p>
  </div>`;

document.getElementById('clearChat').addEventListener('click', () => {
  messagesEl.querySelectorAll('.msg').forEach(m => m.remove());
  messagesEl.appendChild(el(WELCOME_HTML));
});

document.getElementById('clearLog').addEventListener('click', () => {
  document.querySelectorAll('.run').forEach(r => r.remove());
  timelineEl.appendChild(el(LOG_EMPTY_HTML));
  runCount = 0;
});

/* ============================================================
   Deep link: auto-run a question passed as ?q=...
   ============================================================ */
const deepLinkQuestion = new URLSearchParams(location.search).get('q');
if (deepLinkQuestion) {
  inputEl.value = deepLinkQuestion;
  ask();
}

inputEl.focus();
