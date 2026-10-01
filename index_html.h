#ifndef INDEX_HTML_H
#define INDEX_HTML_H

const char INDEX_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Esp Vault</title>
<!-- No external assets: this page runs on the device's own AP, usually with no internet. -->
<style>
:root { --bg: #0f172a; --card: #1e293b; --primary: #38bdf8; --text: #f8fafc; --text-dim: #94a3b8; --accent: #22c55e; --danger: #ef4444; --warn: #f59e0b; }
body { font-family: 'Segoe UI', sans-serif; background: var(--bg); color: var(--text); margin: 0; padding-bottom: 70px; }
.mi { font-style: normal; display: inline-block; }

/* Navigation */
.nav-bar { display: flex; position: fixed; bottom: 0; width: 100%; background: var(--card); border-top: 1px solid rgba(255,255,255,0.1); z-index: 1000; }
.nav-item { flex: 1; padding: 12px; text-align: center; color: var(--text-dim); cursor: pointer; display: flex; flex-direction: column; align-items: center; font-size: 11px; }
.nav-item.active { color: var(--primary); background: rgba(56, 189, 248, 0.05); }
.nav-item .mi { font-size: 24px; margin-bottom: 4px; }

/* Layout */
.container { padding: 20px; max-width: 500px; margin: 0 auto; }
.tab-content { display: none; animation: fadeIn 0.3s ease; }
.tab-content.active { display: block; }

/* Cards */
.card { background: var(--card); border-radius: 12px; padding: 16px; margin-bottom: 15px; border: 1px solid rgba(255,255,255,0.05); }
.status-pill { display: inline-block; padding: 4px 12px; border-radius: 20px; font-size: 11px; font-weight: bold; background: rgba(34,197,94,0.1); color: var(--accent); margin-bottom: 10px; }
h2 { font-size: 1rem; margin-top: 0; color: var(--primary); text-transform: uppercase; letter-spacing: 1px; display: flex; align-items: center; gap: 8px; }
.stat-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 14px; }

