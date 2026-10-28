const $ = (id) => document.getElementById(id);
const authScreen = $('auth-screen');
const loginForm = $('login-form');
const registerForm = $('register-form');
const authError = $('auth-error');
const authTitle = $('auth-title');
const authIntro = $('auth-intro');
const authSwitch = $('auth-switch');

function showAuth(message = '') {
  authScreen.hidden = false;
  $('app-header').hidden = true;
  $('app-main').hidden = true;
  $('app-footer').hidden = true;
  authError.textContent = message;
  loginForm.classList.remove('hidden');
  registerForm.classList.add('hidden');
  authTitle.innerHTML = 'Welcome back<span>.</span>';
  authIntro.textContent = 'Sign in to open your document encryption workspace.';
  authSwitch.innerHTML = 'New here? <button type="button" id="show-register">Create an account</button>';
  $('show-register').addEventListener('click', showRegister);
}
function showRegister() {
  authError.textContent = '';
  loginForm.classList.add('hidden');
  registerForm.classList.remove('hidden');
  authTitle.innerHTML = 'Create account<span>.</span>';
  authIntro.textContent = 'Your password is hashed with bcrypt on the local Java server.';
  authSwitch.innerHTML = 'Already registered? <button type="button" id="show-login">Sign in</button>';
  $('show-login').addEventListener('click', () => showAuth());
}
function showWorkspace(email) {
  authScreen.hidden = true;
  $('app-header').hidden = false;
  $('app-main').hidden = false;
  $('app-footer').hidden = false;
  $('account-email').textContent = email;
}
async function sendAuthForm(endpoint, form) {
  const values = new URLSearchParams(new FormData(form));
  values.delete('confirm');
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: values,
  });
  const message = await response.text();
  if (!response.ok) throw new Error(message || 'Authentication failed.');
  const account = JSON.parse(message);
  form.reset();
  showWorkspace(account.email);
}
loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  authError.textContent = '';
  try { await sendAuthForm('/api/login', loginForm); }
  catch (error) { authError.textContent = error.message || 'Sign in failed.'; }
});
registerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  authError.textContent = '';
  if ($('register-password').value !== $('register-confirm').value) {
    authError.textContent = 'The passwords do not match.';
    return;
  }
  try { await sendAuthForm('/api/register', registerForm); }
  catch (error) { authError.textContent = error.message || 'Account creation failed.'; }
});
$('show-register').addEventListener('click', showRegister);
$('logout-button').addEventListener('click', async () => {
  try { await fetch('/api/logout', { method: 'POST' }); } catch (error) { /* Clear local state even if the server has stopped. */ } finally {
    fileInput.value = '';
    $('decrypt-input').value = '';
    $('decrypt-file-label').textContent = 'Choose a .des file';
    $('decrypt-button').disabled = true;
    $('key-input').value = '';
    $('key-password').value = '';
    $('key-account-password').value = '';
    $('decrypt-key').value = '';
    $('decrypt-key').type = 'password';
    $('toggle-decrypt-key').textContent = 'SHOW';
    $('decrypt-account-password').value = '';
    $('decrypt-password').value = '';
    $('copy-key-button').disabled = true;
    setSelectedFile(null);
    decryptFile = null;
    encryptedBlob = null;
    decryptedBlob = null;
    decryptedName = null;
    decryptedType = null;
    $('selected-file').classList.add('hidden');
    $('encrypt-result').classList.add('hidden');
    $('decrypt-result').classList.add('hidden');
    showAuth();
  }
});

async function restoreSession() {
  try {
    const response = await fetch('/api/session', { cache: 'no-store' });
    const session = await response.json();
    if (session.authenticated) showWorkspace(session.email);
  } catch (error) {
    showAuth('Could not connect to the local server. Restart it and refresh this page.');
  }
}

const fileInput = $('file-input');
const keyInput = $('key-input');
const dropzone = $('dropzone');
let selectedFile = null;
let encryptedBlob = null;
let decryptedBlob = null;
let decryptFile = null;
let decryptedName = null;
let decryptedType = null;

async function deriveDesKey(accountPassword, encryptionPassword) {
  const username = $('account-email').textContent.trim().toLowerCase();
  if (!username) throw new Error('Sign in before generating a key.');
  if (accountPassword.length < 8) throw new Error('Enter your account password to confirm your identity.');
  if (new TextEncoder().encode(accountPassword).length > 72) throw new Error('Your account password must be no longer than 72 UTF-8 bytes.');
  if (encryptionPassword.length < 8) throw new Error('Use an encryption password with at least 8 characters.');
  if (accountPassword === encryptionPassword) throw new Error('Use a different encryption password from your account password.');
  if (!window.crypto || !window.crypto.subtle) throw new Error('Secure key derivation is unavailable in this browser. Open the app at http://localhost:8080.');

  const encoder = new TextEncoder();
  const combinedPasswords = JSON.stringify([accountPassword, encryptionPassword]);
  const material = await crypto.subtle.importKey('raw', encoder.encode(combinedPasswords), 'PBKDF2', false, ['deriveBits']);
  const salt = encoder.encode(`DES-USER-KEY-v1:${username}`);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' }, material, 512);
  const bytes = new Uint8Array(bits);
  const alphabetSize = 94; // Printable ASCII characters from ! through ~.
  const unbiasedLimit = Math.floor(256 / alphabetSize) * alphabetSize;
  let key = '';
  for (const byte of bytes) {
    if (byte < unbiasedLimit) key += String.fromCharCode(33 + (byte % alphabetSize));
    if (key.length === 8) return key;
  }
  throw new Error('Could not derive a key. Try again.');
}

