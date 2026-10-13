const $ = (id) => document.getElementById(id);
const fileInput = $('file-input');
const keyInput = $('key-input');
const dropzone = $('dropzone');
let selectedFile = null;
let encryptedBlob = null;
let decryptedBlob = null;
let decryptFile = null;

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
async function processFile(endpoint, file, key) {
  const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream', 'X-Des-Key': key }, body: file });
  if (!response.ok) throw new Error(await response.text());
  return response.blob();
}
function download(blob, name) {
  const url = URL.createObjectURL(blob);
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
keyInput.addEventListener('input', updateEncryptState);
$('toggle-key').addEventListener('click', () => {
  keyInput.type = keyInput.type === 'password' ? 'text' : 'password';
  $('toggle-key').textContent = keyInput.type === 'password' ? 'SHOW' : 'HIDE';
});
for (const eventName of ['dragenter', 'dragover']) dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.add('dragging'); });
for (const eventName of ['dragleave', 'drop']) dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.remove('dragging'); });
dropzone.addEventListener('drop', (event) => setSelectedFile(event.dataTransfer.files[0]));
$('encrypt-button').addEventListener('click', async () => {
  if (!selectedFile || selectedFile.size > 25 * 1024 * 1024) return showStatus('Choose a file smaller than 25 MiB.', true);
  const button = $('encrypt-button'); button.disabled = true; button.classList.add('busy'); button.firstElementChild.textContent = 'Encrypting…';
  showStatus('');
  try {
    encryptedBlob = await processFile('/api/encrypt', selectedFile, keyInput.value);
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
  $('decrypt-button').disabled = !decryptFile || $('decrypt-key').value.length !== 8;
});
$('decrypt-key').addEventListener('input', () => { $('decrypt-button').disabled = !decryptFile || $('decrypt-key').value.length !== 8; });
$('decrypt-button').addEventListener('click', async () => {
  if (!decryptFile || decryptFile.size > 25 * 1024 * 1024 + 32) return showStatus('Choose a .des file created from a document smaller than 25 MiB.', true);
  const button = $('decrypt-button'); button.disabled = true; button.innerHTML = 'Decrypting…'; showStatus('');
  try {
    decryptedBlob = await processFile('/api/decrypt', decryptFile, $('decrypt-key').value);
    $('decrypt-result-detail').textContent = 'Decryption completed. Save and inspect the recovered document.';
    $('decrypt-result').classList.remove('hidden');
    $('decrypt-result').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (error) { showStatus(error.message || 'Decryption failed. Check the key and selected file.', true); }
  finally { button.innerHTML = 'Decrypt file <span>→</span>'; button.disabled = !decryptFile || $('decrypt-key').value.length !== 8; }
});
$('download-decrypted').addEventListener('click', () => {
  if (!decryptedBlob || !decryptFile) return;
  const name = decryptFile.name.toLowerCase().endsWith('.des') ? decryptFile.name.slice(0, -4) : `${decryptFile.name}.recovered`;
  download(decryptedBlob, name);
});