/* File Browser */
.file-list { margin-top: 10px; }
.file-item { display: flex; justify-content: space-between; align-items: center; padding: 10px; background: rgba(0,0,0,0.2); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.file-info { display: flex; flex-direction: column; }
.file-name { color: var(--text); font-weight: 500; overflow: hidden; text-overflow: ellipsis; max-width: 190px; }
.file-meta { color: var(--text-dim); font-size: 11px; }
.file-ops { display: flex; gap: 12px; }
.file-ops .mi { font-size: 20px; cursor: pointer; color: var(--text-dim); transition: color 0.2s; }
.file-ops .mi:hover { color: var(--primary); }
.file-ops .del:hover { color: var(--danger); }

/* Vault list */
.vault-item { display: flex; justify-content: space-between; align-items: center; }
.vault-item .actions { display: flex; gap: 8px; }
.vault-item .actions .mi { font-size: 20px; cursor: pointer; color: var(--text-dim); transition: color 0.2s; }
.vault-item .actions .mi:hover { color: var(--primary); }
.vault-item .actions .del:hover { color: var(--danger); }
.inline-edit { display: flex; gap: 8px; margin-top: 8px; }
.inline-edit input { flex: 1; margin-bottom: 0; }
.inline-edit button { width: auto; padding: 8px 12px; }

/* Buttons & Inputs */
input[type="text"], input[type="password"] { width: 100%; background: #0f172a; border: 1px solid #334155; color: white; padding: 12px; border-radius: 8px; box-sizing: border-box; margin-bottom: 12px; font-size: 14px; }
select { width: 100%; background: #0f172a; border: 1px solid #334155; color: white; padding: 12px; border-radius: 8px; margin-bottom: 8px; font-size: 14px; }
.btn { width: 100%; padding: 12px; border-radius: 8px; border: none; font-weight: bold; cursor: pointer; transition: 0.2s; font-size: 13px; }
.btn-primary { background: var(--primary); color: var(--bg); }
.btn-danger  { background: var(--danger); color: #fff; }
.btn-outline { background: transparent; border: 1px solid var(--primary); color: var(--primary); margin-top: 10px; display: flex; align-items: center; justify-content: center; gap: 8px; }
.btn-sm { width: auto; padding: 6px 14px; font-size: 12px; border-radius: 6px; }

/* Modal */
.modal-bg { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.7); z-index: 2000; align-items: center; justify-content: center; }
.modal-bg.open { display: flex; }
.modal { background: var(--card); border-radius: 12px; width: 92%; max-width: 480px; max-height: 85vh; display: flex; flex-direction: column; border: 1px solid rgba(255,255,255,0.08); }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid rgba(255,255,255,0.07); }
.modal-header h3 { margin: 0; font-size: 14px; color: var(--primary); letter-spacing: 1px; text-transform: uppercase; }
.modal-header .mi { cursor: pointer; color: var(--text-dim); }
.modal-header .mi:hover { color: var(--text); }
.modal-body { padding: 16px; flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
textarea#fileContent { flex: 1; min-height: 240px; background: #0f172a; border: 1px solid #334155; color: var(--text); padding: 12px; border-radius: 8px; font-family: 'Courier New', monospace; font-size: 13px; resize: vertical; width: 100%; box-sizing: border-box; }
.modal-footer { padding: 12px 16px; border-top: 1px solid rgba(255,255,255,0.07); display: flex; gap: 8px; }
.modal-footer .btn { margin: 0; }

/* Login */
.login-bg { display: none; position: fixed; inset: 0; background: var(--bg); z-index: 3000; align-items: center; justify-content: center; }
.login-bg.open { display: flex; }
.login-card { background: var(--card); border-radius: 12px; padding: 28px 24px; width: 88%; max-width: 340px; text-align: center; border: 1px solid rgba(255,255,255,0.08); }
.login-err { color: var(--danger); font-size: 13px; min-height: 18px; margin-bottom: 8px; }
.logout-link { color: var(--text-dim); font-size: 12px; cursor: pointer; text-decoration: underline; }

/* New file row */
.new-file-row { display: flex; gap: 8px; margin-top: 10px; }
.new-file-row input { flex: 1; margin-bottom: 0; }
.new-file-row button { width: auto; padding: 12px 16px; }
/* Visually-hidden but still rendered file input: keeps <label for> working
   in every browser (unlike display:none, which Firefox ignores). */
.vh-input { position: absolute !important; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }

@keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
</style>
</head><body>

<!-- ── LOGIN OVERLAY ──────────────────────────────────────── -->
<div class="login-bg" id="loginModal">
  <div class="login-card">
    <div style="font-size:40px;margin-bottom:10px;"><i class="mi">&#x1F511;</i></div>
    <h2 style="justify-content:center;">Esp Vault</h2>
    <p style="color:var(--text-dim);font-size:13px;">Enter the portal WiFi password to manage this device.</p>
    <input type="password" id="loginPass" placeholder="Portal password" autocomplete="off">
    <div class="login-err" id="loginErr"></div>
    <button class="btn btn-primary" onclick="doLogin()">UNLOCK</button>
  </div>
</div>

<!-- ── FORCED PASSWORD CHANGE (after first login with default) ── -->
<div class="login-bg" id="forceChangeModal">
  <div class="login-card">
    <div style="font-size:40px;margin-bottom:10px;"><i class="mi">&#x1F6E1;</i></div>
    <h2 style="justify-content:center;">Change Password</h2>
    <p style="color:var(--text-dim);font-size:13px;">You're using the default portal password (<code>espvault</code>). Pick a new one to continue.</p>
    <input type="password" id="newPortalPass" placeholder="New password (min 8 chars)" autocomplete="new-password">
    <input type="password" id="newPortalPass2" placeholder="Confirm new password" autocomplete="new-password" style="margin-top:8px;">
    <div class="login-err" id="forceChangeErr"></div>
    <button class="btn btn-primary" onclick="doForceChange()">SAVE &amp; CONTINUE</button>
  </div>
</div>

<div class="container">

  <!-- ── HOME TAB ─────────────────────────────────────────── -->
  <div id="home" class="tab-content active">
    <!-- Device Name Card -->
    <div class="card">
      <div class="status-pill">&#x25CF; SYSTEM SECURE</div>
      <h2><i class="mi">&#x25A6;</i>Device Status</h2>

      <div style="text-align:center; margin:15px 0;">
        <span id="deviceNameDisplay" style="font-size:28px; font-weight:bold; color:var(--primary); letter-spacing:1px;">Loading...</span>
      </div>

      <!-- Status rows -->
      <div class="stat-row"><span>Current Time</span><span id="currentTime">&mdash;</span></div>
      <div class="stat-row"><span>Time Status</span><span id="timeStatus">&mdash;</span></div>
      <div class="stat-row"><span>NTP Server</span><span id="ntpServer">&mdash;</span></div>
      <div class="stat-row"><span>Timezone</span><span id="tzName">&mdash;</span></div>
      <div class="stat-row"><span>Storage Usage</span><span id="storageStatus">&mdash;</span></div>
      <div class="stat-row"><span>Keys In Vault</span><span id="keyCountHome">&mdash;</span></div>
      <div class="stat-row"><span>Stored Files</span><span id="fileCountHome">&mdash;</span></div>
      <div style="text-align:center;margin-top:12px;">
        <span class="logout-link" onclick="doLogout()">Log out</span>
      </div>
    </div>

    <!-- Collapsible Auth Codes Card -->
    <div class="card" id="authCodesCard">
      <h2 style="cursor:pointer;" onclick="toggleAuthCodes()">
        <i class="mi">&#x1F522;</i>
        Active TOTP Codes
        <span id="authToggleIcon" style="float:right;transition:transform 0.2s;">&#x25BC;</span>
      </h2>
      <div id="authCodesList" style="display:none; animation:fadeIn 0.3s ease;">
        <p style="color:var(--text-dim);text-align:center;">Loading...</p>
      </div>
    </div>

    <!-- Collapsible System Settings Card -->
    <div class="card">
      <h2 style="cursor:pointer;" onclick="toggleSystemSettings()">
        <i class="mi">&#x2699;</i>
        System Settings
        <span id="systemToggleIcon" style="float:right;transition:transform 0.2s;">&#x25BC;</span>
      </h2>
      <div id="systemSettingsList" style="display:none; animation:fadeIn 0.3s ease;">

        <!-- Device Name Section -->
        <div style="margin-bottom:20px;">
          <h3 style="color:var(--primary);font-size:14px;margin:0 0 8px 0;">Device Name</h3>
          <div class="inline-edit">
            <input type="text" id="deviceNameInput" placeholder="Enter device name" style="font-size:14px;">
            <button class="btn btn-primary btn-sm" onclick="saveDeviceName()">SET</button>
          </div>
        </div>

        <!-- WiFi Settings Section -->
        <div style="margin-bottom:20px;">
          <h3 style="color:var(--primary);font-size:14px;margin:0 0 8px 0;">WiFi Credentials</h3>
          <input type="text" id="wifiSSIDInput" placeholder="WiFi Network Name" style="margin-bottom:8px;">
          <input type="password" id="wifiPasswordInput" placeholder="WiFi Password" style="margin-bottom:8px;">
          <button class="btn btn-primary btn-sm" onclick="saveWiFiSettings()" style="width:100%;">SAVE WIFI</button>
          <div style="font-size:11px;color:var(--text-dim);margin-top:6px;">
            Current: <span id="currentWifiSSID">&mdash;</span>
          </div>
        </div>

        <!-- Portal WiFi Settings Section -->
        <div style="margin-bottom:20px;">
          <h3 style="color:var(--primary);font-size:14px;margin:0 0 8px 0;">Portal Mode WiFi</h3>
          <input type="text" id="portalSSIDInput" placeholder="Portal WiFi Name" style="margin-bottom:8px;">
          <input type="password" id="portalPasswordInput" placeholder="Portal Password (min 8 chars)" style="margin-bottom:8px;">
          <button class="btn btn-primary btn-sm" onclick="savePortalSettings()" style="width:100%;">SAVE PORTAL WIFI</button>
          <div style="font-size:11px;color:var(--text-dim);margin-top:6px;">
            Current: <span id="currentPortalSSID">&mdash;</span>
          </div>
        </div>

        <!-- Bluetooth Settings Section -->
        <div style="margin-bottom:20px;">
          <h3 style="color:var(--primary);font-size:14px;margin:0 0 8px 0;">Bluetooth</h3>
          <label style="display:flex;align-items:center;gap:8px;font-size:13px;margin-bottom:8px;">
            <input type="checkbox" id="bleAutoCheckbox" style="width:auto;">
            Auto-start Bluetooth on unlock
          </label>
          <button class="btn btn-primary btn-sm" onclick="saveBleSettings()" style="width:100%;">SAVE BLUETOOTH</button>
          <div style="font-size:11px;color:var(--text-dim);margin-top:6px;">
            Radio turns off when the device locks or after 5 min idle. Status: <span id="bleStatus">&mdash;</span>
          </div>
          <div style="font-size:11px;color:var(--text-dim);margin-top:4px;">
            Tip: while in portal mode, hold the device button 3s to arm/disarm Bluetooth (takes effect on the next unlock; hold 6s to leave portal mode).
          </div>
        </div>

        <!-- Timezone Settings Section -->
        <div style="margin-bottom:20px;">
          <h3 style="color:var(--primary);font-size:14px;margin:0 0 8px 0;">Timezone</h3>
          <select id="timezoneSelect">
            <option value="CST6CDT,M3.2.0,M11.1.0">America/Winnipeg</option>
            <option value="EST5EDT,M3.2.0,M11.1.0">America/New_York</option>
            <option value="CST6CDT,M3.2.0,M11.1.0">America/Chicago</option>
            <option value="MST7MDT,M3.2.0,M11.1.0">America/Denver</option>
            <option value="PST8PDT,M3.2.0,M11.1.0">America/Los_Angeles</option>
            <option value="UTC0">UTC</option>
            <option value="GMT0BST,M3.5.0/1,M10.5.0">Europe/London</option>
            <option value="CET-1CEST,M3.5.0,M10.5.0">Europe/Paris</option>
            <option value="CST-8">Asia/Shanghai</option>
            <option value="JST-9">Asia/Tokyo</option>
            <option value="AEST-10AEDT,M10.1.0,M4.1.0/3">Australia/Sydney</option>
          </select>
          <button class="btn btn-primary btn-sm" onclick="saveTimezone()" style="width:100%;">SAVE TIMEZONE</button>
          <div style="font-size:11px;color:var(--text-dim);margin-top:6px;">
            Current: <span id="currentTimezone">&mdash;</span>
          </div>
        </div>

        <!-- Instructions Fine Print -->
        <div style="border-top:1px solid rgba(255,255,255,0.1);padding-top:15px;margin-top:15px;">
          <p style="font-size:10px;color:var(--text-dim);margin:0 0 6px 0;"><strong>Device Usage:</strong></p>
          <ul style="font-size:10px; color:var(--text-dim); padding-left:15px; margin:0;">
            <li><strong>Unlock:</strong> Tap button 3 times within 2 seconds</li>
            <li><strong>Auto-Lock:</strong> Device locks after 5 minutes of inactivity</li>
            <li><strong>TOTP Mode:</strong> Tap cycles through vault keys</li>
            <li><strong>Password Mode:</strong> Hold button 3s to enter/exit</li>
            <li><strong>Portal Mode:</strong> Hold button during boot</li>
          </ul>
          <p style="font-size:10px; color:var(--text-dim);margin:8px 0 0 0;">
            Password file: <code>/Passwords.txt</code>
          </p>
        </div>
      </div>
    </div>
  </div>

  <!-- ── VAULT TAB ─────────────────────────────────────────── -->
  <div id="vault" class="tab-content">
    <h2>Stored Secrets</h2>
    <div id="vaultList"><p style="color:var(--text-dim);text-align:center;">Loading...</p></div>
  </div>

  <!-- ── ADD TAB ───────────────────────────────────────────── -->
  <div id="add" class="tab-content">
    <div class="card">
      <h2>Manual Entry</h2>
      <input type="text" id="label" placeholder="Account Name">
      <input type="text" id="secret" placeholder="Secret Key (Base32)">
      <button class="btn btn-primary" onclick="addKey()">SAVE TO VAULT</button>
    </div>

    <div class="card">
      <h2>Restore Database</h2>
      <p style="font-size:12px; color:var(--text-dim); margin-bottom:12px;">Import a <code>vault.json</code> backup to replace all TOTP keys. The old vault is kept as <code>vault.json.bak</code>.</p>
      <form method="POST" action="/upload" enctype="multipart/form-data">
        <input type="file" name="update" style="color:var(--text-dim); font-size:12px; margin-bottom:12px;">
        <button type="submit" class="btn btn-outline">SYNC DATABASE</button>
      </form>
    </div>

    <div class="card">
      <h2>Import Bitwarden Export</h2>
      <p style="font-size:12px; color:var(--text-dim); margin-bottom:12px;">Pick an <code>unencrypted</code> Bitwarden/Vaultwarden JSON export. TOTP secrets go to the vault, passwords go to <code>/files/Passwords.txt</code>. Everything is converted in your browser — the export never leaves this page except to the device.</p>
      <!-- Same Firefox-safe pattern as the Files tab: a <label> opens the
           picker natively, no JS click() needed. -->
      <input type="file" id="bwImportInput" class="vh-input" accept=".json,application/json" onchange="importBitwardenFile()">
      <label for="bwImportInput" class="btn btn-primary" style="cursor:pointer; display:block; text-align:center;">
        <i class="mi">&#x2B06;</i> IMPORT BITWARDEN JSON
      </label>
    </div>
  </div>

  <!-- ── FILES TAB ─────────────────────────────────────────── -->
  <div id="files" class="tab-content">
    <div class="card">
      <h2><i class="mi">&#x1F4C1;</i> File Storage</h2>
      <div id="fileList" class="file-list">
        <div style="color:var(--text-dim); font-size:12px; text-align:center; padding:10px;">Loading...</div>
      </div>

      <!-- Upload existing file: a <label> natively opens the file picker,
           no JS click() needed (Firefox ignores clicks on hidden inputs) -->
      <input type="file" id="fileInput" class="vh-input" onchange="uploadFile()">
      <label for="fileInput" class="btn btn-outline" style="margin-top:14px; cursor:pointer; display:block; text-align:center;">
        <i class="mi">&#x2B06;</i> UPLOAD FILE
      </label>
    </div>

    <!-- Create new text file -->
    <div class="card">
      <h2><i class="mi">&#x1F4DD;</i> New Text File</h2>
      <div class="new-file-row">
        <input type="text" id="newFileName" placeholder="filename.txt">
        <button class="btn btn-primary btn-sm" onclick="createNewFile()">CREATE</button>
      </div>
    </div>
  </div>

</div><!-- /container -->

<!-- ── BOTTOM NAV ─────────────────────────────────────────── -->
<nav class="nav-bar">
  <div class="nav-item active"  onclick="showTab('home',  this)"><i class="mi">&#x2302;</i>Home</div>
  <div class="nav-item"         onclick="showTab('vault', this)"><i class="mi">&#x1F511;</i>Vault</div>
  <div class="nav-item"         onclick="showTab('add',   this)"><i class="mi">&#x2795;</i>Add</div>
  <div class="nav-item"         onclick="showTab('files', this)"><i class="mi">&#x1F4C1;</i>Files</div>
</nav>

<!-- ── FILE EDITOR MODAL ──────────────────────────────────── -->
<div class="modal-bg" id="editorModal">
  <div class="modal">
    <div class="modal-header">
      <h3 id="editorTitle">FILE</h3>
      <i class="mi" onclick="closeEditor()">&#x2715;</i>
    </div>
    <div class="modal-body">
      <textarea id="fileContent"></textarea>
    </div>
    <div class="modal-footer">
      <button class="btn btn-primary" id="saveBtn" onclick="saveFile()">SAVE</button>
      <button class="btn btn-outline"  onclick="closeEditor()">CANCEL</button>
    </div>
  </div>
</div>

<script>
// ── State ────────────────────────────────────────────────────
let currentEditPath = null;
let vaultCache = [];   // [{label, index}] from /api/list
let fileCache = [];    // [{name, size}] from /api/list-files
let loggedIn = false;

// Escape for HTML text interpolation (labels, filenames).
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function(c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

// Authenticated fetch: on 401 the session died -> show the login overlay.
function api(path, opts) {
  opts = opts || {};
  opts.credentials = 'same-origin';
  return fetch(path, opts).then(function(r) {
    if (r.status === 401) {
      loggedIn = false;
      showLogin();
      return Promise.reject(new Error('unauthorized'));
    }
    return r;
  });
}

// ── Login ────────────────────────────────────────────────────
function showLogin() {
  document.getElementById('loginModal').classList.add('open');
  var p = document.getElementById('loginPass');
  if (p) p.focus();
}
function hideLogin() {
  document.getElementById('loginModal').classList.remove('open');
  document.getElementById('loginPass').value = '';
  document.getElementById('loginErr').innerText = '';
}
function doLogin() {
  var p = document.getElementById('loginPass').value;
  fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'password=' + encodeURIComponent(p)
  }).then(function(r) {
    if (!r.ok) throw 0;
    afterAuth();
  }).catch(function() {
    document.getElementById('loginErr').innerText = 'Wrong password';
    document.getElementById('loginPass').value = '';
  });
}
// After any successful auth: force a password change if still on the default,
// otherwise enter the app normally.
function afterAuth() {
  loggedIn = true;
  hideLogin();
  api('/api/auth-check').then(function(r) { return r.json(); }).then(function(d) {
    if (d && d.mustChangePassword) showForceChange();
    else loadHomeStats();
  }).catch(function() { showLogin(); });
}
function showForceChange() {
  document.getElementById('forceChangeModal').classList.add('open');
  document.getElementById('newPortalPass').value = '';
  document.getElementById('newPortalPass2').value = '';
  document.getElementById('forceChangeErr').innerText = '';
  var p = document.getElementById('newPortalPass');
  if (p) p.focus();
}
function hideForceChange() {
  document.getElementById('forceChangeModal').classList.remove('open');
}
function doForceChange() {
  var p1 = document.getElementById('newPortalPass').value;
  var p2 = document.getElementById('newPortalPass2').value;
  var err = document.getElementById('forceChangeErr');
  if (p1.length < 8) { err.innerText = 'At least 8 characters.'; return; }
  if (p1 !== p2) { err.innerText = 'Passwords do not match.'; return; }
  if (p1 === 'espvault') { err.innerText = 'Pick something other than the default.'; return; }
  api('/api/portal-settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: p1 })
  }).then(function(r) {
    if (!r.ok) { return r.text().then(function(t) { throw new Error(t); }); }
    hideForceChange();
    loadHomeStats();
  }).catch(function(e) { err.innerText = 'Error: ' + e.message; });
}
function doLogout() {
  api('/api/logout', { method: 'POST' }).catch(function() {});
  loggedIn = false;
  showLogin();
}
document.getElementById('newPortalPass2').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') doForceChange();
});
document.getElementById('loginPass').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') doLogin();
});

