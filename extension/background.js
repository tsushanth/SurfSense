let activeTabId = null;
let activeStartTime = null;
const BACKEND_BASE = "https://usage-tracker-backend-917362189743.us-central1.run.app";
let lastUsedDate = new Date().toISOString().split("T")[0];

// --- Auth helper ---
async function getAuthHeaders() {
  const { apiKey } = await chrome.storage.local.get("apiKey");
  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) {
    headers['Authorization'] = `Bearer ${apiKey}`;
  }
  return headers;
}

// Track tab changes
chrome.tabs.onActivated.addListener(({ tabId }) => handleTabSwitch(tabId));

// Track window focus changes
chrome.windows.onFocusChanged.addListener((windowId) => {
  if (windowId === chrome.windows.WINDOW_ID_NONE) {
    stopTracking();
  } else {
    chrome.tabs.query({ active: true, windowId }, (tabs) => {
      if (tabs[0]) handleTabSwitch(tabs[0].id);
    });
  }
});

// Register client on install
chrome.runtime.onInstalled.addListener(() => {
  chrome.runtime.getPlatformInfo((info) => {
    const os = info.os;
    const arch = info.arch;
    const clientName = `${os}-${arch}-${new Date().toISOString().split("T")[0]}`;

    chrome.storage.local.get("extensionId", async (result) => {
      if (!result.extensionId) {
        const generatedId = crypto.randomUUID();
        chrome.storage.local.set({ extensionId: generatedId });

        try {
          // Use new POST /register-client endpoint
          const res = await fetch(`${BACKEND_BASE}/register-client`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              clientId: generatedId,
              clientType: "CHROME_EXTENSION",
              clientName: clientName
            })
          });

          if (res.ok) {
            const data = await res.json();
            // Store API key if returned (first registration)
            if (data.apiKey) {
              chrome.storage.local.set({ apiKey: data.apiKey });
            }
          }
        } catch (err) {
          // Silent failure — will retry on next install event
        }
      }
    });
  });
});

function getExtensionId(callback) {
  chrome.storage.local.get("extensionId", (result) => {
    callback(result.extensionId);
  });
}

// Track tab updates (like page reload)
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (tab.active && changeInfo.status === 'complete') {
    handleTabSwitch(tabId);
  }
});

function handleTabSwitch(tabId) {
  stopTracking();
  activeTabId = tabId;
  activeStartTime = Date.now();
}

function stopTracking() {
  if (!activeTabId || !activeStartTime) return;

  const now = new Date();
  const today = now.toISOString().split("T")[0];
  const timeSpent = Math.floor((Date.now() - activeStartTime) / 1000);

  chrome.tabs.get(activeTabId, (tab) => {
    if (!tab || !tab.url) return;
    try {
      const domain = new URL(tab.url).hostname.replace(/^www\./, "");
      if (!domain) return;

      chrome.storage.local.get(["timeData"], async (result) => {
        const timeData = result.timeData || {};
        timeData[domain] = (timeData[domain] || 0) + timeSpent;

        if (today !== lastUsedDate) {
          await sendDailySummary(timeData);
          chrome.storage.local.set({ timeData: {} });
          lastUsedDate = today;
        } else {
          chrome.storage.local.set({ timeData });
        }
      });
    } catch {
      // Invalid URL — skip
    }
  });

  activeStartTime = null;
  activeTabId = null;
}

async function sendDailySummary(timeData) {
  const domains = Object.keys(timeData);
  if (domains.length === 0) return;

  try {
    const headers = await getAuthHeaders();

    // 1. Get category mapping
    const res = await fetch(`${BACKEND_BASE}/get-category-mapping`, {
      method: "POST",
      headers,
      body: JSON.stringify({ domains })
    });

    const categoryMap = await res.json();

    // 2. Aggregate time by category
    const newCategorySummary = {};
    for (const domain of domains) {
      const category = categoryMap[domain] || "Uncategorized";
      newCategorySummary[category] = (newCategorySummary[category] || 0) + timeData[domain];
    }

    // 3. Fetch previous summary and merge
    const today = new Date().toISOString().split("T")[0];
    getExtensionId(async (extensionId) => {
      const summaryRes = await fetch(
        `${BACKEND_BASE}/get-summary-history?day=${today}&userId=${extensionId}`,
        { headers }
      );

      let existingSummary = {};
      if (summaryRes.ok) {
        const summaryList = await summaryRes.json();
        const todayEntry = summaryList.find(s => s.timestamp.startsWith(today));
        if (todayEntry && todayEntry.summary) {
          existingSummary = todayEntry.summary;
        }
      }

      // 4. Merge summaries
      const mergedSummary = { ...existingSummary };
      for (const [category, seconds] of Object.entries(newCategorySummary)) {
        mergedSummary[category] = (mergedSummary[category] || 0) + seconds;
      }

      // 5. Send to backend
      const response = await fetch(`${BACKEND_BASE}/submit-category-summary`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          userId: extensionId,
          categorySummary: mergedSummary
        })
      });

      if (response.ok) {
        chrome.storage.local.set({ timeData: {} });
      }
    });
  } catch (err) {
    // Silent failure — data preserved locally for next attempt
  }
}

// Save backup every minute to avoid data loss
setInterval(() => {
  stopTracking();
}, 60000);

async function checkAndSendUsage(force = false) {
  chrome.storage.local.get(["timeData"], async (result) => {
    const usageData = result.timeData || {};
    if (Object.keys(usageData).length === 0) return;
    await sendDailySummary(usageData);
  });
}

// Message handler
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === "manualUpdateUsage") {
    checkAndSendUsage(true).then(() => sendResponse({ success: true }));
    return true;
  }

  switch (message.action) {
    case "generateLinkingCode":
      generateLinkingCode()
        .then(code => sendResponse({ success: true, code }))
        .catch(error => sendResponse({ success: false, error: error.message }));
      return true;

    case "unlinkDevice":
      unlinkDevice(message.deviceId)
        .then(() => sendResponse({ success: true }))
        .catch(error => sendResponse({ success: false, error: error.message }));
      return true;

    case "getLinkedDevices":
      loadLinkedDevices()
        .then(devices => sendResponse({ success: true, devices }))
        .catch(error => sendResponse({ success: false, error: error.message }));
      return true;
  }
});

async function generateLinkingCode() {
  const { extensionId: clientId } = await chrome.storage.local.get("extensionId");
  if (!clientId) throw new Error("No clientId found in storage");

  const headers = await getAuthHeaders();
  const response = await fetch(`${BACKEND_BASE}/initiate-linking`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ clientId })
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Server responded with ${response.status}`);
  }

  const { code } = await response.json();
  return code;
}

async function unlinkDevice(deviceId) {
  const { extensionId: clientId } = await chrome.storage.local.get("extensionId");
  const headers = await getAuthHeaders();

  const response = await fetch(`${BACKEND_BASE}/unlink-device`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      clientIdA: clientId,
      clientIdB: deviceId
    })
  });

  if (!response.ok) {
    throw new Error("Failed to unlink device");
  }
}

async function loadLinkedDevices() {
  const { extensionId: clientId } = await chrome.storage.local.get("extensionId");
  const headers = await getAuthHeaders();

  const response = await fetch(`${BACKEND_BASE}/get-linked-clients?clientId=${clientId}`, {
    method: 'GET',
    headers
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const result = await response.json();

  if (result.success && result.data) {
    return result.data.clients || [];
  }
  return [];
}
