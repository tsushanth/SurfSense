// Action names for popup -> background messages (chrome.runtime.sendMessage).
export const ACTIONS = Object.freeze({
  MANUAL_UPDATE: 'manualUpdateUsage',
  GET_TODAY: 'getToday',
  GENERATE_LINKING_CODE: 'generateLinkingCode',
  COMPLETE_LINKING: 'completeLinking',
  UNLINK_DEVICE: 'unlinkDevice',
  GET_LINKED_DEVICES: 'getLinkedDevices',
  GET_SUMMARY: 'getSummary',
  GET_IDENTITY: 'getIdentity',
});