// ── Tab Switching ─────────────────────────────────────────────
function showTab(tabId, el) {
  document.querySelectorAll('.tab-content').forEach(function(t) { t.classList.remove('active'); });
  document.querySelectorAll('.nav-item').forEach(function(n) { n.classList.remove('active'); });
  document.getElementById(tabId).classList.add('active');
  (el || event.currentTarget).classList.add('active');
  if (tabId === 'home')  { loadHomeStats(); }
  if (tabId === 'vault') { loadVault(); }
  if (tabId === 'files') { refreshFiles(); }
}

// ── Home Stats ───────────────────────────────────────────────
function loadHomeStats() {
  if (!loggedIn) return;
  // First, send browser time to device so the clock is right
  var browserTime = Math.floor(Date.now() / 1000);
  api('/api/set-time', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ timestamp: browserTime })
  }).catch(function(e) { console.log('set-time error:', e); });

  Promise.all([
    api('/api/storage').then(function(r) { return r.text(); }).catch(function(e) { console.log('storage error:', e); return '&mdash;'; }),
    api('/api/list-files').then(function(r) { return r.json(); }).catch(function(e) { console.log('list-files error:', e); return []; }),
    api('/api/list').then(function(r) { return r.json(); }).catch(function(e) { console.log('list error:', e); return []; }),
    api('/api/auth-codes').then(function(r) { return r.json(); }).catch(function(e) { console.log('auth-codes error:', e); return []; }),
    api('/api/time-info').then(function(r) { return r.json(); }).catch(function(e) { console.log('time-info error:', e); return {}; }),
    api('/api/device-name').then(function(r) { return r.json(); }).catch(function(e) { console.log('device-name error:', e); return { name: '&mdash;' }; })
  ]).then(function(res) {
    var storage = res[0], files = res[1], keys = res[2], codes = res[3], timeInfo = res[4], deviceInfo = res[5];
    document.getElementById('storageStatus').innerText = storage || '&mdash;';
    document.getElementById('keyCountHome').innerText = keys && keys.length ? keys.length : '&mdash;';
    document.getElementById('fileCountHome').innerText = files && files.length ? files.length : '&mdash;';

    // Device name
    if (deviceInfo && deviceInfo.name) {
      document.getElementById('deviceNameDisplay').innerText = deviceInfo.name;
      document.getElementById('deviceNameInput').value = deviceInfo.name;
    }

    // Time: prefer device time, fall back to browser time
    var browserStr = new Date().toLocaleString();
    if (timeInfo && timeInfo.currentTime && timeInfo.currentTime.indexOf('1970') === -1) {
      document.getElementById('currentTime').innerText = timeInfo.currentTime;
    } else {
      document.getElementById('currentTime').innerText = browserStr + ' (browser)';
    }
    document.getElementById('ntpServer').innerText = (timeInfo && timeInfo.ntpServer) || '&mdash;';
    document.getElementById('tzName').innerText = (timeInfo && timeInfo.timezone) || '&mdash;';
    var ts = document.getElementById('timeStatus');
    if (timeInfo && timeInfo.timeIsFresh === true) {
      ts.innerText = 'Fresh (NTP synced)';
      ts.style.color = 'var(--accent)';
    } else if (timeInfo && timeInfo.timeIsFresh === false) {
      ts.innerText = 'Stale (sync needed)';
      ts.style.color = 'var(--warn)';
    } else {
      ts.innerText = '&mdash;';
      ts.style.color = '';
    }

    // Current WiFi SSID (shown in settings card)
    var wifiEl = document.getElementById('currentWifiSSID');
    if (wifiEl) wifiEl.innerText = (timeInfo && timeInfo.wifiSSID) || '&mdash;';

    // Refresh the auth codes card if it's open
    var list = document.getElementById('authCodesList');
    if (list.style.display === 'block') renderAuthCodes(list, codes);
  });
}

