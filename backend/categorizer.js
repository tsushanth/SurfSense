/**
 * Domain/Package Categorizer
 *
 * Tiered approach:
 * 1. Check Supabase cache
 * 2. Pattern matching (free)
 * 3. Static database lookup (free)
 * 4. GPT-3.5-turbo fallback (cheap)
 */

// Pattern-based categorization
const CATEGORY_PATTERNS = {
    'Social Media': {
        domains: [/facebook|instagram|twitter|tiktok|snapchat|linkedin|pinterest|reddit|tumblr|whatsapp|telegram|discord|mastodon|threads|bluesky|wechat|weibo|vk\.com/i],
        packages: [/facebook|instagram|twitter|tiktok|snapchat|linkedin|pinterest|reddit|tumblr|whatsapp|telegram|discord|threads|messenger/i]
    },
    'Entertainment': {
        domains: [/youtube|netflix|hulu|disney|hbo|spotify|twitch|vimeo|dailymotion|soundcloud|pandora|deezer|tidal|primevideo|peacock|paramount|crunchyroll|funimation|plex|roku|apple.*tv|music\.|video\.|stream|gaming|game|play\./i],
        packages: [/youtube|netflix|hulu|disney|hbo|spotify|twitch|vimeo|soundcloud|pandora|music|video|player|game|gaming|entertainment/i]
    },
    'Work/Productivity': {
        domains: [/github|gitlab|bitbucket|stackoverflow|slack|notion|trello|asana|jira|confluence|zoom|teams|webex|figma|canva|dropbox|drive\.google|docs\.google|sheets\.google|office|outlook|calendar|mail\.|email|workspace|salesforce|hubspot|zendesk|freshdesk|clickup|monday\.com|basecamp|airtable|miro|linear|vercel|netlify|aws\.|azure|cloudflare|heroku|digitalocean/i],
        packages: [/slack|notion|trello|asana|jira|zoom|teams|webex|figma|dropbox|drive|docs|sheets|office|outlook|calendar|mail|email|workspace|salesforce|hubspot|productivity|work|business|enterprise/i]
    },
    'Shopping': {
        domains: [/amazon|ebay|walmart|target|bestbuy|etsy|shopify|aliexpress|alibaba|wish|shein|zalando|asos|zara|hm\.com|nike|adidas|costco|kroger|instacart|doordash|ubereats|grubhub|postmates|shop\.|store\.|buy\.|cart|checkout|ecommerce/i],
        packages: [/amazon|ebay|walmart|target|bestbuy|etsy|aliexpress|wish|shein|shop|store|buy|cart|ecommerce|retail|market/i]
    },
    'Education': {
        domains: [/coursera|udemy|edx|khan.*academy|duolingo|quizlet|chegg|studocu|brainly|wikipedia|britannica|edu\.|\.edu|university|college|school|learn|study|tutorial|course|lecture|academic|research|scholar\.google/i],
        packages: [/coursera|udemy|edx|khan|duolingo|quizlet|chegg|wikipedia|learn|study|education|school|university|college|tutorial|course/i]
    },
    'News': {
        domains: [/cnn|bbc|nytimes|washingtonpost|theguardian|reuters|apnews|npr|foxnews|msnbc|nbcnews|abcnews|cbsnews|usatoday|wsj|bloomberg|forbes|techcrunch|theverge|wired|arstechnica|engadget|mashable|huffpost|buzzfeed.*news|news\.|daily|times|post|herald|tribune|gazette|journal/i],
        packages: [/cnn|bbc|nytimes|guardian|reuters|news|daily|times|post|journal/i]
    },
    'Finance': {
        domains: [/paypal|venmo|cashapp|zelle|mint|ynab|quickbooks|turbotax|robinhood|coinbase|binance|kraken|fidelity|schwab|vanguard|etrade|ameritrade|bank|chase|wellsfargo|bofa|citi|capital.*one|discover|amex|visa|mastercard|crypto|bitcoin|trading|invest|stock|finance|money/i],
        packages: [/paypal|venmo|cashapp|zelle|mint|bank|chase|wellsfargo|citi|capital.*one|crypto|bitcoin|trading|invest|stock|finance|money|wallet/i]
    },
    'Health': {
        domains: [/webmd|mayoclinic|healthline|medscape|nih\.gov|cdc\.gov|who\.int|fitbit|myfitnesspal|strava|peloton|headspace|calm|betterhelp|talkspace|teladoc|zocdoc|health|medical|doctor|hospital|clinic|pharmacy|fitness|workout|exercise|meditation|wellness/i],
        packages: [/fitbit|myfitnesspal|strava|peloton|headspace|calm|health|medical|fitness|workout|exercise|meditation|wellness/i]
    },
    'Travel': {
        domains: [/booking|expedia|airbnb|vrbo|hotels|tripadvisor|kayak|skyscanner|google.*flights|united|delta|american.*airlines|southwest|jetblue|uber|lyft|maps\.google|waze|travel|flight|hotel|vacation|trip/i],
        packages: [/booking|expedia|airbnb|hotels|tripadvisor|uber|lyft|maps|waze|travel|flight|hotel|vacation|trip|airline/i]
    },
    'Food': {
        domains: [/doordash|ubereats|grubhub|postmates|instacart|seamless|yelp|opentable|allrecipes|epicurious|foodnetwork|tasty|food|recipe|restaurant|delivery|dining|eat/i],
        packages: [/doordash|ubereats|grubhub|postmates|instacart|yelp|food|recipe|restaurant|delivery|dining|eat/i]
    }
};