async function verifyAccountPassword(password) {
  if (!password) throw new Error('Enter your account password for the second check.');
  const credentials = new URLSearchParams({ password });
  const response = await fetch('/api/reauth', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: credentials,
  });
  if (!response.ok) throw new Error(await response.text() || 'Account password verification failed.');
}

async function fillDerivedKey(accountPasswordInput, encryptionPasswordInput, keyField, button, defaultText) {
  button.disabled = true;
  button.textContent = 'Deriving…';
  showStatus('');
  try {
    const accountPassword = accountPasswordInput.value;
    const encryptionPassword = encryptionPasswordInput.value;
    if (encryptionPassword.length < 8) throw new Error('Use an encryption password with at least 8 characters.');
    if (accountPassword === encryptionPassword) throw new Error('Use a different encryption password from your account password.');
    await verifyAccountPassword(accountPassword);
    keyField.value = await deriveDesKey(accountPassword, encryptionPassword);
    keyField.dispatchEvent(new Event('input', { bubbles: true }));
    $('copy-key-button').disabled = false;
    accountPasswordInput.value = '';
    encryptionPasswordInput.value = '';
    showStatus('Second check passed. Keep both passwords to derive the same key for decryption.');
  } catch (error) {
    showStatus(error.message || 'Could not generate the key.', true);
  } finally {
    button.disabled = false;
    button.textContent = defaultText;
  }
}