// ── File Browser ─────────────────────────────────────────────
function refreshFiles() {
  if (!loggedIn) return;
  api('/api/list-files').then(function(r) { return r.json(); }).then(function(files) {
    fileCache = files || [];
    var box = document.getElementById('fileList');
    if (!fileCache.length) {
      box.innerHTML = '<div style="text-align:center;color:var(--text-dim);padding:10px;">No files stored</div>';
      return;
    }
    var html = '';
    fileCache.forEach(function(f, i) {
      var isText = /\.(txt|json|md|csv|log|ini|cfg|conf|yaml|yml)$/i.test(f.name);
      var editBtn = isText
        ? '<i class="mi" title="Edit" onclick="openEditorIdx(' + i + ')">&#x270E;</i>'
        : '<i class="mi" title="Download" onclick="viewFileIdx(' + i + ')">&#x2B07;</i>';
      html += '<div class="file-item">'
        + '<div class="file-info">'
        + '<span class="file-name">' + esc(f.name) + '</span>'
        + '<span class="file-meta">' + (f.size / 1024).toFixed(1) + ' KB</span>'
        + '</div>'
        + '<div class="file-ops">'
        + '<i class="mi" title="View" onclick="viewFileIdx(' + i + ')">&#x1F441;</i>'
        + editBtn
        + '<i class="mi del" title="Delete" onclick="deleteFileIdx(' + i + ')">&#x1F5D1;</i>'
        + '</div></div>';
    });
    box.innerHTML = html;
  }).catch(function(e) { console.log('refreshFiles error:', e); });
}