// Static database of common domains
const STATIC_DOMAINS = {
    // Social Media
    'facebook.com': 'Social Media',
    'instagram.com': 'Social Media',
    'twitter.com': 'Social Media',
    'x.com': 'Social Media',
    'tiktok.com': 'Social Media',
    'linkedin.com': 'Social Media',
    'reddit.com': 'Social Media',
    'pinterest.com': 'Social Media',
    'snapchat.com': 'Social Media',
    'whatsapp.com': 'Social Media',
    'telegram.org': 'Social Media',
    'discord.com': 'Social Media',
    'threads.net': 'Social Media',
    'mastodon.social': 'Social Media',

    // Entertainment
    'youtube.com': 'Entertainment',
    'netflix.com': 'Entertainment',
    'spotify.com': 'Entertainment',
    'twitch.tv': 'Entertainment',
    'hulu.com': 'Entertainment',
    'disneyplus.com': 'Entertainment',
    'hbomax.com': 'Entertainment',
    'max.com': 'Entertainment',
    'primevideo.com': 'Entertainment',
    'crunchyroll.com': 'Entertainment',
    'soundcloud.com': 'Entertainment',
    'vimeo.com': 'Entertainment',
    'dailymotion.com': 'Entertainment',
    'music.apple.com': 'Entertainment',
    'music.youtube.com': 'Entertainment',

    // Work/Productivity
    'github.com': 'Work/Productivity',
    'gitlab.com': 'Work/Productivity',
    'stackoverflow.com': 'Work/Productivity',
    'slack.com': 'Work/Productivity',
    'notion.so': 'Work/Productivity',
    'trello.com': 'Work/Productivity',
    'asana.com': 'Work/Productivity',
    'figma.com': 'Work/Productivity',
    'canva.com': 'Work/Productivity',
    'zoom.us': 'Work/Productivity',
    'meet.google.com': 'Work/Productivity',
    'teams.microsoft.com': 'Work/Productivity',
    'dropbox.com': 'Work/Productivity',
    'drive.google.com': 'Work/Productivity',
    'docs.google.com': 'Work/Productivity',
    'sheets.google.com': 'Work/Productivity',
    'mail.google.com': 'Work/Productivity',
    'outlook.com': 'Work/Productivity',
    'office.com': 'Work/Productivity',
    'vercel.com': 'Work/Productivity',
    'netlify.com': 'Work/Productivity',
    'cloudflare.com': 'Work/Productivity',
    'aws.amazon.com': 'Work/Productivity',
    'console.cloud.google.com': 'Work/Productivity',
    'azure.microsoft.com': 'Work/Productivity',
    'linear.app': 'Work/Productivity',
    'clickup.com': 'Work/Productivity',
    'monday.com': 'Work/Productivity',
    'airtable.com': 'Work/Productivity',
    'miro.com': 'Work/Productivity',

    // Shopping
    'amazon.com': 'Shopping',
    'ebay.com': 'Shopping',
    'walmart.com': 'Shopping',
    'target.com': 'Shopping',
    'bestbuy.com': 'Shopping',
    'etsy.com': 'Shopping',
    'aliexpress.com': 'Shopping',
    'shein.com': 'Shopping',
    'nike.com': 'Shopping',
    'adidas.com': 'Shopping',
    'costco.com': 'Shopping',
    'homedepot.com': 'Shopping',
    'lowes.com': 'Shopping',
    'macys.com': 'Shopping',
    'nordstrom.com': 'Shopping',
    'zappos.com': 'Shopping',

    // Education
    'coursera.org': 'Education',
    'udemy.com': 'Education',
    'edx.org': 'Education',
    'khanacademy.org': 'Education',
    'duolingo.com': 'Education',
    'quizlet.com': 'Education',
    'wikipedia.org': 'Education',
    'britannica.com': 'Education',
    'scholar.google.com': 'Education',
    'wolframalpha.com': 'Education',
    'grammarly.com': 'Education',

    // News
    'cnn.com': 'News',
    'bbc.com': 'News',
    'nytimes.com': 'News',
    'washingtonpost.com': 'News',
    'theguardian.com': 'News',
    'reuters.com': 'News',
    'apnews.com': 'News',
    'npr.org': 'News',
    'wsj.com': 'News',
    'bloomberg.com': 'News',
    'forbes.com': 'News',
    'techcrunch.com': 'News',
    'theverge.com': 'News',
    'wired.com': 'News',
    'arstechnica.com': 'News',
    'engadget.com': 'News',
    'mashable.com': 'News',

    // Finance
    'paypal.com': 'Finance',
    'venmo.com': 'Finance',
    'chase.com': 'Finance',
    'bankofamerica.com': 'Finance',
    'wellsfargo.com': 'Finance',
    'capitalone.com': 'Finance',
    'robinhood.com': 'Finance',
    'coinbase.com': 'Finance',
    'mint.com': 'Finance',
    'turbotax.com': 'Finance',

    // Health
    'webmd.com': 'Health',
    'mayoclinic.org': 'Health',
    'healthline.com': 'Health',
    'fitbit.com': 'Health',
    'myfitnesspal.com': 'Health',
    'strava.com': 'Health',
    'headspace.com': 'Health',
    'calm.com': 'Health',

    // Travel
    'booking.com': 'Travel',
    'expedia.com': 'Travel',
    'airbnb.com': 'Travel',
    'tripadvisor.com': 'Travel',
    'kayak.com': 'Travel',
    'skyscanner.com': 'Travel',
    'uber.com': 'Travel',
    'lyft.com': 'Travel',
    'maps.google.com': 'Travel',

    // Search
    'google.com': 'Work/Productivity',
    'bing.com': 'Work/Productivity',
    'duckduckgo.com': 'Work/Productivity',
    'search.yahoo.com': 'Work/Productivity'
};

