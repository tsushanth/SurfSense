const VALID_CLIENT_TYPES = ['CHROME_EXTENSION', 'IOS', 'ANDROID', 'UNKNOWN'];

/** The client type to store. Android builds older than 10.0.0 register as ANDROID_PHONE. */
export function normalizeClientType(type) {
    if (type === 'ANDROID_PHONE') return 'ANDROID';
    return VALID_CLIENT_TYPES.includes(type) ? type : 'UNKNOWN';
}