function viewFileIdx(i) {
  var f = fileCache[i];
  if (!f) return;
  window.open('/api/view-file?path=' + encodeURIComponent(f.name), '_blank');
}

function uploadFile() {
  var input = document.getElementById('fileInput');
  var file = input.files[0];
  if (!file) return;
  var fd = new FormData();
  fd.append('update', file);
  api('/api/upload-file', { method: 'POST', body: fd })
    .then(function(r) {
      if (!r.ok) return r.text().then(function(t) { throw new Error(t || ('HTTP ' + r.status)); });
      input.value = '';
      refreshFiles();
    })
    .catch(function(e) {
      console.log('upload error:', e);
      if (e && e.message !== 'unauthorized') alert('Upload failed: ' + e.message);
    });
}

// ── Bitwarden export import ──────────────────────────────────────────
// Converts an unencrypted Bitwarden/Vaultwarden JSON export in the browser:
// TOTP secrets -> vault.json (device's native {name, login.totp} format),
// passwords   -> /files/Passwords.txt (label:username:password lines).
// Passwords are uploaded first (/api/upload-file does not reboot); the vault
// goes to /upload last because the device reboots after it.
function bwNormalizeLabel(name) {
  var label = ((name || 'UNKNOWN') + '').toUpperCase().replace(/ /g, '_');
  label = label.replace(/[^A-Z0-9_\-]/g, '') || 'UNKNOWN';
  return label.substring(0, 48);
}
function bwCleanSecret(s) {
  return ((s || '') + '').trim().toUpperCase().replace(/ /g, '').replace(/=+$/, '');
}
function bwValidSecret(s) {
  return s.length >= 8 && /^[A-Z2-7]+$/.test(s);
}
function bwField(s, allowColon, maxlen) {
  s = ((s || '') + '').replace(/[\r\n]/g, '');
  if (!allowColon) s = s.replace(/:/g, '-');
  s = s.trim();
  return s.substring(0, maxlen);
}
function importBitwardenFile() {
  var input = document.getElementById('bwImportInput');
  var file = input.files[0];
  if (!file) return;
  var reader = new FileReader();
  reader.onload = function() {
    input.value = '';
    var data;
    try {
      data = JSON.parse(reader.result);
    } catch (e) {
      alert('Not a valid JSON file: ' + e.message);
      return;
    }
    doBitwardenImport(data);
  };
  reader.onerror = function() { input.value = ''; alert('Could not read the file.'); };
  reader.readAsText(file);
}
function doBitwardenImport(data) {
  if (data && data.encrypted === true) {
    alert('This export is encrypted and cannot be read. Export again without encryption (and delete the file afterwards).');
    return;
  }
  var items = (data && data.items) || [];
  if (!Array.isArray(items)) items = [];
  var isBitwarden = items.some(function(it) { return it && it.login; });
  if (!isBitwarden) {
    // Maybe it's already a vault.json backup -> treat like Restore Database.
    var looksVault = Array.isArray(data) ||
      (data && Array.isArray(data.items) && data.items.every(function(it) {
        return it && (it.secret || (it.login && it.login.totp));
      }));
    if (looksVault) {
      if (!confirm('This looks like a vault backup, not a Bitwarden export. Upload it as the new vault?')) return;
      postVaultFile(JSON.stringify(data), 0, 0);
    } else {
      alert('Unrecognized file: not a Bitwarden export or vault backup.');
    }
    return;
  }
  if (data.encrypted === true) {
    alert('This export is encrypted and cannot be read. Export again without encryption (and delete the file afterwards).');
    return;
  }
  if (data.encrypted === true) {
    alert('This export is encrypted and cannot be read. Export again without encryption (and delete the file afterwards).');
    return;
  }

  var vaultEntries = [];   // {name, login:{totp}} in the device's native format
  var seenLabels = {};
  var seenTotp = {};       // skip exact duplicate (name + secret) entries
  var pwLines = [];
  var seenPw = {};
  var skippedTotp = 0;

  items.forEach(function(item) {
    var login = item.login || {};
    // --- TOTP ---
    var totpUrl = login.totp || '';
    var m = /secret=([A-Za-z0-9=]+)/i.exec(totpUrl);
    if (m) {
      var secret = bwCleanSecret(m[1]);
      var dupKey = (item.name || '') + '|' + secret;
      if (bwValidSecret(secret) && !seenTotp[dupKey]) {
        seenTotp[dupKey] = true;
        var base = bwNormalizeLabel(item.name), label = base, n = 2;
        while (seenLabels[label]) { label = base + '_' + n; n++; }
        seenLabels[label] = true;
        vaultEntries.push({ name: label, login: { totp: 'otpauth://totp/' + label + '?secret=' + secret } });
      } else {
        skippedTotp++;
      }
    }
    // --- password ---
    var pw = ((login.password || '') + '').replace(/[\r\n]/g, '');
    if (pw) {
      var plabel = bwField(item.name, false, 40) || 'UNKNOWN';
      var puser = bwField(login.username, false, 64);
      var line = plabel + ':' + puser + ':' + pw;
      if (!seenPw[line]) { seenPw[line] = true; pwLines.push(line); }
    }
  });

  if (!vaultEntries.length && !pwLines.length) {
    alert('No TOTP secrets or passwords found in this export.' +
      (skippedTotp ? ' (' + skippedTotp + ' invalid secret(s) skipped.)' : ''));
    return;
  }
  var summary = 'Found ' + vaultEntries.length + ' TOTP secret(s) and ' +
    pwLines.length + ' password(s)' +
    (skippedTotp ? ' (' + skippedTotp + ' invalid secret(s) skipped)' : '') +
    '.\n\nImport to the device?';
  if (!confirm(summary)) return;

  var chain = Promise.resolve();
  if (pwLines.length) {
    var txt = '# Esp Vault passwords - format per line: label:username:password\n' +
      pwLines.join('\n') + '\n';
    chain = chain.then(function() {
      return postRawFile('/api/upload-file', 'Passwords.txt', txt, 'text/plain');
    });
  }
  chain.then(function() {
    if (vaultEntries.length) {
      postVaultFile(JSON.stringify(vaultEntries), vaultEntries.length, pwLines.length);
    } else {
      alert('Imported ' + pwLines.length + ' password(s) to /files/Passwords.txt.');
      if (typeof refreshFiles === 'function') refreshFiles();
    }
  }).catch(function(e) {
    console.log('import error:', e);
    if (e && e.message !== 'unauthorized') alert('Import failed: ' + e.message);
  });
}
function postRawFile(url, filename, content, mime) {
  var fd = new FormData();
  fd.append('update', new Blob([content], { type: mime }), filename);
  return api(url, { method: 'POST', body: fd }).then(function(r) {
    if (!r.ok) return r.text().then(function(t) { throw new Error(t || ('HTTP ' + r.status)); });
    return r;
  });
}
function postVaultFile(content, totpCount, pwCount) {
  // /upload reboots the device on success, so this is always the last step.
  postRawFile('/upload', 'vault.json', content, 'application/json').then(function() {
    alert('Imported ' + totpCount + ' TOTP secret(s)' +
      (pwCount ? ' and ' + pwCount + ' password(s)' : '') +
      '. The device is rebooting — rejoin the portal WiFi to continue.');
  }).catch(function(e) {
    console.log('vault upload error:', e);
    if (e && e.message !== 'unauthorized') alert('Vault upload failed: ' + e.message);
  });
}