// Static database of common Android packages
const STATIC_PACKAGES = {
    // Google
    'com.google.android.youtube': 'Entertainment',
    'com.google.android.apps.youtube.music': 'Entertainment',
    'com.google.android.gm': 'Work/Productivity',
    'com.google.android.apps.maps': 'Travel',
    'com.google.android.apps.docs': 'Work/Productivity',
    'com.google.android.apps.photos': 'Entertainment',
    'com.google.android.calendar': 'Work/Productivity',
    'com.google.android.apps.messaging': 'Social Media',
    'com.google.android.dialer': 'Other',
    'com.google.android.apps.meetings': 'Work/Productivity',
    'com.google.android.keep': 'Work/Productivity',

    // Social Media
    'com.facebook.katana': 'Social Media',
    'com.facebook.orca': 'Social Media',
    'com.instagram.android': 'Social Media',
    'com.twitter.android': 'Social Media',
    'com.zhiliaoapp.musically': 'Social Media', // TikTok
    'com.snapchat.android': 'Social Media',
    'com.linkedin.android': 'Social Media',
    'com.reddit.frontpage': 'Social Media',
    'com.pinterest': 'Social Media',
    'com.whatsapp': 'Social Media',
    'org.telegram.messenger': 'Social Media',
    'com.discord': 'Social Media',
    'com.Slack': 'Work/Productivity',

    // Entertainment
    'com.netflix.mediaclient': 'Entertainment',
    'com.spotify.music': 'Entertainment',
    'tv.twitch.android.app': 'Entertainment',
    'com.hulu.plus': 'Entertainment',
    'com.disney.disneyplus': 'Entertainment',
    'com.hbo.hbonow': 'Entertainment',
    'com.amazon.avod.thirdpartyclient': 'Entertainment', // Prime Video
    'com.crunchyroll.crunchyroid': 'Entertainment',
    'com.soundcloud.android': 'Entertainment',
    'com.pandora.android': 'Entertainment',

    // Shopping
    'com.amazon.mShop.android.shopping': 'Shopping',
    'com.ebay.mobile': 'Shopping',
    'com.walmart.android': 'Shopping',
    'com.target.ui': 'Shopping',
    'com.alibaba.aliexpresshd': 'Shopping',
    'com.shopify.mobile': 'Shopping',
    'com.etsy.android': 'Shopping',

    // Food Delivery
    'com.dd.doordash': 'Food',
    'com.ubercab.eats': 'Food',
    'com.grubhub.android': 'Food',
    'com.postmates.android': 'Food',
    'com.instacart.client': 'Food',

    // Finance
    'com.paypal.android.p2pmobile': 'Finance',
    'com.venmo': 'Finance',
    'com.chase.sig.android': 'Finance',
    'com.wf.wellsfargomobile': 'Finance',
    'com.infonow.bofa': 'Finance',
    'com.robinhood.android': 'Finance',
    'com.coinbase.android': 'Finance',

    // Travel
    'com.booking': 'Travel',
    'com.expedia.bookings': 'Travel',
    'com.airbnb.android': 'Travel',
    'com.tripadvisor.tripadvisor': 'Travel',
    'com.ubercab': 'Travel',
    'me.lyft.android': 'Travel',
    'com.waze': 'Travel',

    // Education
    'com.duolingo': 'Education',
    'com.quizlet.quizletandroid': 'Education',
    'org.coursera.android': 'Education',
    'org.khanacademy.android': 'Education',
    'com.udemy.android': 'Education',

    // Health
    'com.fitbit.FitbitMobile': 'Health',
    'com.myfitnesspal.android': 'Health',
    'com.strava': 'Health',
    'com.calm.android': 'Health',
    'com.getsomeheadspace.android': 'Health',

    // System/Other
    'com.android.settings': 'Other',
    'com.android.vending': 'Shopping', // Play Store
    'com.android.chrome': 'Work/Productivity',
    'com.sec.android.app.launcher': 'Other',
    'com.samsung.android.messaging': 'Social Media'
};

