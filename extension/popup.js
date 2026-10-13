import { localDate } from './lib/dates.js';
import { ACTIONS } from './lib/messages.js';

// The popup never touches the network: everything goes through the background worker.
function send(action, payload = {}) {
  return new Promise((resolve) => {
    const unreachable = { success: false, error: 'Could not reach the extension. Close and reopen this popup.' };
    try {
      chrome.runtime.sendMessage({ action, ...payload }, (response) => {
        if (chrome.runtime.lastError || !response) resolve(unreachable);
        else resolve(response);
      });
    } catch {
      resolve(unreachable);
    }
  });
}

// Shows a failed response's server/friendly error text; identical messages inside the toast
// lifetime are shown once so several parallel failures (e.g. offline) don't stack up.
let lastErrorShown = { text: '', at: 0 };
function showError(prefix, response) {
  const text = `${prefix}${response?.error || 'Something went wrong.'}`;
  if (text === lastErrorShown.text && Date.now() - lastErrorShown.at < 3000) return;
  lastErrorShown = { text, at: Date.now() };
  showToast(text, "error");
}

async function getExtensionId() {
  const r = await send(ACTIONS.GET_IDENTITY);
  return r.success ? r.extensionId : null;
}

// --- Device linking ---
async function loadLinkedDevices({ report = false } = {}) {
  const response = await send(ACTIONS.GET_LINKED_DEVICES);
  if (response.success) {
    renderLinkedDevices(response.devices || []);
    return response.devices || [];
  }
  if (report) showError("", response);
  return [];
}

async function handleUnlinkDevice(event) {
  const targetClientId = event.target.dataset.deviceId;
  if (!targetClientId) {
    showToast("Unlink failed: Missing device ID", "error");
    return;
  }
  const response = await send(ACTIONS.UNLINK_DEVICE, { deviceId: targetClientId });
  if (!response.success) {
    showError("Unlink failed: ", response);
    return;
  }
  await loadLinkedDevices();
  showToast("Device unlinked successfully", "success");
}

// --- Safe DOM rendering (no innerHTML with untrusted data) ---
function renderLinkedDevices(devices) {
  const linkedDevicesEl = document.getElementById('linkedDevices');
  linkedDevicesEl.textContent = '';

  const formatDeviceType = (type) => {
    const types = {
      'CHROME_EXTENSION': 'Chrome',
      'IOS': 'iPhone',
      'ANDROID': 'Android'
    };
    return types[type] || type;
  };

  devices.forEach(device => {
    const deviceEl = document.createElement('div');
    deviceEl.className = 'device-item';

    const infoDiv = document.createElement('div');
    infoDiv.className = 'device-info';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'device-name';
    nameSpan.textContent = device.clientName
      ? `${device.clientName} (${formatDeviceType(device.clientType)})`
      : `${formatDeviceType(device.clientType)} Device`;

    const idSpan = document.createElement('span');
    idSpan.className = 'device-id';
    idSpan.textContent = `ID: ${device.clientId.substring(0, 8)}...`;

    infoDiv.appendChild(nameSpan);
    infoDiv.appendChild(idSpan);

    const unlinkBtn = document.createElement('button');
    unlinkBtn.className = 'unlink-btn';
    unlinkBtn.dataset.deviceId = device.clientId;
    unlinkBtn.textContent = 'Unlink';
    unlinkBtn.addEventListener('click', handleUnlinkDevice);

    deviceEl.appendChild(infoDiv);
    deviceEl.appendChild(unlinkBtn);
    linkedDevicesEl.appendChild(deviceEl);
  });
}

// --- Top sites (live local totals for today, from the background tracker) ---
async function renderTopSites() {
  const response = await send(ACTIONS.GET_TODAY);
  if (!response.success) {
    showError("", response);
    return;
  }
  const data = response.today || {};
  const sorted = Object.entries(data).sort((a, b) => b[1] - a[1]).slice(0, 10);
  const list = document.getElementById("siteList");
  list.textContent = "";

  if (sorted.length === 0) {
    const li = document.createElement("li");
    li.textContent = "No data yet";
    list.appendChild(li);
  } else {
    sorted.forEach(([domain, time]) => {
      const li = document.createElement("li");
      const link = document.createElement("a");
      link.href = `https://${domain}`;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = domain;
      li.appendChild(link);
      li.append(`: ${formatTime(time)}`);
      list.appendChild(li);
    });
  }
}