function deleteFileIdx(i) {
  var f = fileCache[i];
  if (!f) return;
  if (!confirm('Delete ' + f.name + '?')) return;
  api('/api/delete-file?path=' + encodeURIComponent(f.name)).then(refreshFiles);
}

function createNewFile() {
  var name = document.getElementById('newFileName').value.trim();
  if (!name) return alert('Enter a filename');
  if (name.indexOf('.') === -1) name += '.txt';
  currentEditPath = name.charAt(0) === '/' ? name.substring(1) : name;
  document.getElementById('editorTitle').innerText = name;
  document.getElementById('fileContent').value = '';
  document.getElementById('editorModal').classList.add('open');
}

// ── File Editor Modal ─────────────────────────────────────────
function openEditorIdx(i) {
  var f = fileCache[i];
  if (!f) return;
  var name = f.name;
  currentEditPath = name.charAt(0) === '/' ? name.substring(1) : name;
  document.getElementById('editorTitle').innerText = name;
  document.getElementById('fileContent').value = 'Loading...';
  document.getElementById('editorModal').classList.add('open');
  api('/api/view-file?path=' + encodeURIComponent(currentEditPath))
    .then(function(r) { return r.text(); })
    .then(function(text) { document.getElementById('fileContent').value = text; });
}

function closeEditor() {
  document.getElementById('editorModal').classList.remove('open');
  document.getElementById('newFileName').value = '';
  currentEditPath = null;
}

function saveFile() {
  if (!currentEditPath) return;
  var content = document.getElementById('fileContent').value;
  var btn = document.getElementById('saveBtn');
  btn.disabled = true; btn.innerText = 'SAVING...';
  api('/api/save-file', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'path=' + encodeURIComponent(currentEditPath) + '&content=' + encodeURIComponent(content)
  }).then(function(r) {
    btn.disabled = false; btn.innerText = 'SAVE';
    if (r.ok) { closeEditor(); refreshFiles(); }
    else r.text().then(function(t) { alert('Error: ' + t); });
  }).catch(function() { btn.disabled = false; btn.innerText = 'SAVE'; });
}

// ── Auth Codes Toggle ─────────────────────────────────────────
function toggleAuthCodes() {
  var list = document.getElementById('authCodesList');
  var icon = document.getElementById('authToggleIcon');
  var open = list.style.display !== 'block';
  list.style.display = open ? 'block' : 'none';
  icon.innerHTML = open ? '&#x25B2;' : '&#x25BC;';
  if (open) {
    loadAuthCodes();
    if (!window.authTimer) {
      window.authTimer = setInterval(function() { updateAuthCountdowns(); }, 1000);
    }
  } else {
    clearInterval(window.authTimer);
    window.authTimer = null;
  }
}

