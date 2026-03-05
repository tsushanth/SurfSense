const BACKEND_BASE = "https://usage-tracker-backend-917362189743.us-central1.run.app";

// --- Auth helper: get stored API key ---
async function getAuthHeaders() {
  const { apiKey } = await chrome.storage.local.get("apiKey");
  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) {
    headers['Authorization'] = `Bearer ${apiKey}`;
  }
  return headers;
}

function getExtensionId() {
  return new Promise((resolve) => {
    chrome.storage.local.get("extensionId", (result) => {
      resolve(result.extensionId);
    });
  });
}

// --- Device linking ---
function loadLinkedDevices() {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage({ action: "getLinkedDevices" }, (response) => {
      if (response?.success) {
        renderLinkedDevices(response.devices || []);
        resolve(response.devices || []);
      } else {
        resolve([]);
      }
    });
  });
}

async function handleUnlinkDevice(event) {
  try {
    const { extensionId: currentClientId } = await chrome.storage.local.get("extensionId");
    const targetClientId = event.target.dataset.deviceId;

    if (!currentClientId || !targetClientId) {
      throw new Error("Missing client IDs for unlinking");
    }

    const headers = await getAuthHeaders();
    const response = await fetch(`${BACKEND_BASE}/unlink-device`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        clientIdA: currentClientId,
        clientIdB: targetClientId
      })
    });

    if (!response.ok) {
      const errorData = await response.json();
      throw new Error(errorData.error || "Failed to unlink devices");
    }

    const devices = await loadLinkedDevices();
    renderLinkedDevices(devices);
    showToast("Device unlinked successfully", "success");
  } catch (error) {
    showToast(`Unlink failed: ${error.message}`, "error");
  }
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

// --- Top 5 sites ---
chrome.storage.local.get("timeData", (result) => {
  const data = result.timeData || {};
  const sorted = Object.entries(data).sort((a, b) => b[1] - a[1]).slice(0, 5);
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
});

function formatTime(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

// --- Device linking UI ---
const generateCodeBtn = document.getElementById('generateCodeBtn');
const linkingCodeContainer = document.getElementById('linkingCodeContainer');
const linkingCodeEl = document.getElementById('linkingCode');

generateCodeBtn.addEventListener('click', () => {
  generateCodeBtn.disabled = true;
  generateCodeBtn.textContent = "Generating...";
  chrome.runtime.sendMessage({ action: "generateLinkingCode" }, (response) => {
    generateCodeBtn.disabled = false;
    generateCodeBtn.textContent = "Generate Linking Code";
    if (response?.success) {
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
      showToast("Failed to generate code", "error");
    }
  });
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
  try {
    const code = document.getElementById('otherDeviceCode').value.trim();
    const deviceName = document.getElementById('otherDeviceName').value.trim();
    const { extensionId: clientId } = await chrome.storage.local.get("extensionId");

    // Validate code is exactly 6 numeric digits
    if (!code || !/^\d{6}$/.test(code)) {
      showToast("Please enter a valid 6-digit numeric code", "error");
      return;
    }

    showToast("Linking device...", "info");

    const headers = await getAuthHeaders();
    const response = await fetch(`${BACKEND_BASE}/complete-linking`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ clientId, code, deviceName })
    });

    const result = await response.json();

    if (!response.ok || !result.success) {
      throw new Error(result.error || "Failed to link devices");
    }

    showToast("Device linked successfully!", "success");

    document.getElementById('addDeviceForm').style.display = 'none';
    document.getElementById('otherDeviceCode').value = '';
    document.getElementById('otherDeviceName').value = '';

    const devices = await loadLinkedDevices();
    renderLinkedDevices(devices);
  } catch (error) {
    showToast(`Linking failed: ${error.message}`, "error");
  }
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
  try {
    const today = new Date().toISOString().split("T")[0];
    const userId = await getExtensionId();
    const headers = await getAuthHeaders();
    const url = `${BACKEND_BASE}/get-summary-history?day=${today}&userId=${userId}`;

    const res = await fetch(url, { headers });
    if (!res.ok) return {};

    const data = await res.json();

    if (Array.isArray(data)) {
      const todaySummary = data.find((s) => s.timestamp.startsWith(today));
      return todaySummary?.summary || {};
    }
    return {};
  } catch (err) {
    return {};
  }
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

    const devices = await loadLinkedDevices();
    if (!Array.isArray(devices) || devices.length === 0) return;

    const today = new Date().toISOString().split('T')[0];
    const headers = await getAuthHeaders();

    // Fetch all device data in parallel
    const fetchPromises = devices
      .filter(d => d.clientId)
      .map(async (device) => {
        try {
          const url = `${BACKEND_BASE}/get-summary-history?userId=${device.clientId}&day=${today}`;
          const response = await fetch(url, { headers });
          if (!response.ok) return null;

          const summaries = await response.json();
          const todaySummary = summaries.find(s => s.timestamp?.startsWith(today));
          return todaySummary ? { device, summary: todaySummary.summary } : null;
        } catch {
          return null;
        }
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
loadLinkedDevices();
renderAllDeviceCharts();

// --- Refresh button ---
document.getElementById("refreshButton").addEventListener("click", () => {
  chrome.runtime.sendMessage({ action: "manualUpdateUsage" }, (response) => {
    if (chrome.runtime.lastError) {
      showToast("Failed to update usage.", "error");
    } else {
      showToast("Usage data synced.", "success");
      renderCategoryChart();
    }
  });
});