/**
 * Normalize domain (remove www., subdomain handling)
 */
function normalizeDomain(domain) {
    if (!domain) return null;
    return domain.toLowerCase()
        .replace(/^www\./, '')
        .replace(/^m\./, '')
        .replace(/^mobile\./, '')
        .trim();
}

/**
 * Try pattern matching for category
 */
function matchByPattern(input, isPackage = false) {
    const normalized = input.toLowerCase();

    for (const [category, patterns] of Object.entries(CATEGORY_PATTERNS)) {
        const regexList = isPackage ? patterns.packages : patterns.domains;
        for (const regex of regexList) {
            if (regex.test(normalized)) {
                return category;
            }
        }
    }
    return null;
}

/**
 * Lookup in static database
 */
function lookupStatic(input, isPackage = false) {
    if (isPackage) {
        return STATIC_PACKAGES[input] || null;
    }

    const normalized = normalizeDomain(input);
    if (!normalized) return null;

    // Try exact match
    if (STATIC_DOMAINS[normalized]) {
        return STATIC_DOMAINS[normalized];
    }

    // Try without first subdomain
    const parts = normalized.split('.');
    if (parts.length > 2) {
        const parentDomain = parts.slice(1).join('.');
        if (STATIC_DOMAINS[parentDomain]) {
            return STATIC_DOMAINS[parentDomain];
        }
    }

    return null;
}

/**
 * Main categorization function (without LLM - just local logic)
 * Returns category or null if unknown
 */
function categorizeLocally(input, isPackage = false) {
    // 1. Static database lookup
    const staticResult = lookupStatic(input, isPackage);
    if (staticResult) return staticResult;

    // 2. Pattern matching
    const patternResult = matchByPattern(input, isPackage);
    if (patternResult) return patternResult;

    return null;
}

export {
    categorizeLocally,
    normalizeDomain,
    matchByPattern,
    lookupStatic,
    STATIC_DOMAINS,
    STATIC_PACKAGES
};