function renderAuthCodes(list, data) {
  if (!data || !data.length) {
    list.innerHTML = '<p style="color:var(--text-dim);text-align:center;">No TOTP keys configured</p>';
    return;
  }
  var html = '';
  data.forEach(function(item) {
    var barPixels = Math.round((item.remaining / 30) * 100);
    var c0 = esc(String(item.code || '').substring(0, 3));
    var c1 = esc(String(item.code || '').substring(4, 7));
    html += '<div class="card" style="padding:12px;margin-top:10px;">'
      + '<div style="display:flex;justify-content:space-between;align-items:center;">'
      + '<strong style="color:var(--primary);letter-spacing:0.5px;">' + esc(item.label) + '</strong>'
      + '<span class="auth-remaining" style="color:var(--text-dim);font-family:monospace;">' + item.remaining + 's</span>'
      + '</div>'
      + '<div style="font-size:24px;font-weight:bold;letter-spacing:2px;margin-top:8px;">'
      + '<span style="color:var(--primary)">' + c0 + '</span> <span style="color:var(--primary)">' + c1 + '</span>'
      + '</div>'
      + '<div style="height:4px;background:#334155;border-radius:2px;margin-top:8px;overflow:hidden;">'
      + '<div class="auth-bar" style="width:' + barPixels + '%;height:100%;background:var(--primary);transition:width 0.5s linear;"></div>'
      + '</div></div>';
  });
  list.innerHTML = html;
}

function loadAuthCodes() {
  api('/api/auth-codes').then(function(r) { return r.json(); }).then(function(data) {
    renderAuthCodes(document.getElementById('authCodesList'), data);
  }).catch(function(e) { console.log('auth-codes error:', e); });
}

function updateAuthCountdowns() {
  var bars = document.querySelectorAll('#authCodesList .auth-bar');
  var needsReload = false;
  bars.forEach(function(bar) {
    var card = bar.closest('.card');
    var span = card ? card.querySelector('.auth-remaining') : null;
    if (!span) return;
    var remaining = Math.max(0, (parseInt(span.innerText, 10) || 30) - 1);
    bar.style.width = (remaining / 30 * 100) + '%';
    span.innerText = remaining + 's';
    if (remaining <= 0) needsReload = true;
  });
  if (needsReload && document.getElementById('authCodesList').style.display === 'block') loadAuthCodes();
}

// ── System Settings Toggle ────────────────────────────────────
function toggleSystemSettings() {
  var list = document.getElementById('systemSettingsList');
  var icon = document.getElementById('systemToggleIcon');
  var open = list.style.display !== 'block';
  list.style.display = open ? 'block' : 'none';
  icon.innerHTML = open ? '&#x25B2;' : '&#x25BC;';
  if (open) {
    loadWiFiSettings();
    loadPortalSettings();
    loadTimezoneSettings();
    loadBleSettings();
  }
}

var TZ_OPTIONS = [
  'CST6CDT,M3.2.0,M11.1.0',      // America/Winnipeg, America/Chicago
  'EST5EDT,M3.2.0,M11.1.0',      // America/New_York
  'MST7MDT,M3.2.0,M11.1.0',      // America/Denver
  'PST8PDT,M3.2.0,M11.1.0',      // America/Los_Angeles
  'UTC0',                        // UTC
  'GMT0BST,M3.5.0/1,M10.5.0',    // Europe/London
  'CET-1CEST,M3.5.0,M10.5.0',    // Europe/Paris
  'CST-8',                       // Asia/Shanghai
  'JST-9',                       // Asia/Tokyo
  'AEST-10AEDT,M10.1.0,M4.1.0/3' // Australia/Sydney
];

function loadTimezoneSettings() {
  api('/api/timezone').then(function(r) { return r.json(); }).then(function(data) {
    var sel = document.getElementById('timezoneSelect');
    var cur = document.getElementById('currentTimezone');
    var tz = data.tz || data.posix || '';
    var matched = false;
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === tz) { sel.selectedIndex = i; matched = true; break; }
    }
    cur.innerText = data.name ? data.name + (matched ? '' : ' (' + tz + ')') : '&mdash;';
  }).catch(function(e) {
    console.log('Timezone settings error:', e);
    document.getElementById('currentTimezone').innerText = '&mdash;';
  });
}

function loadWiFiSettings() {
  api('/api/wifi-settings').then(function(r) { return r.json(); }).then(function(data) {
    if (data.ssid) {
      document.getElementById('wifiSSIDInput').value = data.ssid;
      document.getElementById('currentWifiSSID').innerText = data.ssid;
    }
    if (data.password) {
      document.getElementById('wifiPasswordInput').value = data.password;
    }
  }).catch(function(e) {
    console.log('WiFi settings error:', e);
    document.getElementById('currentWifiSSID').innerText = '&mdash;';
  });
}

function loadPortalSettings() {
  api('/api/portal-settings').then(function(r) { return r.json(); }).then(function(data) {
    if (data.ssid) {
      document.getElementById('portalSSIDInput').value = data.ssid;
      document.getElementById('currentPortalSSID').innerText = data.ssid;
    }
    if (data.password) {
      document.getElementById('portalPasswordInput').value = data.password;
    }
  }).catch(function(e) {
    console.log('Portal settings error:', e);
    document.getElementById('currentPortalSSID').innerText = '&mdash;';
  });
}

function saveWiFiSettings() {
  var ssid = document.getElementById('wifiSSIDInput').value.trim();
  var password = document.getElementById('wifiPasswordInput').value;
  if (!ssid) return alert('WiFi SSID cannot be empty');
  api('/api/wifi-settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ssid: ssid, password: password })
  }).then(function(r) {
    if (r.ok) {
      alert('WiFi settings saved! Device will use these on next boot.');
      document.getElementById('currentWifiSSID').innerText = ssid;
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  }).catch(function(e) { alert('Error saving WiFi settings: ' + e); });
}

function savePortalSettings() {
  var ssid = document.getElementById('portalSSIDInput').value.trim();
  var password = document.getElementById('portalPasswordInput').value;
  if (!ssid) return alert('Portal SSID cannot be empty');
  if (password.length < 8) return alert('Portal password must be at least 8 characters (ESP32 AP requirement)');
  api('/api/portal-settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ssid: ssid, password: password })
  }).then(function(r) {
    if (r.ok) {
      alert('Portal settings saved! Will be used next time you enter portal mode.');
      document.getElementById('currentPortalSSID').innerText = ssid;
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  }).catch(function(e) { alert('Error saving portal settings: ' + e); });
}