function formatTime(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

// --- Device linking UI ---
const generateCodeBtn = document.getElementById('generateCodeBtn');
const linkingCodeContainer = document.getElementById('linkingCodeContainer');
const linkingCodeEl = document.getElementById('linkingCode');

generateCodeBtn.addEventListener('click', async () => {
  generateCodeBtn.disabled = true;
  generateCodeBtn.textContent = "Generating...";
  const response = await send(ACTIONS.GENERATE_LINKING_CODE);
  generateCodeBtn.disabled = false;
  generateCodeBtn.textContent = "Generate Linking Code";
  if (response.success) {
    linkingCodeEl.textContent = response.code;
    linkingCodeContainer.style.display = 'block';

    // Poll for linked devices with timeout (max 5 minutes)
    let pollCount = 0;
    const maxPolls = 100;
    const pollInterval = setInterval(() => {
      pollCount++;
      if (pollCount >= maxPolls) {
        clearInterval(pollInterval);
        linkingCodeContainer.style.display = 'none';
        showToast("Linking code expired. Generate a new one.", "info");
        return;
      }
      loadLinkedDevices().then(devices => {
        if (devices && devices.length > 0) {
          clearInterval(pollInterval);
          linkingCodeContainer.style.display = 'none';
        }
      });
    }, 3000);
  } else {
    showError("Failed to generate code: ", response);
  }
});

// --- Add device form ---
document.addEventListener('DOMContentLoaded', () => {
  const addDeviceBtn = document.getElementById('addOtherDeviceBtn');
  const addDeviceForm = document.getElementById('addDeviceForm');
  const submitBtn = document.getElementById('submitOtherDevice');

  addDeviceBtn.addEventListener('click', () => {
    addDeviceForm.style.display = addDeviceForm.style.display === 'none' ? 'block' : 'none';
  });

  submitBtn.addEventListener('click', handleAddDevice);
});

async function handleAddDevice() {
  const code = document.getElementById('otherDeviceCode').value.trim();

  // Validate code is exactly 6 numeric digits
  if (!code || !/^\d{6}$/.test(code)) {
    showToast("Please enter a valid 6-digit numeric code", "error");
    return;
  }

  showToast("Linking device...", "info");

  const response = await send(ACTIONS.COMPLETE_LINKING, { code });
  if (!response.success) {
    showError("Linking failed: ", response);
    return;
  }

  showToast("Device linked successfully!", "success");

  document.getElementById('addDeviceForm').style.display = 'none';
  document.getElementById('otherDeviceCode').value = '';
  document.getElementById('otherDeviceName').value = '';

  await loadLinkedDevices();
  renderAllDeviceCharts();
}

// --- Toast notifications ---
function showToast(message, type = "info") {
  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 3000);
}

// --- Category chart ---
async function fetchCategorySummary() {
  const today = localDate(Date.now());
  const userId = await getExtensionId();
  if (!userId) return {};
  const response = await send(ACTIONS.GET_SUMMARY, { userId, day: today });
  if (!response.success) {
    showError("", response);
    return {};
  }
  return response.summary || {};
}

async function renderCategoryChart() {
  const summary = await fetchCategorySummary();

  const ctx = document.getElementById("categoryChart").getContext("2d");
  const labels = Object.keys(summary);
  const values = Object.values(summary);

  if (window.categoryChartInstance) {
    window.categoryChartInstance.destroy();
  }

  window.categoryChartInstance = new Chart(ctx, {
    type: "pie",
    data: {
      labels,
      datasets: [{
        label: "Time by Category (min)",
        data: values.map(sec => (sec / 60).toFixed(1)),
        backgroundColor: [
          "#4caf50", "#2196f3", "#ff9800", "#9c27b0", "#f44336", "#3f51b5",
          "#009688", "#673ab7", "#ff5722", "#607d8b"
        ]
      }]
    },
    options: {
      responsive: true,
      plugins: {
        legend: { position: "bottom" }
      }
    }
  });
}