function formatSize(bytes) {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
function setSelectedFile(file) {
  selectedFile = file || null;
  $('selected-file').classList.toggle('hidden', !file);
  dropzone.classList.toggle('has-file', !!file);
  if (file) {
    $('file-name').textContent = file.name;
    $('file-size').textContent = formatSize(file.size);
  }
  updateEncryptState();
}
function updateEncryptState() {
  const chars = keyInput.value.length;
  $('key-meter-bar').style.width = `${chars / 8 * 100}%`;
  $('key-meter-bar').classList.toggle('complete', chars === 8);
  $('key-hint').textContent = chars === 8 ? 'Key length ready. Keep this key for decryption.' : `Enter ${8 - chars} more character${8 - chars === 1 ? '' : 's'}.`;
  $('encrypt-button').disabled = !selectedFile || chars !== 8;
}
function encodeMetadataHeader(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}
function decodeMetadataHeader(value) {
  if (!value) return null;
  try {
    const base64 = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
    const binary = atob(base64);
    return new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));
  } catch (error) { return null; }
}
async function processFile(endpoint, file, key, metadata = null) {
  const headers = { 'Content-Type': 'application/octet-stream', 'X-Des-Key': key };
  if (metadata) {
    headers['X-Original-Name'] = encodeMetadataHeader(metadata.name);
    headers['X-Original-Type'] = encodeMetadataHeader(metadata.type || 'application/octet-stream');
  }
  const response = await fetch(endpoint, { method: 'POST', headers, body: file });
  if (response.status === 401) {
    showAuth('Your session expired. Sign in again.');
    throw new Error('Your session expired. Sign in again.');
  }
  if (!response.ok) throw new Error(await response.text());
  return {
    blob: await response.blob(),
    originalName: decodeMetadataHeader(response.headers.get('X-Original-Name')),
    originalType: decodeMetadataHeader(response.headers.get('X-Original-Type')),
  };
}
function download(blob, name, type = 'application/octet-stream') {
  const typedBlob = new Blob([blob], { type });
  const url = URL.createObjectURL(typedBlob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function showStatus(message, error = false) {
  const el = $('status-message');
  el.textContent = message;
  el.classList.toggle('error', error);
}
fileInput.addEventListener('change', () => setSelectedFile(fileInput.files[0]));
$('remove-file').addEventListener('click', () => { fileInput.value = ''; setSelectedFile(null); });
keyInput.addEventListener('input', () => {
  $('copy-key-button').disabled = true;
  updateEncryptState();
});
$('generate-key').addEventListener('click', () => fillDerivedKey($('key-account-password'), $('key-password'), keyInput, $('generate-key'), 'Generate key'));
$('copy-key-button').addEventListener('click', async () => {
  if (keyInput.value.length !== 8) return;
  try {
    await navigator.clipboard.writeText(keyInput.value);
    showStatus('DES key copied. Share it securely with the recipient.');
  } catch (error) {
    showStatus('Clipboard access failed. Reveal the key and copy it manually.', true);
  }
});
$('toggle-key').addEventListener('click', () => {
  keyInput.type = keyInput.type === 'password' ? 'text' : 'password';
  $('toggle-key').textContent = keyInput.type === 'password' ? 'SHOW' : 'HIDE';
});
$('toggle-decrypt-key').addEventListener('click', () => {
  const field = $('decrypt-key');
  field.type = field.type === 'password' ? 'text' : 'password';
  $('toggle-decrypt-key').textContent = field.type === 'password' ? 'SHOW' : 'HIDE';
});
for (const eventName of ['dragenter', 'dragover']) dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.add('dragging'); });
for (const eventName of ['dragleave', 'drop']) dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.remove('dragging'); });
dropzone.addEventListener('drop', (event) => setSelectedFile(event.dataTransfer.files[0]));
$('encrypt-button').addEventListener('click', async () => {
  if (!selectedFile || selectedFile.size > 25 * 1024 * 1024) return showStatus('Choose a file smaller than 25 MiB.', true);
  const button = $('encrypt-button'); button.disabled = true; button.classList.add('busy'); button.firstElementChild.textContent = 'Encrypting…';
  showStatus('');
  try {
    const encrypted = await processFile('/api/encrypt', selectedFile, keyInput.value, { name: selectedFile.name, type: selectedFile.type });
    encryptedBlob = encrypted.blob;
    $('encrypt-result-detail').textContent = `${selectedFile.name} · ${formatSize(selectedFile.size)} encrypted with DES-CBC.`;
    $('encrypt-result').classList.remove('hidden');
    $('encrypt-result').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (error) { showStatus(error.message || 'Encryption failed. Check that the local Java server is running.', true); }
  finally { button.classList.remove('busy'); button.firstElementChild.textContent = 'Encrypt document'; updateEncryptState(); }
});
$('download-encrypted').addEventListener('click', () => { if (encryptedBlob && selectedFile) download(encryptedBlob, `${selectedFile.name}.des`); });
$('decrypt-input').addEventListener('change', (event) => {
  decryptFile = event.target.files[0] || null;
  $('decrypt-file-label').textContent = decryptFile ? decryptFile.name : 'Choose a .des file';
  updateDecryptState();
});
function updateDecryptState() {
  $('decrypt-button').disabled = !decryptFile || $('decrypt-key').value.length !== 8 ||
    $('decrypt-account-password').value.length < 8 || $('decrypt-password').value.length < 8;
}
$('decrypt-key').addEventListener('input', updateDecryptState);
$('decrypt-account-password').addEventListener('input', updateDecryptState);
$('decrypt-password').addEventListener('input', updateDecryptState);
$('decrypt-button').addEventListener('click', async () => {
  if (!decryptFile || decryptFile.size > 25 * 1024 * 1024 + 4096 + 32) return showStatus('Choose a .des file created from a document smaller than 25 MiB.', true);
  const button = $('decrypt-button'); button.disabled = true; button.innerHTML = 'Decrypting…'; showStatus('');
  try {
    const accountPassword = $('decrypt-account-password').value;
    const encryptionPassword = $('decrypt-password').value;
    const enteredKey = $('decrypt-key').value;
    if (accountPassword === encryptionPassword) throw new Error('Use the same two different passwords used when generating this key.');
    await verifyAccountPassword(accountPassword);
    const expectedKey = await deriveDesKey(accountPassword, encryptionPassword);
    if (enteredKey !== expectedKey) throw new Error('The account password, encryption password, and DES key do not match the encryption inputs.');
    const recovered = await processFile('/api/decrypt', decryptFile, $('decrypt-key').value);
    decryptedBlob = recovered.blob;
    decryptedName = recovered.originalName;
    decryptedType = recovered.originalType;
    $('decrypt-account-password').value = '';
    $('decrypt-password').value = '';
    $('decrypt-result-detail').textContent = decryptedName ? `Recovered ${decryptedName} (${decryptedType}).` : 'Decryption completed. Save and inspect the recovered document.';
    $('decrypt-result').classList.remove('hidden');
    $('decrypt-result').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (error) { showStatus(error.message || 'Decryption failed. Check the key and selected file.', true); }
  finally { button.innerHTML = 'Decrypt file <span>→</span>'; updateDecryptState(); }
});
$('download-decrypted').addEventListener('click', () => {
  if (!decryptedBlob || !decryptFile) return;
  const fallbackName = decryptFile.name.toLowerCase().endsWith('.des') ? decryptFile.name.slice(0, -4) : `${decryptFile.name}.recovered`;
  download(decryptedBlob, decryptedName || fallbackName, decryptedType || 'application/octet-stream');
});
restoreSession();