// ── Bluetooth Settings ──────────────────────────────────────────
function loadBleSettings() {
  api('/api/ble').then(function(r) { return r.json(); }).then(function(data) {
    document.getElementById('bleAutoCheckbox').checked = !!data.auto;
    document.getElementById('bleStatus').innerText = data.active ? 'radio ON' : 'radio off';
  }).catch(function(e) { console.log('Bluetooth settings error:', e); });
}

function saveBleSettings() {
  var auto = document.getElementById('bleAutoCheckbox').checked;
  api('/api/ble', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ auto: auto })
  }).then(function(r) {
    if (r.ok) {
      alert('Bluetooth setting saved! The radio will auto-start on unlock.');
      loadBleSettings();
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  }).catch(function(e) { alert('Error saving Bluetooth setting: ' + e); });
}

function saveTimezone() {  var sel = document.getElementById('timezoneSelect');
  var posix = sel.options[sel.selectedIndex].value;
  var name = sel.options[sel.selectedIndex].text;
  api('/api/timezone', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: name, posix: posix })
  }).then(function(r) {
    if (r.ok) {
      alert('Timezone saved! Device will use this timezone for time display.');
      document.getElementById('currentTimezone').innerText = name;
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  }).catch(function(e) { alert('Error saving timezone: ' + e); });
}

function saveDeviceName() {
  var name = document.getElementById('deviceNameInput').value.trim();
  if (!name) return alert('Device name cannot be empty');
  var formData = new URLSearchParams();
  formData.append('plain', name);
  api('/api/device-name', { method: 'POST', body: formData }).then(function(r) {
    if (r.ok) {
      alert('Device name saved!');
      loadHomeStats();
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  }).catch(function(e) { alert('Error: ' + e); });
}

// ── Vault Logic ───────────────────────────────────────────────
// /api/list returns [{label, index}] only; secrets are fetched
// per-entry via /api/get when the user edits an entry.
function loadVault() {
  if (!loggedIn) return;
  api('/api/list').then(function(r) { return r.json(); }).then(function(data) {
    vaultCache = data || [];
    var box = document.getElementById('vaultList');
    if (!vaultCache.length) {
      box.innerHTML = '<p style="color:var(--text-dim);text-align:center;">Vault is empty.</p>';
      return;
    }
    var html = '';
    vaultCache.forEach(function(item, i) {
      html += '<div class="card" id="card-' + i + '">'
        + '<div id="view-' + i + '" class="vault-item">'
        + '<div><b style="color:var(--primary)">' + esc(item.label) + '</b></div>'
        + '<div class="actions">'
        + '<i class="mi" title="Edit" onclick="showVaultEdit(' + i + ')">&#x270E;</i>'
        + '<i class="mi del" title="Delete" onclick="deleteKey(' + i + ')">&#x1F5D1;</i>'
        + '</div></div>'
        + '<div id="edit-' + i + '" style="display:none">'
        + '<div class="inline-edit"><input type="text" id="input-label-' + i + '" placeholder="Account Name"></div>'
        + '<div class="inline-edit"><input type="text" id="input-secret-' + i + '" placeholder="Secret Key"></div>'
        + '<div class="inline-edit" style="margin-top:6px;">'
        + '<button class="btn btn-primary btn-sm" onclick="saveVaultEdit(' + i + ')">&#x1F4BE;</button>'
        + '<button class="btn btn-outline btn-sm" onclick="cancelVaultEdit(' + i + ')">&#x2715;</button>'
        + '</div></div></div>';
    });
    box.innerHTML = html;
  }).catch(function(e) { console.log('loadVault error:', e); });
}

function showVaultEdit(i) {
  var item = vaultCache[i];
  if (!item) return;
  document.getElementById('view-' + i).style.display = 'none';
  document.getElementById('edit-' + i).style.display = 'block';
  var labelInput = document.getElementById('input-label-' + i);
  var secretInput = document.getElementById('input-secret-' + i);
  labelInput.value = item.label;
  secretInput.value = 'Loading...';
  // Fetch the secret only now (never exposed in the list view).
  api('/api/get?index=' + item.index).then(function(r) { return r.json(); }).then(function(d) {
    secretInput.value = d.secret || '';
  }).catch(function() { secretInput.value = ''; });
}
function cancelVaultEdit(i) {
  document.getElementById('view-' + i).style.display = 'flex';
  document.getElementById('edit-' + i).style.display = 'none';
}
function saveVaultEdit(i) {
  var item = vaultCache[i];
  if (!item) return;
  var newLabel = document.getElementById('input-label-' + i).value.toUpperCase().replace(/ /g, '_');
  var newSecret = document.getElementById('input-secret-' + i).value.replace(/ /g, '');
  if (!newLabel) return alert('Label cannot be empty');
  if (!newSecret) return alert('Secret cannot be empty');
  api('/api/edit?index=' + item.index + '&label=' + encodeURIComponent(newLabel) + '&secret=' + encodeURIComponent(newSecret))
    .then(function(r) {
      if (r.ok) loadVault();
      else r.text().then(function(t) { alert('Error: ' + t); });
    });
}
function addKey() {
  var l = document.getElementById('label').value;
  var s = document.getElementById('secret').value;
  if (!l || !s) return alert('Fill out both fields');
  api('/api/add?label=' + encodeURIComponent(l) + '&secret=' + encodeURIComponent(s)).then(function(r) {
    if (r.ok) {
      document.getElementById('label').value = '';
      document.getElementById('secret').value = '';
      showTab('vault', document.querySelectorAll('.nav-item')[1]);
    } else {
      r.text().then(function(t) { alert('Error: ' + t); });
    }
  });
}
function deleteKey(i) {
  var item = vaultCache[i];
  if (!item) return;
  if (confirm('Delete "' + item.label + '"?')) {
    api('/api/delete?index=' + item.index).then(loadVault);
  }
}

// ── Initial load ──────────────────────────────────────────────
// Check the session first; unauthenticated browsers land on the login overlay.
api('/api/auth-check').then(function(r) {
  if (r.ok) {
    afterAuth();
  } else {
    showLogin();
  }
}).catch(function() { /* 401 handler already showed the login overlay */ });
</script>
</body></html>

)rawliteral";

#endif