async function renderAllDeviceCharts() {
  try {
    await renderCategoryChart();

    const devices = await loadLinkedDevices({ report: true });
    if (!Array.isArray(devices) || devices.length === 0) return;

    const today = localDate(Date.now());

    // Fetch all device data in parallel
    const fetchPromises = devices
      .filter(d => d.clientId)
      .map(async (device) => {
        const response = await send(ACTIONS.GET_SUMMARY, { userId: device.clientId, day: today });
        return response.success && response.summary ? { device, summary: response.summary } : null;
      });

    const results = await Promise.all(fetchPromises);
    results.filter(Boolean).forEach(({ device, summary }) => {
      renderDeviceChart(device, summary);
    });
  } catch (err) {
    // Silent failure for chart rendering
  }
}

function renderDeviceChart(device, summary) {
  const container = document.getElementById('otherDevicesUsage');
  const chartId = `chart-${device.clientId}`;

  // Create chart container safely (no innerHTML with untrusted data)
  if (!document.getElementById(chartId)) {
    const chartContainer = document.createElement('div');
    chartContainer.className = 'device-chart-container';

    const heading = document.createElement('h4');
    heading.textContent = `${device.clientName || 'Unknown'} (${device.clientType})`;

    const canvas = document.createElement('canvas');
    canvas.id = chartId;
    canvas.width = 200;
    canvas.height = 200;

    chartContainer.appendChild(heading);
    chartContainer.appendChild(canvas);
    container.appendChild(chartContainer);
  }

  const ctx = document.getElementById(chartId).getContext('2d');
  const labels = Object.keys(summary);
  const values = Object.values(summary);

  if (window.deviceCharts && window.deviceCharts[device.clientId]) {
    window.deviceCharts[device.clientId].destroy();
  }

  window.deviceCharts = window.deviceCharts || {};
  window.deviceCharts[device.clientId] = new Chart(ctx, {
    type: "pie",
    data: {
      labels,
      datasets: [{
        label: "Time by Category (min)",
        data: values.map(sec => (sec / 60).toFixed(1)),
        backgroundColor: [
          "#4caf50", "#2196f3", "#ff9800", "#9c27b0", "#f44336", "#3f51b5",
          "#009688", "#673ab7", "#ff5722", "#607d8b"
        ]
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: "bottom", labels: { boxWidth: 12 } }
      }
    }
  });
}

// --- Dynamic styles for device charts ---
const style = document.createElement('style');
style.textContent = `
  .device-chart-container {
    margin: 20px 0;
    padding: 15px;
    background: white;
    border-radius: 8px;
    box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  }
  .device-chart-container h4 {
    margin: 0 0 10px 0;
    font-size: 14px;
    color: #333;
  }
  .device-chart-container canvas {
    max-height: 250px;
  }
  .device-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px;
    margin: 4px 0;
    background: #f9f9f9;
    border-radius: 4px;
  }
  .device-info {
    display: flex;
    flex-direction: column;
  }
  .device-name {
    font-weight: 600;
    font-size: 13px;
  }
  .device-id {
    font-size: 11px;
    color: #888;
  }
  .unlink-btn {
    background: #f44336;
    color: white;
    border: none;
    border-radius: 4px;
    padding: 4px 8px;
    font-size: 11px;
    cursor: pointer;
  }
  .unlink-btn:hover {
    background: #d32f2f;
  }
`;
document.head.appendChild(style);

// --- Initialize ---
renderTopSites();
renderAllDeviceCharts();

// --- Refresh button ---
document.getElementById("refreshButton").addEventListener("click", async () => {
  const response = await send(ACTIONS.MANUAL_UPDATE);
  if (!response.success) {
    showError("Failed to update usage: ", response);
    return;
  }
  if (response.failed > 0) {
    showToast("Some usage could not be synced yet. It will retry automatically.", "error");
  } else {
    showToast("Usage data synced.", "success");
  }
  renderTopSites();
  renderAllDeviceCharts();
});
