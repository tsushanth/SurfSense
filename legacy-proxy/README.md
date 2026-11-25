# Legacy address proxy

Android builds older than 10.0.0 (versionCode 7 and 8) call
`https://usage-tracker-backend-917362189743.us-central1.run.app/`, compiled into the app. That Cloud Run service no
longer exists, so every request answers 404. This service forwards those requests to the live backend on Fly.

**Status: not deployed.** It is a new public Cloud Run service, so it needs the project owner's go-ahead.

## Deploy

From the repository root, with `main` checked out:

    gcloud run deploy usage-tracker-backend --source legacy-proxy \
      --project summarizerproxy --region us-central1 --allow-unauthenticated \
      --min-instances 0 --max-instances 1 --cpu 1 --memory 256Mi --concurrency 80 --timeout 30

The service name, project and region must not change: they form the address the old apps use.

## Check

    curl https://usage-tracker-backend-917362189743.us-central1.run.app/health

## Remove

    gcloud run services delete usage-tracker-backend --project summarizerproxy --region us-central1

Do it once Play shows few devices left on versionCode 8 or lower.

## Cost

Scales to zero. At the expected load (about 50 devices) it stays inside the Cloud Run free tier. The one instance cap
bounds the worst case (a public address kept busy all month) at roughly 65 USD.

## Limits

- All old devices reach the backend from the proxy's address, so they share the backend's per-address rate limits
  (100 requests a minute; 5 linking attempts per 15 minutes).
- The old app's background worker does not load its saved key, so it syncs only while the app process that was opened by
  the user is alive. That is in the shipped app and cannot be fixed from the server.
