FROM node:24-slim

ENV NODE_ENV=production
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends tini \
    && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm ci --omit=dev

COPY --chown=node:node . .

USER node
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "server.js"]
